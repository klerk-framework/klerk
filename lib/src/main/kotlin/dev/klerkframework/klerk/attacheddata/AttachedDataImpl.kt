package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.BlobContainer
import dev.klerkframework.klerk.datatypes.BlobStepArgs
import dev.klerkframework.klerk.datatypes.BlobStepResult
import dev.klerkframework.klerk.job.JobExecution
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.currentJobId
import dev.klerkframework.klerk.misc.AttachedDataIdAllocator
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.ReadBlockGuard
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What is known about one piece of attached data, apart from the value itself.
 *
 * Keeping this in memory is not extra overhead: an id→owner map is needed anyway, both to reject a second model
 * claiming an owned id and to let [KlerkAttachedData.get]'s authorization rule reach the owning model. Id allocation
 * probes the same map. Holding the rest here too means serving attached data needs no database round-trip for
 * anything but the value.
 *
 * @property owner the id of the owning model, or null while the data is unclaimed
 * @property metadata what [KlerkAttachedData.getMetadata] reports. Null only between the moment an id is reserved and
 * the moment the value has been written — a window in which the data is unclaimed, and therefore unreadable anyway.
 * @property expires when an unclaimed value is reaped. Null once claimed by a model.
 * @property claimedByJob the job that prepared this value and has not finished with it, or null. This is the second,
 * independent claim: the reaper deletes only when there is neither a model reference nor a job claim, so a
 * long-running job's working set is safe for as long as the job lives — including while it is dead-lettered and
 * awaiting a human.
 */
internal data class AttachedDataEntry(
    val owner: Int?,
    val metadata: AttachedDataMetadata?,
    val expires: Instant?,
    val claimedByJob: JobId? = null,
)

internal class AttachedDataImpl<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val readWriteLock: ReadWriteLock,
    private val settings: KlerkSettings,
) : KlerkAttachedData<C> {

    private val config get() = klerk.config

    // Rebuilt from storage at startup, like ModelCache. Read and written from prepare (which deliberately runs outside
    // the serialized command path) as well as from commit, hence Concurrent. Blobs and strings share it, and thus
    // share one id space: an id identifies a value on its own, which is what makes it usable as a cache key.
    private val entries = ConcurrentHashMap<Int, AttachedDataEntry>()

    private val allocator = AttachedDataIdAllocator()
    private val lastReap = AtomicReference(Instant.DISTANT_PAST)

    /**
     * One entry per value whose steps are being run, so that [awaitProcessing] can be told what happened. Completed
     * with null when every step has run, and with the reason when one refused the file.
     *
     * A value nobody waits for leaves an entry behind until it is claimed ([forget]) or reaped ([maybeReap]).
     */
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<String?>>()

    private val jobs: JobManagerInternal<C, V> get() = klerk.impl().jobs

    /** Where blob bytes live when they are not in the database, or null when they are. */
    private val externalBlobs: AttachedBlobStore.External?
        get() = config.attachedBlobStore as? AttachedBlobStore.External

    /**
     * Rebuilds the in-memory state from storage. Rows that are unclaimed and already past their expiry are reaped
     * rather than reserved.
     */
    internal fun start() {
        val now = config.now()
        deleteBytes(config.persistence.deleteExpiredAttachedData(now))
        val rows = config.persistence.readAllAttachedDataMetadata()
        entries.clear()
        rows.forEach { (id, row) ->
            entries[id] = AttachedDataEntry(row.owner, row.metadata, row.expires, row.claimedByJob)
        }
        lastReap.set(now)
        reconcileExternalBlobs()
        logger.info { "Attached data ready (${entries.size} values)" }
    }

    /**
     * Compares the rows against what the blob store actually holds.
     *
     * Bytes without a row are orphans — a crash between writing them and committing the row, or between deleting the
     * row and deleting them — and are deleted. A row without bytes means the store was changed under an existing
     * database, which Klerk cannot repair and will not paper over: the first user to open an old attachment would
     * otherwise get an unexplained "not found".
     */
    private fun reconcileExternalBlobs() {
        val store = externalBlobs ?: return
        val stored = store.listIds() ?: return
        val expected = entries.filterValues { it.metadata?.kind == AttachedDataKind.Blob }.keys

        val missing = expected.minus(stored)
        if (missing.isNotEmpty()) {
            throw IllegalConfigurationException(
                KlerkErrorCode.AttachedBlobStoreMissingData,
                "The configured attachedBlobStore does not have the bytes for ${missing.size} blob(s) that this " +
                        "database refers to (for example id ${missing.first()}). This happens when the store is " +
                        "changed after the application already has data — Klerk does not move blobs between stores."
            )
        }

        val orphans = stored.minus(expected)
        if (orphans.isNotEmpty()) {
            logger.info { "Deleting ${orphans.size} orphaned blob(s) from the blob store" }
            orphans.forEach { store.delete(it) }
        }
    }

    /**
     * The bytes of [id], without authorization: this is for Klerk's own use inside the command pipeline, where the
     * question is whether a value may be attached rather than whether somebody may read it.
     */
    private fun openValue(id: Int, kind: AttachedDataKind): InputStream {
        val store = if (kind == AttachedDataKind.Blob) externalBlobs else null
        return store?.get(id)
            ?: config.persistence.getAttachedValue(id)
            ?: throw NoSuchElementException("No data found for id $id")
    }

    /** Removes the bytes of [ids] from the blob store, if that is where they are. Never throws. */
    private fun deleteBytes(ids: Set<Int>) {
        val store = externalBlobs ?: return
        ids.forEach { id ->
            // Failing to delete leaves an orphan, which the next startup sweeps. Failing the command would be worse.
            runCatching { store.delete(id) }
                .onFailure { logger.error(it) { "Could not delete the bytes of attached data $id" } }
        }
    }

    override suspend fun prepare(
        value: InputStream,
        declaration: KClass<out BlobContainer>,
        context: C,
        metadata: Map<String, String>,
        lease: Duration?,
    ): AttachedBlobID =
    // Private until a command attaches it: the property it lands in is what decides, and until then nothing can
        // read it anyway.
        AttachedBlobID(
            insert(
                value,
                AttachedDataKind.Blob,
                context,
                AttachedDataVisibility.Private,
                metadata,
                lease,
                declaration = declaration,
            )
        )

    override suspend fun prepareFromFile(
        file: Path,
        declaration: KClass<out BlobContainer>,
        context: C,
        metadata: Map<String, String>,
        lease: Duration?,
    ): AttachedBlobID = AttachedBlobID(
        insert(
            Files.newInputStream(file),
            AttachedDataKind.Blob,
            context,
            AttachedDataVisibility.Private,
            metadata,
            lease,
            adoptFrom = file,
            declaration = declaration,
        )
    )

    override suspend fun prepare(
        value: String,
        context: C,
        visibility: AttachedDataVisibility,
        metadata: Map<String, String>,
        lease: Duration?,
    ): AttachedStringID =
        AttachedStringID(insert(value.byteInputStream(), AttachedDataKind.String, context, visibility, metadata, lease))

    /**
     * Runs the first step [declaration] declares that this value has not been through yet, and records that it has.
     *
     * One step at a time, because the job that calls this yields in between: an expensive pipeline then checkpoints
     * between its stages, and a retry re-runs only the stage that failed. What has already run is read from the value
     * itself, so a yield, a retry and a restart all resume the same way, and a step that rewrites the bytes cannot
     * run twice. A step may only rewrite them while the value is unclaimed; once a model owns it, it is immutable.
     *
     * Not authorized: what runs here is what the developer declared on the property, not something an actor chose.
     *
     * @throws BlobRejected if the step refuses the file, or if it does not satisfy `accept`/`maxSize` — which are
     * re-checked first, and again after a step that replaces the bytes.
     * @throws IllegalStateException if the value has already been claimed by a model.
     */
    internal suspend fun processNextStep(declaration: BlobContainer): BlobProcessing {
        val id = declaration.id.id
        val entry = entries[id] ?: throw NoSuchElementException("No data found for id ${declaration.id}")
        check(entry.owner == null) {
            "The attached data ${declaration.id} is already claimed by model ${entry.owner}, and a claimed value " +
                    "never changes"
        }
        var metadata = checkNotNull(entry.metadata) { "The data ${declaration.id} has not been written yet" }

        // Cheap first: a file the property will not accept anyway should not be scanned or disarmed. The claim
        // checks this again — this is about failing early, not about being the only check.
        declaration.reasonToReject(metadata)?.let { throw BlobRejected("The file is not acceptable here: $it") }

        val steps = declaration.stepsToRun
        val total = steps.size
        val completed = metadata.completedSteps.toMutableList()
        // Anything already recorded was done by an earlier step of the job, or before a restart. Running it again
        // could disarm twice.
        val next = steps.indexOfFirst { !completed.contains(it.first) }
        if (next == -1) {
            processed(id)
            return BlobProcessing.Done(total)
        }

        val (name, step) = steps[next]
        val result = LazyInputStream { openValue(id, metadata.kind) }
            .use { step(BlobStepArgs(it, metadata)) }
        when (result) {
            is BlobStepResult.Pass -> Unit
            is BlobStepResult.Reject -> throw BlobRejected(result.reason)
            is BlobStepResult.Replace -> {
                metadata = replaceValue(id, result.value, metadata)
                // A rewrite can change what the file is and how large it is, so the declaration applies again.
                declaration.reasonToReject(metadata)?.let {
                    throw BlobRejected("After '$name' the file is not acceptable here: $it")
                }
            }
        }
        completed.add(name)
        record(id, metadata, completed.toList())
        if (completed.size == total) {
            processed(id)
            return BlobProcessing.Done(total)
        }
        return BlobProcessing.More(name, completed.size, total)
    }

    override suspend fun awaitProcessing(id: AttachedBlobID, timeout: Duration) {
        // No waiter means there is nothing to wait for: the declaration had no steps, or they have already run.
        val waiter = waiters[id.id] ?: return
        if (config.jobs.execution == JobExecution.Manual) {
            // Nothing runs on its own here, so waiting would be waiting for a step nobody is going to take.
            jobs.runUntilIdle()
        }
        val rejection = try {
            withTimeout(timeout) { waiter.await() }
        } finally {
            waiters.remove(id.id)
        }
        rejection?.let { throw BlobRejected(it) }
    }

    /** Tells whoever is waiting that every step has run. */
    private fun processed(id: Int) {
        waiters[id]?.complete(null)
    }

    /**
     * Throws away a value a step refused, and tells whoever is waiting why.
     *
     * Deleted rather than left to expire: nothing may ever attach it, and the reason has already been reported. The
     * dead-lettered job keeps the reason for anyone looking at the queue afterwards.
     */
    internal fun rejected(id: Int, reason: String) {
        entries.remove(id)
        runCatching { config.persistence.deleteAttachedData(setOf(id)) }
            .onFailure { logger.error(it) { "Could not delete the rejected attached data $id" } }
        deleteBytes(setOf(id))
        waiters[id]?.complete(reason)
    }

    /**
     * Writes new bytes over an unclaimed value, keeping its id.
     *
     * Internal on purpose: if the only thing that can change a value's bytes is a step its property declared, the
     * invariant is easy to state. A public version would let anything holding an id rewrite an unclaimed value.
     */
    private fun replaceValue(id: Int, newValue: InputStream, current: AttachedDataMetadata): AttachedDataMetadata {
        val hashing = HashingInputStream(newValue)
        val store = if (current.kind == AttachedDataKind.Blob) externalBlobs else null
        if (store != null) {
            // put() refuses to overwrite, which is what protects a live value from an id collision.
            store.delete(id)
            store.put(id, hashing)
        }
        config.persistence.updateAttachedData(id, if (store == null) hashing else null, current.completedSteps) {
            hashing.digest()
        }
        val digest = hashing.digest()
        return current.copy(size = digest.size, hash = digest.hash, contentType = digest.contentType)
    }

    /** Records that a step has run, so that an interrupted pipeline resumes rather than starting over. */
    private fun record(id: Int, metadata: AttachedDataMetadata, completed: List<String>) {
        config.persistence.updateAttachedData(id, null, completed) {
            AttachedDataDigest(metadata.size, metadata.hash, metadata.contentType)
        }
        entries[id] = entries[id]?.copy(metadata = metadata.copy(completedSteps = completed)) ?: return
    }

    /**
     * The one write path. A string differs from a blob only in its [AttachedDataKind] and in arriving as a stream over
     * its UTF-8 bytes, so both are measured, hashed and stored identically.
     */
    private suspend fun insert(
        value: InputStream,
        kind: AttachedDataKind,
        context: C,
        visibility: AttachedDataVisibility,
        metadata: Map<String, String>,
        lease: Duration? = null,
        adoptFrom: Path? = null,
        declaration: KClass<out BlobContainer>? = null,
    ): Int {
        // Before a byte is written: a declaration that cannot be built, declares no step, or declares one that is not
        // a named function reference is a programming error, and it should be reported where the mistake is rather
        // than from inside a job an hour later. The list is empty when the only step is noPreAttachProcessing, and
        // then there is nothing for a job to do.
        val steps = declaration?.let { instantiateDeclaration(it, AttachedBlobID(0)).stepNames } ?: emptyList()
        val requested = lease ?: settings.unclaimedAttachedDataLifetime
        require(requested <= settings.maxAttachedDataLease) {
            "A lease of $requested was requested, but the maximum is ${settings.maxAttachedDataLease} " +
                    "(KlerkSettings.maxAttachedDataLease)"
        }
        authorizeWrite(context, kind, visibility, requested)
        validateCustomMetadata(metadata)
        if (kind == AttachedDataKind.Blob && config.attachedBlobStore == AttachedBlobStore.None) {
            throw IllegalConfigurationException(
                KlerkErrorCode.AttachedBlobStoreIsNone,
                "The config says attachedBlobStore(None), so this application cannot store blobs."
            )
        }
        val createdAt = config.now()
        val expires = reserveExpiry(requested)
        val id = allocate(expires)
        // A job step often prepares data before any command references it, and the reaper would otherwise delete it a
        // minute later. Claiming it for the running job (if there is one) is recorded here, at insert time, because
        // the job's own commit may be many steps away.
        val claimedByJob = currentJobId()
        val hashing = HashingInputStream(value)
        // A blob kept outside the database is written first and the row committed after, so a crash can only leave
        // bytes nothing refers to. Those are swept at the next startup; the other order would lose the value instead.
        val store = if (kind == AttachedDataKind.Blob) externalBlobs else null
        try {
            // Adoption still reads the file — the size and hash have to be known — but it saves writing the bytes a
            // second time, which is the expensive half.
            val adopted = adoptFrom != null && store != null && run {
                // Read (and close) before moving the file: the digest has to be complete, and the stream is open on
                // the very file that adoption renames.
                hashing.use { it.copyTo(OutputStream.nullOutputStream()) }
                store.adopt(id, adoptFrom)
            }
            if (!adopted) {
                store?.put(id, hashing)
            }
            config.persistence.insertAttachedData(
                id, if (store == null) hashing else null, kind, visibility, createdAt, metadata, expires, claimedByJob
            ) {
                hashing.digest()
            }
            val digest = hashing.digest()
            entries[id] = AttachedDataEntry(
                owner = null,
                metadata = AttachedDataMetadata(
                    kind, visibility, createdAt, digest.size, digest.hash, metadata, digest.contentType
                ),
                expires = expires,
                claimedByJob = claimedByJob,
            )
        } catch (e: Exception) {
            entries.remove(id)
            store?.let { runCatching { it.delete(id) } }
            throw e
        }
        if (steps.isNotEmpty()) {
            scheduleProcessing(id, checkNotNull(declaration), context)
        }
        return id
    }

    /**
     * Puts the value's declared steps in the hands of a job.
     *
     * The job claims the value as it is created, in the same commit, so that the reaper cannot take it while a scan
     * that outlasts the lease is running. The claim is released when the job finishes.
     */
    private suspend fun scheduleProcessing(id: Int, declaration: KClass<out BlobContainer>, context: C) {
        val className = requireNotNull(declaration.qualifiedName) {
            "A blob declaration must be a named class, and $declaration is not"
        }
        // Registered before the job can possibly run, or a fast pipeline could finish before anyone can wait for it.
        waiters[id] = CompletableDeferred()
        val jobId = try {
            jobs.scheduleClaiming(
                job = config.jobs.processAttachedData.schedule(ProcessBlobCursor(id, className)),
                context = context,
                claim = setOf(id),
            )
        } catch (e: Exception) {
            waiters.remove(id)
            throw e
        }
        entries[id]?.let { entries[id] = it.copy(claimedByJob = jobId) }
    }

    /** The attached data [jobId] has claimed, so that its claims can be released when the job's row goes away. */
    internal fun claimedBy(jobId: JobId): Set<Int> =
        entries.filterValues { it.claimedByJob == jobId }.keys.toSet()

    /**
     * Drops the job claims on [ids] in memory, after the same change has been persisted.
     *
     * Releasing a job claim never deletes anything: a value a committed command attached to a live model still has its
     * model reference, and one nothing references is left to the ordinary reaper.
     */
    internal fun releaseJobClaims(ids: Set<Int>) {
        ids.forEach { id ->
            val entry = entries[id] ?: return@forEach
            entries[id] = entry.copy(claimedByJob = null)
        }
    }

    override suspend fun get(id: AttachedBlobID, context: C): InputStream =
        read(id.id, AttachedDataKind.Blob, id, context)

    override suspend fun get(id: AttachedStringID, context: C): String =
        read(id.id, AttachedDataKind.String, id, context).readBytes().decodeToString()

    override suspend fun getStream(id: AttachedStringID, context: C): InputStream =
        read(id.id, AttachedDataKind.String, id, context)

    /**
     * The one read path: authorize, check that the id was used as the kind it actually is, then fetch the value.
     */
    private suspend fun read(id: Int, expected: AttachedDataKind, publicId: Any, context: C): InputStream {
        val entry = authorizeRead(entries[id], publicId, context, "klerk.attachedData.get")
        requireKind(entry, expected, publicId)
        val row =
            config.persistence.getAttachedData(id) ?: throw NoSuchElementException("No data found for id $publicId")
        check(entry.owner == row.owner) { "The in-memory state of attached data $publicId does not match the database" }
        val store = if (expected == AttachedDataKind.Blob) externalBlobs else null
        return if (store == null) {
            config.persistence.getAttachedValue(id)
                ?: throw NoSuchElementException("No data found for id $publicId")
        } else {
            store.get(id) ?: throw NoSuchElementException(
                "The attached data $publicId has a row but no bytes in the blob store"
            )
        }
    }

    override suspend fun getMetadata(id: AttachedBlobID, context: C): AttachedDataMetadata =
        readMetadata(id.id, AttachedDataKind.Blob, id, context)

    override suspend fun getMetadata(id: AttachedStringID, context: C): AttachedDataMetadata =
        readMetadata(id.id, AttachedDataKind.String, id, context)

    private suspend fun readMetadata(
        id: Int,
        expected: AttachedDataKind,
        publicId: Any,
        context: C
    ): AttachedDataMetadata {
        val entry = authorizeRead(entries[id], publicId, context, "klerk.attachedData.getMetadata")
        return requireKind(entry, expected, publicId)
    }

    /**
     * Rejects an id used as the wrong kind, e.g. a blob id passed as an [AttachedStringID]. Checked after
     * authorization, so that an actor who may not read the data cannot learn what it is either.
     *
     * Any entry that gets this far is claimed, and a claimed entry always has its metadata (it is written before the
     * command that could claim it can even see the id).
     *
     * @return the metadata, now known to be non-null.
     */
    private fun requireKind(
        entry: AttachedDataEntry,
        expected: AttachedDataKind,
        id: Any
    ): AttachedDataMetadata {
        val metadata = checkNotNull(entry.metadata) { "The data with id $id has no metadata" }
        if (metadata.kind != expected) {
            throw NoSuchElementException(
                "The attached data with id $id is a ${metadata.kind}, not a $expected. Blobs and strings share one id " +
                        "space, so an id may only be used through the type it was prepared as."
            )
        }
        return metadata
    }

    private fun validateCustomMetadata(metadata: Map<String, String>) {
        if (metadata.isEmpty()) {
            return
        }
        // Klerk's own findings about a value travel in the same map. Reserving the prefix keeps "what the bytes are"
        // separate from "what somebody said they are", which is the only reason the former can be trusted.
        require(metadata.keys.none { it.startsWith("__") }) {
            "Metadata keys starting with '__' are reserved for Klerk: ${metadata.keys.filter { it.startsWith("__") }}"
        }
        val length = metadata.entries.sumOf { it.key.length + it.value.length + JSON_OVERHEAD_PER_ENTRY }
        require(length <= MAX_CUSTOM_METADATA_LENGTH) {
            "The metadata is too large ($length characters, at most $MAX_CUSTOM_METADATA_LENGTH are allowed). " +
                    "It is kept in memory for as long as the data exists, so put large values in the data itself."
        }
    }

    /**
     * The expiry an unclaimed value gets.
     *
     * Deliberately based on the real clock rather than `context.time`: the context clock is supplied by the caller and
     * must not be able to extend or shorten the claim window.
     */
    private fun reserveExpiry(lease: Duration): Instant = config.now().plus(lease)

    /**
     * Reserves an id. The entry is a placeholder without metadata until the value has been written — see
     * [AttachedDataEntry].
     */
    private suspend fun allocate(expires: Instant): Int {
        maybeReap()
        val entry = AttachedDataEntry(owner = null, metadata = null, expires = expires)
        return allocator.getNextAttachedDataID { candidate ->
            entries.putIfAbsent(candidate, entry) == null
        }
    }

    /**
     * Removes expired unclaimed values. Cheap enough to do from [prepare], which is slow anyway, but not more often
     * than once per lifetime window.
     */
    private fun maybeReap() {
        val now = config.now()
        val previous = lastReap.get()
        if (now < previous.plus(settings.unclaimedAttachedDataLifetime)) {
            return
        }
        if (!lastReap.compareAndSet(previous, now)) {
            return
        }
        entries.entries.removeIf { it.value.isExpired(now) }
        deleteBytes(config.persistence.deleteExpiredAttachedData(now))
        // Whatever no longer has an entry has nothing left to wait for either — a value that was reaped, or one a
        // step refused that nobody was waiting for.
        waiters.keys.removeIf { !entries.containsKey(it) }
    }

    // ---------------------------------------------------------------- authorization

    private suspend fun authorizeWrite(
        context: C,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        lease: Duration,
    ) {
        if (context.actor == SystemIdentity) {
            return
        }
        val args = ArgsForAttachedDataWrite(kind, visibility, context, ReaderWithoutAuth<C, V>(klerk), lease)
        // The reader is only sound while the lock is held, so it is used for the rule and nothing else — never across
        // the upload.
        readWriteLock.acquireRead()
        try {
            if (config.authorization.attachedDataWritePositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
                throw AuthorizationException(
                    KlerkErrorCode.AttachedDataWritePositiveAuthorizationMissing,
                    "Not allowed to prepare attached data"
                )
            }
            if (config.authorization.attachedDataWriteNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
                throw AuthorizationException(
                    KlerkErrorCode.AttachedDataWriteNegativeAuthorizationExist,
                    "Not allowed to prepare attached data"
                )
            }
        } finally {
            readWriteLock.releaseRead()
        }
    }

    /**
     * Applies the read rules, holding the read lock only for as long as they run — never across the returned stream.
     *
     * Public data skips the rules entirely, before the lock is even taken. That is what makes the decision stable over
     * time (and thus cacheable), and it also means serving public data never contends with command processing.
     */
    private suspend fun authorizeRead(
        entry: AttachedDataEntry?,
        id: Any,
        context: C,
        caller: String
    ): AttachedDataEntry {
        ReadBlockGuard.checkNotInsideReadBlock(caller)
        if (entry == null || entry.isExpired(config.now())) {
            throw NoSuchElementException("No data found for id $id")
        }
        // Unclaimed data is not reachable: attached data is always read through the model that owns it.
        val ownerId = entry.owner ?: throw NoSuchElementException(
            "The data with id $id has not been attached to a model yet"
        )
        if (context.actor == SystemIdentity || entry.metadata?.visibility == AttachedDataVisibility.Public) {
            return entry
        }
        readWriteLock.acquireRead()
        try {
            val owner = ModelCache.getOrNull(ModelID<Any>(ownerId))
                ?: throw NoSuchElementException("Could not find the model owning the data with id $id")
            val args = ArgsForAttachedDataRead(owner, context, ReaderWithoutAuth<C, V>(klerk))
            if (config.authorization.attachedDataReadPositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
                throw AuthorizationException(
                    KlerkErrorCode.AttachedDataReadPositiveAuthorizationMissing,
                    "Not allowed to read attached data"
                )
            }
            if (config.authorization.attachedDataReadNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
                throw AuthorizationException(
                    KlerkErrorCode.AttachedDataReadNegativeAuthorizationExist,
                    "Not allowed to read attached data"
                )
            }
        } finally {
            readWriteLock.releaseRead()
        }
        return entry
    }

    // ---------------------------------------------------------------- commit-time diff

    /**
     * Works out what should happen to the attached data as a result of a command, by diffing each affected model's
     * attached-data ids before and after.
     *
     * This one mechanism covers every lifecycle transition: ids that appear are claimed, ids that disappear are
     * deleted. Claim-on-create, claim-on-update, delete-on-null, delete-on-replace and delete-on-model-delete all fall
     * out of it. (A rule phrased as "the reference became null, so delete" would miss replacement and leak a value on
     * every change.)
     *
     * @return the changes to apply, or the problems that should fail the command.
     */
    internal fun <T : Any> planFor(delta: ProcessingData<T, C, V>): AttachedDataPlan {
        val affected = delta.aggregatedModelState.keys.plus(delta.deletedModels)
        if (affected.isEmpty()) {
            return AttachedDataPlan.Ok(AttachedDataDelta())
        }

        val now = config.now()
        val problems = mutableListOf<Problem>()
        val claimed = mutableMapOf<Int, AttachedDataClaim>()
        val deleted = mutableSetOf<Int>()

        affected.forEach { modelId ->
            val before = ModelCache.getOrNull(ModelID<Any>(modelId.value))
                ?.let { collectAttachedData(it.props) } ?: emptyMap()
            val after = if (delta.deletedModels.contains(modelId)) emptyMap() else
                delta.aggregatedModelState[modelId]?.let { collectAttachedData(it.props) } ?: emptyMap()

            claim(after.minus(before.keys).values, modelId, claimed, now, problems)
            deleted.addAll(before.keys.minus(after.keys))
        }

        if (problems.isNotEmpty()) {
            return AttachedDataPlan.Rejected(problems)
        }
        return AttachedDataPlan.Ok(AttachedDataDelta(claimed, deleted))
    }

    private fun claim(
        references: Collection<AttachedDataReference>,
        modelId: ModelID<out Any>,
        claims: MutableMap<Int, AttachedDataClaim>,
        now: Instant,
        problems: MutableList<Problem>
    ) {
        references.forEach { reference ->
            val id = reference.id
            val entry = entries[id]
            if (entry == null || entry.isExpired(now)) {
                problems.add(
                    StateProblem(
                        "There is no attached data with id $id (it may have expired)",
                        "The attached data with id $id does not exist or has expired, so $modelId cannot claim it",
                        KlerkErrorCode.AttachedDataNotFound
                    )
                )
                return@forEach
            }
            val currentOwner = entry.owner ?: claims[id]?.owner
            if (currentOwner != null && currentOwner != modelId.value) {
                problems.add(
                    StateProblem(
                        "The attached data with id $id is already owned by another model",
                        "The attached data with id $id is owned by model $currentOwner, so $modelId cannot claim it",
                        KlerkErrorCode.AttachedDataAlreadyOwned
                    )
                )
                return@forEach
            }

            // What the property declares, checked against what Klerk found the bytes to be. Metadata only: the value
            // itself may be gigabytes, and reading it here would put the upload back inside command processing.
            val declaration = reference.declaration
            val metadata = entry.metadata
            if (declaration != null && metadata != null) {
                // Metadata only. What a step concluded was recorded when it ran; running one here would put a virus
                // scan or a disarm pass inside command processing, with every other command waiting behind it.
                val unacceptable = declaration.reasonToReject(metadata)
                if (unacceptable != null) {
                    problems.add(
                        StateProblem(
                            "The file is not acceptable here: $unacceptable",
                            "The attached data with id $id cannot be claimed by $modelId: $unacceptable",
                            KlerkErrorCode.AttachedDataNotAcceptable
                        )
                    )
                    return@forEach
                }
                val missing = declaration.stepNames.firstOrNull { !metadata.completedSteps.contains(it) }
                if (missing != null) {
                    problems.add(
                        StateProblem(
                            "The file has not finished being checked",
                            "The attached data with id $id cannot be claimed by $modelId: it has not been through " +
                                    "'$missing'. Wait for klerk.attachedData.awaitProcessing(...) before attaching it.",
                            KlerkErrorCode.AttachedDataNotProcessed
                        )
                    )
                    return@forEach
                }
            }

            claims[id] = AttachedDataClaim(
                owner = modelId.value,
                // Declared by the property, since whoever uploaded the bytes could not have known what they were for.
                // A bare AttachedBlobID declares nothing, so whatever was chosen at prepare time stands.
                visibility = declaration?.visibility ?: metadata?.visibility ?: AttachedDataVisibility.Private,
            )
        }
    }

    /**
     * Applies what [planFor] decided to the in-memory state. Must be called after the data has been persisted, while
     * the write lock is held.
     */
    internal fun applyToMemory(attachedData: AttachedDataDelta) {
        attachedData.claimed.forEach { (id, claim) ->
            val entry = entries[id] ?: return@forEach
            entries[id] = entry.copy(
                owner = claim.owner,
                expires = null,
                metadata = entry.metadata?.copy(visibility = claim.visibility),
            )
        }
        attachedData.deleted.forEach { entries.remove(it) }
        // After the row is gone, never before: bytes nothing refers to are swept at startup, but a row referring to
        // bytes that were already deleted would be a hard error on the next read.
        deleteBytes(attachedData.deleted)
        forget(attachedData.claimed.keys + attachedData.deleted)
    }

    /** Drops the waiters of values that are no longer waiting for anything: a model has them, or they are gone. */
    private fun forget(ids: Set<Int>) {
        ids.forEach { waiters.remove(it) }
    }

}

/**
 * A value a job has claimed never expires while the claim lasts, even though no model owns it yet. The two claims are
 * independent: the reaper takes a value only when neither holds.
 */
internal fun AttachedDataEntry.isExpired(now: Instant): Boolean =
    claimedByJob == null && expires?.let { it < now } ?: false

/** Roughly what `{"key":"value",}` costs on top of the key and the value themselves. */
private const val JSON_OVERHEAD_PER_ENTRY = 6
private const val MAX_CUSTOM_METADATA_LENGTH = 1000

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Measures and hashes a stream as it is read, so that a blob can be described without ever being held in memory.
 *
 * Both read overloads must be implemented: [java.io.FilterInputStream.read] with a buffer delegates straight to the
 * wrapped stream, so relying on the inherited one would silently miss almost every byte.
 */
private class HashingInputStream(private val source: InputStream) : InputStream() {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0L
    private var result: AttachedDataDigest? = null

    // The first bytes, kept so that the value's type can be recognised without reading it a second time.
    private val head = ByteArrayOutputStream(SNIFF_LENGTH)

    override fun read(): Int {
        val b = source.read()
        if (b != -1) {
            digest.update(b.toByte())
            size++
            if (head.size() < SNIFF_LENGTH) {
                head.write(b)
            }
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = source.read(b, off, len)
        if (read > 0) {
            digest.update(b, off, read)
            size += read
            val wanted = minOf(read, SNIFF_LENGTH - head.size())
            if (wanted > 0) {
                head.write(b, off, wanted)
            }
        }
        return read
    }


    override fun available(): Int = source.available()

    override fun close(): Unit = source.close()

    /**
     * The size, hash and detected type of everything read so far. Idempotent: [MessageDigest.digest] resets the
     * digest, so the answer is computed once and remembered — callers may well ask more than once.
     */
    fun digest(): AttachedDataDigest = result ?: AttachedDataDigest(
        size = size,
        hash = digest.digest().toHex(),
        contentType = detectContentType(head.toByteArray()),
    ).also { result = it }
}

internal sealed class AttachedDataPlan {
    internal data class Ok(val delta: AttachedDataDelta) : AttachedDataPlan()
    internal data class Rejected(val problems: List<Problem>) : AttachedDataPlan()
}

/**
 * A stream that opens the underlying one on the first read, and closes nothing if it never did.
 *
 * This is what makes a [dev.klerkframework.klerk.datatypes.BlobStep] that decides from the metadata alone free: the
 * value is fetched from wherever it lives only if the step actually asks for a byte.
 */
private class LazyInputStream(private val open: () -> InputStream) : InputStream() {

    private var source: InputStream? = null

    private fun source(): InputStream = source ?: open().also { source = it }

    override fun read(): Int = source().read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = source().read(b, off, len)

    override fun available(): Int = source?.available() ?: 0

    override fun close() {
        source?.close()
    }
}

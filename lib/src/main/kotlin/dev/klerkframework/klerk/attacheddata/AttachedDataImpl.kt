package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.currentJobId
import dev.klerkframework.klerk.misc.AttachedDataIdAllocator
import dev.klerkframework.klerk.misc.ReadWriteLock

import dev.klerkframework.klerk.read.ReadBlockGuard
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.AttachedDataDelta
import dev.klerkframework.klerk.storage.ModelCache
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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
     * Rebuilds the in-memory state from storage. Rows that are unclaimed and already past their expiry are reaped
     * rather than reserved.
     */
    internal fun start() {
        val now = config.now()
        config.persistence.deleteExpiredAttachedData(now)
        val rows = config.persistence.readAllAttachedDataMetadata()
        entries.clear()
        rows.forEach { (id, row) ->
            entries[id] = AttachedDataEntry(row.owner, row.metadata, row.expires, row.claimedByJob)
        }
        lastReap.set(now)
        logger.info { "Attached data ready (${entries.size} values)" }
    }

    override suspend fun prepare(
        value: InputStream,
        context: C,
        visibility: AttachedDataVisibility,
        metadata: Map<String, String>,
    ): AttachedBlobID = AttachedBlobID(insert(value, AttachedDataKind.Blob, context, visibility, metadata))

    override suspend fun prepare(
        value: String,
        context: C,
        visibility: AttachedDataVisibility,
        metadata: Map<String, String>,
    ): AttachedStringID =
        AttachedStringID(insert(value.byteInputStream(), AttachedDataKind.String, context, visibility, metadata))

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
    ): Int {
        authorizeWrite(context, kind, visibility)
        validateCustomMetadata(metadata)
        val createdAt = config.now()
        val expires = reserveExpiry()
        val id = allocate(expires)
        // A job step often prepares data before any command references it, and the reaper would otherwise delete it a
        // minute later. Claiming it for the running job (if there is one) is recorded here, at insert time, because
        // the job's own commit may be many steps away.
        val claimedByJob = currentJobId()
        val hashing = HashingInputStream(value)
        try {
            config.persistence.insertAttachedData(
                id, hashing, kind, visibility, createdAt, metadata, expires, claimedByJob
            ) {
                hashing.sizeAndHash()
            }
            val (size, hash) = hashing.sizeAndHash()
            entries[id] = AttachedDataEntry(
                owner = null,
                metadata = AttachedDataMetadata(kind, visibility, createdAt, size, hash, metadata),
                expires = expires,
                claimedByJob = claimedByJob,
            )
        } catch (e: Exception) {
            entries.remove(id)
            throw e
        }
        return id
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
        val row = config.persistence.getAttachedData(id) ?: throw NoSuchElementException("No data found for id $publicId")
        check(entry.owner == row.owner) { "The in-memory state of attached data $publicId does not match the database" }
        return row.value
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
    private fun reserveExpiry(): Instant = config.now().plus(settings.unclaimedAttachedDataLifetime)

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
        config.persistence.deleteExpiredAttachedData(now)
    }

    // ---------------------------------------------------------------- authorization

    private suspend fun authorizeWrite(context: C, kind: AttachedDataKind, visibility: AttachedDataVisibility) {
        if (context.actor == SystemIdentity) {
            return
        }
        val args = ArgsForAttachedDataWrite(kind, visibility, context, ReaderWithoutAuth<C, V>(klerk))
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
        val claimed = mutableMapOf<Int, Int>()
        val deleted = mutableSetOf<Int>()

        affected.forEach { modelId ->
            val before = ModelCache.getOrNull(ModelID<Any>(modelId.value))
                ?.let { collectAttachedDataIds(it.props) } ?: emptySet()
            val after = if (delta.deletedModels.contains(modelId)) emptySet() else
                delta.aggregatedModelState[modelId]?.let { collectAttachedDataIds(it.props) } ?: emptySet()

            claim(after.minus(before), modelId, claimed, now, problems)
            deleted.addAll(before.minus(after))
        }

        if (problems.isNotEmpty()) {
            return AttachedDataPlan.Rejected(problems)
        }
        return AttachedDataPlan.Ok(AttachedDataDelta(claimed, deleted))
    }

    private fun claim(
        ids: Set<Int>,
        modelId: ModelID<out Any>,
        claims: MutableMap<Int, Int>,
        now: Instant,
        problems: MutableList<Problem>
    ) {
        ids.forEach { id ->
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
            val currentOwner = entry.owner ?: claims[id]
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
            claims[id] = modelId.value
        }
    }

    /**
     * Applies what [planFor] decided to the in-memory state. Must be called after the data has been persisted, while
     * the write lock is held.
     */
    internal fun applyToMemory(attachedData: AttachedDataDelta) {
        attachedData.claimed.forEach { (id, owner) ->
            val entry = entries[id] ?: return@forEach
            entries[id] = entry.copy(owner = owner, expires = null)
        }
        attachedData.deleted.forEach { entries.remove(it) }
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
    private var result: Pair<Long, String>? = null

    override fun read(): Int {
        val b = source.read()
        if (b != -1) {
            digest.update(b.toByte())
            size++
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = source.read(b, off, len)
        if (read > 0) {
            digest.update(b, off, read)
            size += read
        }
        return read
    }

    override fun available(): Int = source.available()

    override fun close(): Unit = source.close()

    /**
     * The size and hash of everything read so far. Idempotent: [MessageDigest.digest] resets the digest, so the answer
     * is computed once and remembered — callers may well ask more than once.
     */
    fun sizeAndHash(): Pair<Long, String> = result ?: (size to digest.digest().toHex()).also { result = it }
}

internal sealed class AttachedDataPlan {
    internal data class Ok(val delta: AttachedDataDelta) : AttachedDataPlan()
    internal data class Rejected(val problems: List<Problem>) : AttachedDataPlan()
}

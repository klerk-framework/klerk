package dev.klerkframework.klerk.storage

import com.google.gson.Gson
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.JobCommit
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.JobRecord
import dev.klerkframework.klerk.migration.MigrationStep
import java.io.InputStream
import kotlin.time.Instant

/** One persisted entry in the audit log: the record of a single committed command against a single model. */
public data class AuditEntry(
    val time: Instant,
    val eventReference: EventReference,
    val reference: Int,
    val actorType: Byte,
    val actorReference: Int?,
    val actorExternalId: Long?,
    val params: String,
    val extra: String?
)

/**
 * A row in the attached-data table.
 *
 * @property owner the id of the owning model, or null while the data is still unclaimed.
 * @property metadata everything about the data except the value itself, including its
 * [dev.klerkframework.klerk.AttachedDataKind]. Immutable, written once on insert.
 * @property expires when an unclaimed row is reaped. Null once the data has been claimed by a model.
 * @property claimedByJob the job that prepared this data and has not finished with it yet, or null. A row with a job
 * claim is never reaped, even though it has no owning model — see [Persistence.deleteExpiredAttachedData].
 */
public data class AttachedDataRow<T>(
    val value: T,
    val owner: Int?,
    val metadata: AttachedDataMetadata,
    val expires: Instant?,
    val claimedByJob: JobId? = null,
)

/**
 * Everything about a value that is only known once it has been written: it is streamed rather than held in memory, so
 * none of this can be measured in advance.
 *
 * @property contentType what the bytes were recognised as, or null when they match no known format.
 */
public data class AttachedDataDigest(
    val size: Long,
    val hash: String,
    val contentType: String?,
)

/**
 * The changes to attached data that a command implies (see [dev.klerkframework.klerk.KlerkAttachedData]). Applied in the
 * same transaction as the models, so a failing command leaves the data untouched.
 *
 * Blobs and strings share one id space, so neither map needs to distinguish between them.
 *
 * @property claimed ids that got an owner, mapped to the owning model id
 * @property deleted ids that no property refers to any more
 */
public data class AttachedDataDelta(
    val claimed: Map<Int, AttachedDataClaim> = emptyMap(),
    val deleted: Set<Int> = emptySet(),
) {
    public fun isEmpty(): Boolean = claimed.isEmpty() && deleted.isEmpty()
}

/**
 * What happens to a value when a model claims it: it gets an owner, it stops expiring, and its visibility is settled.
 *
 * @property visibility declared by the property the value was attached to. Written once, here, and never changed
 * afterwards — which is what makes [AttachedDataVisibility.Public] safe to cache.
 */
public data class AttachedDataClaim(
    val owner: Int,
    val visibility: AttachedDataVisibility,
)

/**
 * Storage backend SPI: implement this to durably store models, the audit log, jobs and attached data. Klerk owns the
 * schema; implementations only need to persist and retrieve the shapes below. Provided implementations are
 * [dev.klerkframework.klerk.storage.SqlPersistence] and [RamStorage]. Wire an instance in via
 * `ConfigBuilder.persistence(...)`.
 */
public interface Persistence {
    /** The schema version currently stored (see [dev.klerkframework.klerk.migration.MigrationStep]). */
    public val currentModelSchemaVersion: Int

    /**
     * Commits everything one command implies: the model delta, its audit-log entry, the attached-data delta, and any
     * jobs the command scheduled. All of it in a single transaction.
     */
    public fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta = AttachedDataDelta(),
        jobs: JobCommit = JobCommit(),
    ): Unit

    /**
     * Commits one step of a job.
     *
     * This is the load-bearing operation of the whole job module: almost every guarantee in the "Jobs" documentation
     * reduces to "these writes happen together or not at all". An implementation that gets it subtly wrong produces
     * duplicated children, double-applied commands or lost checkpoints — none of which show up in ordinary testing.
     *
     * It differs from [store] only in that [delta] may be null, because most steps emit no command at all.
     *
     * **The contract.** `commitJobStep` MUST apply all of the following in a single atomic unit, and the result MUST
     * NOT be observable in a partial state by any reader, or by a subsequent [getAllJobs] after a crash:
     *
     * 1. the model delta produced by the step's command, if any;
     * 2. the attached-data delta, including new job claims from [JobCommit.attachedDataClaimed];
     * 3. the job's new cursor, progress, status, step number and log entries;
     * 4. rows for any children declared in the step's `spawn`, together with the parent's `awaitedChildren` count;
     * 5. for the terminal step of a child, the decrement of the parent's `awaitedChildren` and the child's `result`;
     * 6. the audit-log entry for the command.
     *
     * Point 5 is the one most likely to be missed. Waking a parent must be part of the *child's* own commit, or the
     * last child's completion can be lost and the parent waits forever. Klerk hands you the parent's updated row in
     * [JobCommit.upserted] for exactly this reason — an implementation only has to write the rows it is given, in one
     * transaction, for the contract to hold.
     *
     * **If the underlying store cannot do all of this in one transaction, it MUST NOT be used as a Klerk
     * [Persistence] implementation for jobs.**
     */
    public fun <T : Any, P, C : KlerkContext, V> commitJobStep(
        delta: ProcessingData<out T, C, V>?,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta = AttachedDataDelta(),
        jobs: JobCommit,
    ): Unit

    public fun readAllModels(lambda: (Model<out Any>) -> Unit): Unit
    public fun readAuditLog(
        modelId: Int? = null,
        from: Instant = Instant.DISTANT_PAST,
        until: Instant = Instant.DISTANT_FUTURE
    ): Iterable<AuditEntry>

    public fun modifyEventsInAuditLog(modelId: Int, transformer: (AuditEntry) -> AuditEntry?): Unit
    public fun setConfig(config: Config<*, *>): Unit
    public fun migrate(migrations: List<MigrationStep>): Unit

    /**
     * Inserts an unclaimed value. Must fail if the id is already taken (i.e. insert, never upsert).
     *
     * Strings and blobs are stored identically here — a string arrives as a stream over its UTF-8 bytes — so the only
     * thing distinguishing them is [kind], which must be reported back by [getAttachedData] and
     * [readAllAttachedDataMetadata].
     *
     * The size and hash are not known before the value has been written, since it is streamed rather than held in
     * memory. [digestAfterWrite] provides them, and must therefore be called *after* [value] has been fully consumed
     * and before the insert is committed, so that a row is never visible without them.
     *
     * @param value the bytes to store, or null when they live in an [AttachedBlobStore.External] and this row is only
     * the record of them. A null value never happens for [AttachedDataKind.String].
     * @param claimedByJob the job that prepared this value, if it was prepared inside a job step. Such a row is not
     * reaped for as long as the job lives — see [deleteExpiredAttachedData].
     * @param digestAfterWrite returns the size in bytes and the SHA-256 (lowercase hex) of what was written. Already
     * complete when [value] is null, since the bytes were written before this call.
     */
    public fun insertAttachedData(
        id: Int,
        value: InputStream?,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        createdAt: Instant,
        custom: Map<String, String>,
        expires: Instant,
        claimedByJob: JobId? = null,
        digestAfterWrite: () -> AttachedDataDigest,
    ): Unit

    /**
     * Records what running a value's declared steps did to it: which of them have completed, and — when a step
     * rewrote the bytes — the new value and digest.
     *
     * Only ever called for unclaimed data, which is what makes rewriting safe: nothing can read it, no URL names it
     * and no cache can hold it. Once a model claims a value it never changes again.
     *
     * @param value the new bytes when they belong in the row and a step replaced them, otherwise null. A blob kept in
     * an [AttachedBlobStore.External] is replaced in that store instead, and only the digest is recorded here.
     * @param digestAfterWrite as in [insertAttachedData]: called after [value] has been consumed.
     */
    public fun updateAttachedData(
        id: Int,
        value: InputStream?,
        completedSteps: List<String>,
        digestAfterWrite: () -> AttachedDataDigest,
    ): Unit

    /**
     * One attached-data row, without its value — see [getAttachedValue] for that.
     *
     * The two are separate because a blob's bytes need not be here at all: with an [AttachedBlobStore.External] they
     * live outside the database, and only the caller knows which. `Unit` in place of the value says so in the type,
     * rather than leaving a null for every caller to interpret.
     */
    public fun getAttachedData(id: Int): AttachedDataRow<Unit>?

    /**
     * The bytes stored in the row for [id], or null if there is no such row.
     *
     * Called only for values this row actually holds: strings always, and blobs when the store is
     * [AttachedBlobStore.Database]. A blob kept in an [AttachedBlobStore.External] is read from that store instead,
     * and its row holds no bytes at all.
     */
    public fun getAttachedValue(id: Int): InputStream?

    /**
     * Reads every attached-data row without its value, so that the in-memory structures can be rebuilt at startup.
     */
    public fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>>

    /**
     * Deletes rows whose expiry has passed **and** which no job has claimed.
     *
     * There are two independent claims on a piece of attached data: a model reference (which nulls `expires`) and a
     * job claim (`claimedByJob`). The reaper deletes only when neither holds, so a long-running job's working set is
     * safe for as long as the job lives — including while it is dead-lettered and awaiting a human.
     *
     * @return the ids of the rows that were deleted, so that their bytes can be removed from an external blob store.
     */
    public fun deleteExpiredAttachedData(now: Instant): Set<Int>

    /**
     * Deletes the given rows, whatever their expiry says.
     *
     * Called for a value a step refused: it is unclaimed, nothing may ever attach it, and waiting for its lease to run
     * out would only keep a file somebody has already been told is unacceptable.
     */
    public fun deleteAttachedData(ids: Set<Int>): Unit

    /**
     * Every persisted job, in no particular order. Called once at startup to rebuild the scheduler's state; the job
     * module keeps the rows in memory from then on.
     */
    public fun getAllJobs(): List<JobRecord>

    /**
     * When each cron schedule last fired, keyed by [dev.klerkframework.klerk.job.CronSchedule.id]. Cron *definitions*
     * are configuration rather than rows, but the last-fired time has to be persisted or `CatchUp` cannot survive a
     * restart.
     */
    public fun getCronState(): Map<String, Instant>

    /** Records that the schedule [scheduleId] fired at [firedAt]. */
    public fun setCronFired(scheduleId: String, firedAt: Instant): Unit
}

/**
 * Keeps all data in memory. Should only be used for testing.
 */
public open class RamStorage : Persistence {
    // Set by setConfig with the app's configured Gson (which knows how to serialize Klerk's own data types) once
    // Klerk starts. Left uninitialized when RamStorage is used standalone, e.g. in a test that never calls
    // setConfig -- createAuditEntry falls back to an empty params string in that case.
    private lateinit var gson: Gson
    private val auditLog = mutableSetOf<AuditEntry>()
    private val models = mutableMapOf<Int, Model<Any>>()
    override val currentModelSchemaVersion: Int = 1

    // A null value means the bytes are in an external blob store rather than here.
    private val attachedRows = mutableMapOf<Int, AttachedDataRow<ByteArray?>>()
    private val jobs = mutableMapOf<JobId, JobRecord>()
    private val cronState = mutableMapOf<String, Instant>()

    /**
     * Everything happens under one lock, which is what stands in for a transaction here. It is not a real one — a
     * crash mid-write leaves memory inconsistent — but RamStorage is wiped on every restart anyway, so there is no
     * state for a partial write to corrupt.
     */
    private val lock = Any()

    override fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta,
        jobs: JobCommit,
    ): Unit = synchronized(lock) {
        writeAll(delta, command, context, attachedData, jobs)
    }

    override fun <T : Any, P, C : KlerkContext, V> commitJobStep(
        delta: ProcessingData<out T, C, V>?,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta,
        jobs: JobCommit,
    ): Unit = synchronized(lock) {
        writeAll(delta, command, context, attachedData, jobs)
    }

    private fun <T : Any, P, C : KlerkContext, V> writeAll(
        delta: ProcessingData<out T, C, V>?,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta,
        jobCommit: JobCommit,
    ) {
        applyAttachedDataDelta(attachedData)
        applyJobCommit(jobCommit)
        if (delta == null) {
            return
        }
        if (command != null && context != null) {
            auditLog.add(createAuditEntry(command, delta, context))
        }
        delta.createdModels
            .union(delta.aggregatedModelState.keys)
            .union(delta.transitions).forEach { modelId ->
                val model =
                    requireNotNull(delta.aggregatedModelState[modelId]) { "Could not find $modelId in modifiedModels" }
                @Suppress("UNCHECKED_CAST")
                models[model.id.value] = model as Model<Any>
            }

        delta.deletedModels.forEach { models.remove(it.value) }
    }

    private fun applyJobCommit(commit: JobCommit) {
        commit.upserted.forEach { jobs[it.id] = it }
        commit.deleted.forEach { jobs.remove(it) }
        commit.attachedDataClaimed.forEach { (dataId, jobId) ->
            attachedRows[dataId]?.let { attachedRows[dataId] = it.copy(claimedByJob = jobId) }
        }
        commit.attachedDataReleased.forEach { dataId ->
            attachedRows[dataId]?.let { attachedRows[dataId] = it.copy(claimedByJob = null) }
        }
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit): Unit {
        return models.values.forEach { lambda(it) }
    }

    override fun readAuditLog(modelId: Int?, from: Instant, until: Instant): Iterable<AuditEntry> {
        return auditLog
            .filter {
                return@filter if (modelId == null) {
                    true
                } else {
                    modelId == it.reference
                }
            }
            .filter { it.time > from || it.time == from }
            .filter { it.time < until || it.time == from }
            .sortedBy { it.time }
    }

    override fun modifyEventsInAuditLog(modelId: Int, transformer: (AuditEntry) -> AuditEntry?) {
        readAuditLog(modelId).toList().forEach {
            auditLog.remove(it)
            val new = transformer(it)
            if (new != null) {
                auditLog.add(new)
            }
        }
    }

    override fun setConfig(config: Config<*, *>) {
        this.gson = config.gson
    }

    override fun migrate(migrations: List<MigrationStep>) {
        logger.debug { "Skipping migration since RamStorage is always empty on startup" }
    }

    private fun applyAttachedDataDelta(attachedData: AttachedDataDelta) {
        attachedData.claimed.forEach { (id, claim) ->
            val row = requireNotNull(attachedRows[id]) { "Could not find attached data with id $id" }
            attachedRows[id] = row.copy(
                owner = claim.owner,
                expires = null,
                metadata = row.metadata.copy(visibility = claim.visibility),
            )
        }
        attachedData.deleted.forEach { attachedRows.remove(it) }
    }

    override fun insertAttachedData(
        id: Int,
        value: InputStream?,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        createdAt: Instant,
        custom: Map<String, String>,
        expires: Instant,
        claimedByJob: JobId?,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        require(!attachedRows.containsKey(id)) { "There is already attached data with id $id" }
        val bytes = value?.readAllBytes()
        val digest = digestAfterWrite()
        attachedRows[id] = AttachedDataRow(
            value = bytes,
            owner = null,
            metadata = AttachedDataMetadata(
                kind, visibility, createdAt, digest.size, digest.hash, custom, digest.contentType
            ),
            expires = expires,
            claimedByJob = claimedByJob,
        )
    }

    override fun updateAttachedData(
        id: Int,
        value: InputStream?,
        completedSteps: List<String>,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        val row = requireNotNull(attachedRows[id]) { "There is no attached data with id $id" }
        val bytes = value?.readAllBytes()
        val digest = digestAfterWrite()
        attachedRows[id] = row.copy(
            value = bytes ?: row.value,
            metadata = row.metadata.copy(
                size = digest.size,
                hash = digest.hash,
                contentType = digest.contentType,
                completedSteps = completedSteps,
            ),
        )
    }

    override fun getAttachedData(id: Int): AttachedDataRow<Unit>? =
        attachedRows[id]?.let {
            AttachedDataRow(Unit, it.owner, it.metadata, it.expires, it.claimedByJob)
        }

    override fun getAttachedValue(id: Int): InputStream? = attachedRows[id]?.value?.inputStream()

    override fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>> =
        attachedRows.mapValues {
            AttachedDataRow(Unit, it.value.owner, it.value.metadata, it.value.expires, it.value.claimedByJob)
        }

    override fun deleteExpiredAttachedData(now: Instant): Set<Int> {
        val expired = attachedRows.filterValues { row ->
            row.claimedByJob == null && row.expires?.let { it < now } ?: false
        }.keys.toSet()
        expired.forEach { attachedRows.remove(it) }
        return expired
    }

    override fun deleteAttachedData(ids: Set<Int>) {
        ids.forEach { attachedRows.remove(it) }
    }

    override fun getAllJobs(): List<JobRecord> = synchronized(lock) { jobs.values.toList() }

    override fun getCronState(): Map<String, Instant> = synchronized(lock) { cronState.toMap() }

    override fun setCronFired(scheduleId: String, firedAt: Instant): Unit = synchronized(lock) {
        cronState[scheduleId] = firedAt
    }

    public fun <T : Any, P, C : KlerkContext, V> createAuditEntry(
        command: Command<T, P>,
        result: ProcessingData<out T, C, V>,
        context: C
    ): AuditEntry {
        val reference = command.model?.value
            ?: result.createdModels.single { true }.value
        return AuditEntry(
            context.time,
            command.event.id,
            reference,
            context.actor.type.toByte(),
            context.actor.id?.value,
            context.actor.externalId,
            if (::gson.isInitialized) gson.toJson(command.params) else "{}",    // TODO
            extra = context.auditExtra
        )
    }

}

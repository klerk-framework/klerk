package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.ActorType
import dev.klerkframework.klerk.AttachedDataKind
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.storage.spi.AttachedDataDelta
import dev.klerkframework.klerk.storage.spi.AttachedDataDigest
import dev.klerkframework.klerk.storage.spi.AttachedDataRow
import dev.klerkframework.klerk.storage.spi.JobCommit
import dev.klerkframework.klerk.storage.spi.JobRecord
import java.io.InputStream
import kotlin.time.Instant

/**
 * One persisted entry in the event log: the record of a single committed command against a single model.
 *
 * @property sequenceNumber identifies the entry and orders the log. Assigned by Klerk, one per committed command, in
 * commit order. Unlike [time], which is whatever the command's context said, it is monotonic and unique.
 * @property time the [KlerkContext.time] of the command, i.e. when the application considered it to happen.
 * @property model the id of the model the command acted on.
 * @property params the command's parameters as a JSON object keyed by parameter name, with each
 * [dev.klerkframework.klerk.datatypes.DataContainer] written as its value and each [ModelID] as a number. `null` for an
 * event without parameters.
 * @property extra the command's [KlerkContext.eventLogExtra].
 */
public data class EventLogEntry(
    val sequenceNumber: Long,
    val time: Instant,
    val eventReference: EventReference,
    val model: ModelID<out Any>,
    val actorType: ActorType,
    val actorReference: Int?,
    val actorExternalId: Long?,
    val params: String,
    val extra: String?,
)

/**
 * Everything one commit writes, as storage sees it: the model delta, the event-log entry, the attached-data delta and
 * the job rows. A [Persistence] implementation writes exactly what it is given here, in one transaction.
 *
 * @property createdModels models that did not exist before, to be inserted.
 * @property updatedModels models that existed before, to be replaced. Never overlaps [createdModels].
 * @property deletedModels ids of the models to remove.
 * @property eventLogEntry the entry to append, or null when the commit came from no command (e.g. a job checkpoint).
 */
public data class CommitBatch(
    val createdModels: List<Model<out Any>> = emptyList(),
    val updatedModels: List<Model<out Any>> = emptyList(),
    val deletedModels: List<ModelID<out Any>> = emptyList(),
    val eventLogEntry: EventLogEntry? = null,
    val attachedData: AttachedDataDelta = AttachedDataDelta(),
    val jobs: JobCommit = JobCommit(),
)

/**
 * Storage backend SPI: implement this to durably store models, the event log, jobs and attached data. Klerk owns the
 * schema; implementations only need to persist and retrieve the shapes below. Provided implementations are
 * [dev.klerkframework.klerk.storage.SqlPersistence] and [RamStorage]. Wire an instance in via
 * [dev.klerkframework.klerk.KlerkSettings.persistence].
 */
public interface Persistence {
    /** The schema version currently stored (see [dev.klerkframework.klerk.migration.MigrationStep]). */
    public val currentModelSchemaVersion: Int

    /**
     * Commits everything one command implies: the model delta, its event-log entry, the attached-data delta, and any
     * jobs the command scheduled. All of it in a single transaction.
     */
    public fun store(batch: CommitBatch)

    /**
     * Commits one step of a job.
     *
     * This is the load-bearing operation of the whole job module: almost every guarantee in the "Jobs" documentation
     * reduces to "these writes happen together or not at all". An implementation that gets it subtly wrong produces
     * duplicated children, double-applied commands or lost checkpoints — none of which show up in ordinary testing.
     *
     * It differs from [store] only in that most steps emit no command at all, so the batch then carries neither a
     * model delta nor an event-log entry.
     *
     * **The contract.** `commitJobStep` MUST apply all of the following in a single atomic unit, and the result MUST
     * NOT be observable in a partial state by any reader, or by a subsequent [allJobs] after a crash:
     *
     * 1. the model delta produced by the step's command, if any;
     * 2. the attached-data delta, including new job claims from [JobCommit.attachedDataClaimed];
     * 3. the job's new cursor, progress, status, step number and log entries;
     * 4. rows for any children declared in the step's `spawn`;
     * 5. the event-log entry for the command.
     *
     * An implementation only has to write the rows it is given, in one transaction, for the contract to hold.
     *
     * **If the underlying store cannot do all of this in one transaction, it MUST NOT be used as a Klerk
     * [Persistence] implementation for jobs.**
     */
    public fun commitJobStep(batch: CommitBatch)

    /** Reads every stored model. Used once at startup to populate the model cache. */
    public fun readAllModels(lambda: (Model<out Any>) -> Unit)

    /**
     * Reads the model whose [ModelID.value] is [id], or null if there is none.
     *
     * Called on the read path when a model is not resident in memory, so it must be a keyed lookup rather than a scan,
     * and it must be safe to call concurrently from several threads.
     */
    public fun readModel(id: Int): Model<out Any>?

    /**
     * Reads event-log entries, ordered by [EventLogEntry.sequenceNumber], oldest first.
     *
     * If [modelId] is given, only entries for that model are included. [after] and [before] limit the entries to those
     * whose [EventLogEntry.time] is in that (inclusive) range. Only entries at or below [upToSequenceNumber] are
     * included: Klerk uses it to hide a commit that is written to storage but not yet visible to readers, so an
     * implementation must honour it.
     */
    public fun readEventLog(
        modelId: Int? = null,
        after: Instant = Instant.DISTANT_PAST,
        before: Instant = Instant.DISTANT_FUTURE,
        upToSequenceNumber: Long = Long.MAX_VALUE,
    ): Iterable<EventLogEntry>

    /** The event-log entry with [sequenceNumber], or null if there is none. */
    public fun readEventLogEntry(sequenceNumber: Long): EventLogEntry?

    /** The highest [EventLogEntry.sequenceNumber] in storage, or 0 if the log is empty. Read once at startup. */
    public fun lastEventLogSequenceNumber(): Long

    /**
     * Rewrites every event-log entry of [modelId] through [transformer], deleting the ones it maps to null.
     *
     * Used to honour `eraseEventLogAfterModelDeletion`.
     */
    public fun modifyEventLog(modelId: Int, transformer: (EventLogEntry) -> EventLogEntry?)

    /**
     * Hands the implementation the specification, at startup and before anything is read. An implementation that has
     * to know the model classes (e.g. to migrate) keeps it; one that does not may ignore it.
     */
    public fun setSpecification(specification: Specification<*, *>)

    /**
     * Applies [migrations] to the stored models, in the order given, and records the schema version they lead to in
     * [currentModelSchemaVersion]. Called at startup with the steps not yet applied, and never with an empty list.
     */
    public fun migrate(migrations: List<MigrationStep>)

    /**
     * Inserts an unclaimed value. Must fail if the id is already taken (i.e. insert, never upsert).
     *
     * Strings and blobs are stored identically here — a string arrives as a stream over its UTF-8 bytes — so the only
     * thing distinguishing them is [kind], which must be reported back by [getAttachedData] and
     * [readAllAttachedDataMetadata].
     *
     * The size and hash are not known before the value has been written, since it is streamed rather than held in
     * memory. [digestAfterWrite] provides them, and must therefore be called *after* [value] has been fully consumed
     * and before the insert is committed, so that a row is never visible without them. It returns the size in bytes and
     * the SHA-256 (lowercase hex) of what was written, and is already complete when [value] is null, since the bytes
     * were written before this call.
     *
     * [value] is null when the bytes live in an [AttachedBlobStore.External] and this row is only the record of them. A
     * null value never happens for [AttachedDataKind.String].
     *
     * [claimedByJob] is the job that prepared this value, if it was prepared inside a job step. Such a row is not
     * reaped for as long as the job lives — see [deleteExpiredAttachedData]. [preparedFor] is returned as
     * [AttachedDataMetadata.preparedFor].
     */
    public fun insertAttachedData(
        id: Int,
        value: InputStream?,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        createdAt: Instant,
        custom: Map<String, String>,
        preparedFor: String?,
        expires: Instant,
        claimedByJob: JobID? = null,
        digestAfterWrite: () -> AttachedDataDigest,
    )

    /**
     * Records what running a value's declared steps did to it: which of them have completed, and — when a step
     * rewrote the bytes — the new value and digest.
     *
     * Only ever called for unclaimed data, which is what makes rewriting safe: nothing can read it, no URL names it
     * and no cache can hold it. Once a model claims a value it never changes again.
     *
     * [value] holds the new bytes when they belong in the row and a step replaced them, otherwise null. A blob kept in
     * an [AttachedBlobStore.External] is replaced in that store instead, and only the digest is recorded here.
     * [digestAfterWrite] is called after [value] has been consumed, as in [insertAttachedData].
     */
    public fun updateAttachedData(
        id: Int,
        value: InputStream?,
        completedSteps: List<String>,
        digestAfterWrite: () -> AttachedDataDigest,
    )

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
     * Returns the ids of the rows that were deleted, so that their bytes can be removed from an external blob store.
     */
    public fun deleteExpiredAttachedData(now: Instant): Set<Int>

    /**
     * Deletes the given rows, whatever their expiry says.
     *
     * Called for a value a step refused: it is unclaimed, nothing may ever attach it, and waiting for its lease to run
     * out would only keep a file somebody has already been told is unacceptable.
     */
    public fun deleteAttachedData(ids: Set<Int>)

    /**
     * Every persisted job, in no particular order. Called once at startup to rebuild the scheduler's state; the job
     * module keeps the rows in memory from then on.
     */
    public fun allJobs(): List<JobRecord>

    /**
     * When each cron schedule last fired, keyed by [dev.klerkframework.klerk.job.CronSchedule.id]. Cron *definitions*
     * are configuration rather than rows, but the last-fired time has to be persisted or `CatchUp` cannot survive a
     * restart.
     */
    public fun cronState(): Map<String, Instant>

    /** Records that the schedule [scheduleId] fired at [firedAt]. */
    public fun setCronFired(scheduleId: String, firedAt: Instant)
}

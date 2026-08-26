package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import kotlin.time.Instant

/**
 * Everything Klerk persists about one job instance. This is the shape a [dev.klerkframework.klerk.storage.Persistence]
 * implementation has to store and hand back; the job module owns all of the logic that decides what goes in it.
 *
 * @property cursor the job's state, encoded by its [JobType]. Opaque to storage.
 * @property stepNumber how many steps have committed. Also the number of the step about to run.
 * @property attempt how many times the current step has already been attempted.
 * @property readyAt the earliest time the job may be dispatched: the `scheduleAt` while [JobStatus.Scheduled], the
 * end of the backoff while [JobStatus.Backoff], and the time it became ready otherwise. Null while the job is not
 * dispatchable at all (running, waiting for children, terminal).
 * @property rootId the top of this job's spawn tree — itself, for a job nobody spawned. Together with [depth] it makes
 * the `maxDescendants`/`maxDepth` budgets a counter update rather than a tree walk.
 * @property descendants how many jobs the tree rooted here has spawned in total. Only meaningful on the root record.
 * @property failedAtCursor the cursor as it was when the job died, preserved read-only for an end-of-life hook.
 * @property hookCursor the hook's own cursor, checkpointed separately so unwinding never destroys [failedAtCursor].
 * @property noProgressStreak how many consecutive steps changed neither the cursor nor the progress. Three means
 * livelock, and the job is aborted.
 * @property cronScheduleId the [CronSchedule.id] this instance was fired by, or null if it was not.
 */
public data class JobRecord(
    val id: JobId,
    val name: JobName,
    val cursor: String,
    val status: JobStatus,
    val priority: JobPriority,
    val agent: JobAgent,
    val ownerActorType: Int,
    val ownerActorId: Int?,
    val ownerActorExternalId: Long?,
    val stepNumber: Int,
    val attempt: Int,
    val created: Instant,
    val readyAt: Instant?,
    val firstAttemptStarted: Instant?,
    val lastAttemptStarted: Instant?,
    val lastAttemptFinished: Instant?,
    val progressCompleted: Int?,
    val progressTotal: Int?,
    val progressMessage: String?,
    val log: List<JobLogEntry>,
    val parentId: JobId?,
    val rootId: JobId,
    val depth: Int,
    val descendants: Int,
    val result: String?,
    val failedAtCursor: String?,
    val hookCursor: String?,
    val hookKind: JobHookKind?,
    val cancellationRequested: Boolean,
    val reason: String?,
    val noProgressStreak: Int,
    val cronScheduleId: String?,
) {

    /** The progress as the application declared it, or null if the job has not reported any. */
    public val progress: JobProgress?
        get() = progressCompleted?.let { JobProgress(it, progressTotal, progressMessage) }

    internal fun withProgress(progress: JobProgress?): JobRecord =
        if (progress == null) this else copy(
            progressCompleted = progress.completed,
            progressTotal = progress.total,
            progressMessage = progress.message,
        )

    /** Appends log entries, keeping only the most recent [JobLogEntry.MAX_ENTRIES]. */
    internal fun withLog(entries: List<JobLogEntry>): JobRecord =
        if (entries.isEmpty()) this else copy(log = (log + entries).takeLast(JobLogEntry.MAX_ENTRIES))

    /** The cursor the next step should be given: the hook's own while unwinding, the job's own otherwise. */
    internal val activeCursor: String get() = if (hookKind == null) cursor else (hookCursor ?: cursor)

    internal fun toJobInfo(): JobInfo = JobInfo(
        id = id,
        name = name,
        step = stepNumber,
        attempt = attempt,
        created = created,
        priority = priority,
        parent = parentId,
        root = rootId,
        depth = depth,
        status = status,
        progress = progress,
        hook = hookKind,
        cancellationRequested = cancellationRequested,
        reason = reason,
        log = log,
        ownerActorId = ownerActorId?.let { ModelID(it) },
        ownerActorType = ownerActorType,
        ownerActorExternalId = ownerActorExternalId,
    )

    internal fun toChildOutcome(): ChildOutcome =
        ChildOutcome(id = id, name = name, status = status, result = result, reason = reason)
}

/**
 * A job that a command is in the middle of scheduling: it has an id, but nothing is committed until the command is.
 *
 * If the command fails, no job is scheduled — which is why an id is allocated during processing rather than after.
 */
public class PendingJob<C : KlerkContext, V> internal constructor(
    public val id: JobId,
    internal val scheduled: DeclaredJob<C, V>,
) {
    public val name: JobName get() = scheduled.name

    override fun toString(): String = "PendingJob($id, ${name.value})"
}

/**
 * The job-module part of a transaction: rows to write, rows to remove, and the attached-data claims that go with them.
 *
 * Every field here must be applied in the *same* transaction as the model delta it accompanies — see
 * [dev.klerkframework.klerk.storage.Persistence.commitJobStep].
 *
 * @property upserted job rows to insert or replace. One commit may touch several: the stepping job itself, any
 * children it spawned. A finishing child writes only its own row: whether its parent may now run is derived from the
 * children when the question is asked, not tracked on the parent.
 * @property deleted job rows to remove, e.g. an expired dead letter.
 * @property attachedDataClaimed attached-data ids that this job now claims, so that the orphan reaper leaves them
 * alone for as long as the job lives.
 * @property attachedDataReleased attached-data ids whose job claim is being dropped, because the claiming job's row is
 * going away. Releasing a claim never deletes data that a committed command attached to a live model.
 */
public data class JobCommit(
    val upserted: List<JobRecord> = emptyList(),
    val deleted: Set<JobId> = emptySet(),
    val attachedDataClaimed: Map<Int, JobId> = emptyMap(),
    val attachedDataReleased: Set<Int> = emptySet(),
) {
    public fun isEmpty(): Boolean =
        upserted.isEmpty() && deleted.isEmpty() && attachedDataClaimed.isEmpty() && attachedDataReleased.isEmpty()
}

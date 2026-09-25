package dev.klerkframework.klerk.storage.spi

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.ActorType
import dev.klerkframework.klerk.ModelReferenceIdentity
import dev.klerkframework.klerk.job.ChildOutcome
import dev.klerkframework.klerk.job.JobAgent
import dev.klerkframework.klerk.job.JobHookKind
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.job.JobLogEntry
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobPriority
import dev.klerkframework.klerk.job.JobProgress
import dev.klerkframework.klerk.job.JobStatus
import kotlin.time.Instant

/**
 * Everything Klerk persists about one job instance. This is the shape a [dev.klerkframework.klerk.storage.Persistence]
 * implementation has to store and hand back; the job module owns all of the logic that decides what goes in it.
 *
 * @property ownerActorName the plugin name of an owner that is a [dev.klerkframework.klerk.PluginIdentity], else null.
 * @property cursor the job's state, encoded by its [dev.klerkframework.klerk.job.JobType]. Opaque to storage.
 * @property step how many steps have committed. Also the number of the step about to run.
 * @property attempt how many times the current step has already been attempted.
 * @property readyAt the earliest time the job may be dispatched: the `scheduleAt` while [JobStatus.Scheduled], the
 * end of the backoff while [JobStatus.Backoff], and the time it became ready otherwise. Null while the job is not
 * dispatchable at all (running, waiting for children, terminal).
 * @property root the top of this job's spawn tree — itself, for a job nobody spawned. Together with [depth] it is
 * what the `maxDescendants`/`maxDepth` budgets are measured against.
 * @property failedAtCursor the cursor as it was when the job died, preserved read-only for an end-of-life hook.
 * @property hookCursor the hook's own cursor, checkpointed separately so unwinding never destroys [failedAtCursor].
 * @property noProgressStreak how many consecutive steps changed neither the cursor nor the progress. Three means
 * livelock, and the job is aborted.
 * @property cronScheduleId the [dev.klerkframework.klerk.job.CronSchedule.id] this instance was fired by, or null if it
 * was not.
 */
public data class JobRecord(
    val id: JobID,
    val name: JobName,
    val cursor: String,
    val status: JobStatus,
    val priority: JobPriority,
    val agent: JobAgent,
    val ownerActorType: ActorType,
    val ownerActorId: Int?,
    val ownerActorExternalId: Long?,
    val ownerActorName: String?,
    val step: Int,
    val attempt: Int,
    val createdAt: Instant,
    val readyAt: Instant?,
    val firstAttemptStarted: Instant?,
    val lastAttemptStarted: Instant?,
    val lastAttemptFinished: Instant?,
    val progressCompleted: Int?,
    val progressTotal: Int?,
    val progressMessage: String?,
    val log: List<JobLogEntry>,
    val parent: JobID?,
    val root: JobID,
    val depth: Int,
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

    internal fun withProgress(progress: JobProgress?): JobRecord = if (progress == null) {
        this
    } else {
        copy(
            progressCompleted = progress.completed,
            progressTotal = progress.total,
            progressMessage = progress.message,
        )
    }

    /** Appends log entries, keeping only the most recent [JobLogEntry.MAX_ENTRIES]. */
    internal fun withLog(entries: List<JobLogEntry>): JobRecord =
        if (entries.isEmpty()) this else copy(log = (log + entries).takeLast(JobLogEntry.MAX_ENTRIES))

    /** The cursor the next step should be given: the hook's own while unwinding, the job's own otherwise. */
    internal val activeCursor: String get() = if (hookKind == null) cursor else (hookCursor ?: cursor)

    internal fun toJobInfo(): JobInfo = JobInfo(
        id = id,
        name = name,
        step = step,
        attempt = attempt,
        createdAt = createdAt,
        priority = priority,
        agent = agent,
        parent = parent,
        root = root,
        depth = depth,
        status = status,
        progress = progress,
        hook = hookKind,
        cancellationRequested = cancellationRequested,
        reason = reason,
        log = log,
        owner = rebuildOwner(),
    )

    /**
     * The scheduling actor, as far as storage remembers it. Only the id survives, so an actor that was a loaded model
     * comes back as a [ModelReferenceIdentity].
     */
    internal fun rebuildOwner(): ActorIdentity =
        StoredActor(ownerActorType, ownerActorId, ownerActorExternalId, ownerActorName).toIdentity()

    internal fun toChildOutcome(): ChildOutcome =
        ChildOutcome(id = id, name = name, status = status, result = result, reason = reason)
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
    val deleted: Set<JobID> = emptySet(),
    val attachedDataClaimed: Map<Int, JobID> = emptyMap(),
    val attachedDataReleased: Set<Int> = emptySet(),
) {
    /** True if nothing changes. */
    public fun isEmpty(): Boolean =
        upserted.isEmpty() && deleted.isEmpty() && attachedDataClaimed.isEmpty() && attachedDataReleased.isEmpty()
}

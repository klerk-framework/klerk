package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkInstantSerializer
import dev.klerkframework.klerk.ModelID
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The stable, application-chosen identity of a job type.
 *
 * This — not a class or method name — is what is persisted with every job instance, so renaming the Kotlin object that
 * implements a [JobType] is safe while changing its [JobName] is not: after a restart, jobs whose name no longer
 * resolves are handled according to [UnloadableJobPolicy].
 *
 * @throws IllegalArgumentException if the name is blank, longer than 100 characters, or contains whitespace.
 */
@Serializable
@JvmInline
public value class JobName(public val value: String) {
    init {
        require(value.isNotBlank()) { "A JobName cannot be blank" }
        require(value.length <= MAX_LENGTH) { "A JobName cannot be longer than $MAX_LENGTH characters: '$value'" }
        require(value.none { it.isWhitespace() }) { "A JobName cannot contain whitespace: '$value'" }
    }

    override fun toString(): String = value

    public companion object {
        internal const val MAX_LENGTH: Int = 100
    }
}

/**
 * Identifies one job instance. Allocated by Klerk when the job is scheduled and stable for the job's whole life,
 * including across restarts and after it has become terminal.
 */
@Serializable
@JvmInline
public value class JobId(public val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * The queueing class of a job, which decides how long it may sit unstarted before Klerk starts shedding new work of
 * that class (see `AdmissionPolicy.delayBudget`).
 *
 * The classes are ordered: [Interactive] is dispatched before [High], and so on.
 */
public enum class JobPriority {
    /** The user is watching. Default budget 5 seconds. */
    Interactive,

    /** Should happen promptly. Default budget 1 minute. */
    High,

    /** The default. Budget 10 minutes. */
    Normal,

    /** Imports, backfills, cleanup. Budget 2 hours. */
    Bulk,
}

/**
 * Where a job is in its lifecycle.
 *
 * Only [isTerminal] statuses are final; everything else will change on its own eventually. Note that a job unwinding
 * through an end-of-life hook is *not* terminal — it reaches [DeadLettered], [Cancelled] or [CompensationFailed] only
 * once the hook has finished.
 */
@Serializable
public enum class JobStatus(public val isTerminal: Boolean) {
    /** Waiting for its `scheduleAt` to arrive. */
    Scheduled(false),

    /** Queued, waiting for a dispatch slot. */
    Ready(false),

    /** A step is executing right now. */
    Running(false),

    /** Awaiting spawned children. Holds no dispatch slot and is exempt from the livelock guard. */
    Waiting(false),

    /** The last attempt failed; waiting to retry. */
    Backoff(false),

    /** Cancellation has been requested and unwinding is in progress. */
    Cancelling(false),

    /** Done. */
    Succeeded(true),

    /** Cancelled at a step boundary; `onCancelled` ran. */
    Cancelled(true),

    /** Gave up; `onDeadLettered` ran, or was deliberately skipped. */
    DeadLettered(true),

    /** Dead *and* the end-of-life hook could not complete. The queue a human has to look at. */
    CompensationFailed(true),
    ;
}

/** Which end-of-life hook a job is currently unwinding through, or null if it is running its ordinary steps. */
public enum class JobHookKind {
    OnCancelled,
    OnDeadLettered,
}

/**
 * Whose authority a job's commands are applied with.
 *
 * Declared on the [JobType], i.e. in trusted configuration code — choosing [System] is a deliberate, privileged act.
 */
public enum class JobAgent {
    /** Full authority. Commands emitted by the job bypass no rules, but the actor is `SystemIdentity`. */
    System,

    /**
     * The actor that scheduled the job. If that actor loses permission mid-job, subsequent commands simply fail and
     * the step sees it in `previousResult`.
     */
    Scheduler,
}

/**
 * How far a job has got, structured so that a UI can render a progress bar without parsing strings.
 *
 * Stored with the cursor, in the same transaction, and visible through [dev.klerkframework.klerk.JobManager.get]
 * subject to the job authorization rules.
 *
 * @property completed how many units of work are done.
 * @property total how many there are in total, or null when the job doesn't know yet.
 * @property message optional human-readable detail. This is a plain string rather than something translatable because
 * progress is persisted: a translation callback cannot survive a restart. Format it in the language of the actor that
 * scheduled the job, or leave it out and let the UI derive text from [completed]/[total].
 */
public data class JobProgress(
    val completed: Int,
    val total: Int? = null,
    val message: String? = null,
) {
    init {
        require(completed >= 0) { "JobProgress.completed cannot be negative" }
        require(total == null || total >= 0) { "JobProgress.total cannot be negative" }
    }
}

/** How serious a [JobLogEntry] is. */
@Serializable
public enum class JobLogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

/**
 * One diagnostic line attached to a job.
 *
 * The log is for diagnostics only — use [JobProgress] to report how far the job has got. Entries are appended in the
 * same transaction as the step that produced them, and are capped at [MAX_ENTRIES] per job (oldest dropped first) so
 * that a long-running job cannot grow without bound.
 */
@Serializable
public data class JobLogEntry(
    @Serializable(with = KlerkInstantSerializer::class) val time: Instant,
    val level: JobLogLevel,
    val message: String,
) {
    public companion object {
        public const val MAX_ENTRIES: Int = 200
    }
}

/**
 * What a child job reported when it finished, delivered to the parent's next step after an
 * `awaitSpawned` yield.
 *
 * @property result whatever the child passed to `JobResult.Success(result = ...)`, or null if it passed none or did
 * not succeed. Stored as a string; read it with [resultAs] when the child encoded it with [encodeJobResult].
 */
@Serializable
public data class ChildOutcome(
    val id: JobId,
    val name: JobName,
    val status: JobStatus,
    val result: String? = null,
    val reason: String? = null,
) {
    /** True if the child reached [JobStatus.Succeeded]. */
    public val succeeded: Boolean get() = status == JobStatus.Succeeded
}

/**
 * What a step is told about the job it belongs to.
 *
 * @property step the 0-based number of the step about to run. It is also the number of steps that have already
 * committed, so it survives restarts exactly.
 * @property attempt how many times *this* step has been attempted, starting at 0. Non-zero means the previous
 * attempt returned `Fail` or threw.
 * @property depth 0 for a job scheduled by a command or by `klerk.jobs.schedule`, one more than the parent's depth
 * for a spawned child.
 */
public data class JobInfo(
    val id: JobId,
    val name: JobName,
    val step: Int,
    val attempt: Int,
    val createdAt: Instant,
    val priority: JobPriority,
    val parent: JobId?,
    val root: JobId,
    val depth: Int,
    val status: JobStatus,
    val progress: JobProgress?,
    val hook: JobHookKind? = null,
    val cancellationRequested: Boolean = false,
    val reason: String? = null,
    val log: List<JobLogEntry> = emptyList(),
    /**
     * The actor that scheduled the job, so that authorization rules can answer "is this the caller's own job?".
     * Rebuilt from what was persisted, so an actor that was a loaded model comes back as a
     * [dev.klerkframework.klerk.ModelReferenceIdentity].
     */
    val owner: ActorIdentity,
)

/**
 * A job that has been declared but not yet given an id.
 *
 * Hand it to `klerk.jobs.schedule(...)`, return it from a state machine's `job(...)` executable, or declare it in a
 * step's `JobResult.Yield(spawn = ...)`. Nothing happens until one of those commits.
 */
public class DeclaredJob<C : KlerkContext, V> internal constructor(
    internal val type: JobType<*, C, V>,
    internal val encodedCursor: String,
    public val scheduleAt: Instant?,
    public val priority: JobPriority?,
) {
    /** The type of job that will run. */
    public val name: JobName get() = type.name

    override fun toString(): String = "DeclaredJob(${name.value})"
}

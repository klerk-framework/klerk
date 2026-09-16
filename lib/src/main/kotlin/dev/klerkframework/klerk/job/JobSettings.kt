package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.misc.envDuration
import dev.klerkframework.klerk.misc.envEnum
import dev.klerkframework.klerk.misc.envInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * What to do at startup with a persisted job that cannot be loaded.
 *
 * Two things can go wrong, and one setting covers both because the operator's answer is the same for each: a job whose
 * [JobName] is no longer registered (you deleted or renamed a job type while instances were pending), and a cursor
 * that no longer deserializes (you changed the cursor type while instances were checkpointed against the old shape).
 */
public enum class UnloadableJobPolicy {
    /**
     * Refuse to start. This is the default to prevent discarding jobs.
     *
     * It is recommended to control this from e.g. an environment variable so that changing it does not require a
     * rebuild.
     */
    FailToStart,

    /**
     * Dead-letter the job and carry on starting.
     *
     * Such a job runs **no** end-of-life hook: the thing that failed is deserializing the very cursor the hook would
     * need. This is the one dead-letter path with no compensation.
     */
    DeadLetter,
}

/** Whether Klerk runs jobs on its own, or only when a test tells it to. */
public enum class JobExecution {
    /** A background dispatcher runs jobs as they become ready. The normal setting. */
    Automatic,

    /**
     * Nothing runs until [dev.klerkframework.klerk.JobManager.step] or
     * [dev.klerkframework.klerk.JobManager.runUntilIdle] is called. For tests: no background thread, no sleeping, and
     * a deterministic order.
     */
    Manual,
}

/**
 * How the job module is operated on this instance. Part of [dev.klerkframework.klerk.KlerkSettings], because a bigger
 * node may legitimately run more steps at once than a laptop, and a test wants nothing running behind its back.
 */
public data class JobSettings(

    /**
     * What to do at startup with a job whose name is no longer registered, or whose cursor no longer deserializes.
     */
    val onUnloadableJob: UnloadableJobPolicy = UnloadableJobPolicy.FailToStart,

    /** Whether jobs run on their own, or only when a test drives them. */
    val execution: JobExecution = JobExecution.Automatic,

    /**
     * How long a succeeded job is kept before it is deleted. Defaults to 30 days.
     *
     * A succeeded job has already released its attached-data claims, so this setting is purely about bounding
     * storage and event log history, not about freeing resources.
     */
    val succeededRetention: Duration = DEFAULT_TERMINAL_RETENTION,

    /**
     * How long a cancelled job is kept before it is deleted. Defaults to 30 days.
     *
     * A cancelled job keeps its attached-data claims until it is deleted, so this setting also bounds how long that
     * data can leak.
     */
    val cancelledRetention: Duration = DEFAULT_TERMINAL_RETENTION,

    /**
     * How long a dead-lettered (or [JobStatus.CompensationFailed]) job is kept before it is deleted. Defaults to 30
     * days.
     *
     * A dead-lettered job keeps its attached-data claims until it is deleted, so this setting also bounds how long
     * that data can leak. 30 days is meant to give a human time to notice and act — resume it with
     * [dev.klerkframework.klerk.JobManager.resume] — before it is discarded.
     */
    val deadLetterRetention: Duration = DEFAULT_TERMINAL_RETENTION,

    /**
     * The hard cap on non-terminal jobs, enforced before the admission policy runs so that a policy which always
     * returns `Allow` still cannot exhaust memory.
     */
    val hardQueueLimit: Int = DEFAULT_HARD_QUEUE_LIMIT,

    /** How many job steps may run at the same time. Commits are serialized regardless. */
    val maxParallelSteps: Int = DEFAULT_MAX_PARALLEL_STEPS,

    /** How often the dispatcher looks for work that has become ready. Ignored in [JobExecution.Manual]. */
    val pollInterval: Duration = 1.seconds,

    /** The base of the exponential retry backoff: attempt *n* waits `base * 3^(n-1)`. */
    val backoffBase: Duration = 3.seconds,
) {
    init {
        require(hardQueueLimit > 0) { "hardQueueLimit must be positive" }
        require(maxParallelSteps > 0) { "maxParallelSteps must be positive" }
        require(pollInterval > Duration.ZERO) { "pollInterval must be positive" }
        require(backoffBase > Duration.ZERO) { "backoffBase must be positive" }
        require(succeededRetention > Duration.ZERO) { "succeededRetention must be positive" }
        require(cancelledRetention > Duration.ZERO) { "cancelledRetention must be positive" }
        require(deadLetterRetention > Duration.ZERO) { "deadLetterRetention must be positive" }
    }

    public companion object {
        internal const val DEFAULT_HARD_QUEUE_LIMIT: Int = 100_000
        internal const val DEFAULT_MAX_PARALLEL_STEPS: Int = 4

        /**
         * How long a terminal job is kept by default: long enough for a human to notice, short enough to bound storage.
         */
        internal val DEFAULT_TERMINAL_RETENTION: Duration = 30.days

        /**
         * Builds a [JobSettings] from environment variables, falling back to the regular default for any variable
         * that is unset. Variable names are [prefix] plus the property name in `SCREAMING_SNAKE_CASE`, e.g.
         * [hardQueueLimit] from `KLERK_JOBS_HARD_QUEUE_LIMIT`. [onUnloadableJob] and [execution] are read by their
         * enum constant name (e.g. `KLERK_JOBS_EXECUTION=Manual`); durations accept both [Duration] syntax
         * (`"24h"`) and ISO-8601 (`"PT24H"`).
         */
        public fun fromEnvVars(prefix: String = "KLERK_JOBS_"): JobSettings {
            val defaults = JobSettings()
            return JobSettings(
                onUnloadableJob = envEnum<UnloadableJobPolicy>("${prefix}ON_UNLOADABLE_JOB")
                    ?: defaults.onUnloadableJob,
                execution = envEnum<JobExecution>("${prefix}EXECUTION") ?: defaults.execution,
                succeededRetention = envDuration("${prefix}SUCCEEDED_RETENTION") ?: defaults.succeededRetention,
                cancelledRetention = envDuration("${prefix}CANCELLED_RETENTION") ?: defaults.cancelledRetention,
                deadLetterRetention = envDuration("${prefix}DEAD_LETTER_RETENTION") ?: defaults.deadLetterRetention,
                hardQueueLimit = envInt("${prefix}HARD_QUEUE_LIMIT") ?: defaults.hardQueueLimit,
                maxParallelSteps = envInt("${prefix}MAX_PARALLEL_STEPS") ?: defaults.maxParallelSteps,
                pollInterval = envDuration("${prefix}POLL_INTERVAL") ?: defaults.pollInterval,
                backoffBase = envDuration("${prefix}BACKOFF_BASE") ?: defaults.backoffBase,
            )
        }
    }
}

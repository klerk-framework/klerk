package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.attacheddata.PROCESS_ATTACHED_DATA
import dev.klerkframework.klerk.attacheddata.ProcessAttachedData
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
     * It is recommended to control this from e.g. an environment variable so that changing it does not require a rebuild.
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

/** What to do with cron fires that were missed because the node was down. */
public enum class CatchUp {
    /**
     * Fire once on recovery, however many fires were missed. The default, because it is what people mean by "the
     * nightly cleanup should still run".
     */
    RunOnce,

    /** Fire once for every missed occurrence. */
    RunAll,

    /** Fire none; wait for the next scheduled occurrence. */
    Skip,
}

/** What to do when a cron fires while the previous run of the same schedule is still going. */
public enum class Overlap {
    /** Don't fire. The default. */
    Skip,

    /** Fire once the previous run is terminal. */
    Queue,

    /** Fire anyway, so two runs are in flight at once. */
    Allow,
}

/**
 * A recurring schedule, declared statically in specification next to the job type it runs.
 *
 * Times are **UTC**, deliberately: a daily 02:30 local job does not exist on the spring-forward day and happens twice
 * in autumn, and there is no answer to that which is correct for everyone.
 */
public class CronSchedule<C : KlerkContext, V> internal constructor(
    public val type: JobType<*, C, V>,
    public val expression: String,
    internal val parsed: CronExpression,
    internal val encodedCursor: String,
    public val catchUp: CatchUp,
    public val overlap: Overlap,
    public val jitter: Duration,
) {
    /** Stable identity of this schedule, used as the key for the persisted last-fired time. */
    public val id: String = "${type.name.value}|$expression"

    override fun toString(): String = "cron(${type.name.value}, '$expression')"
}

/** Builder for the optional settings of a [CronSchedule]. */
@JobsSpecificationMarker
public class CronBuilder<Cursor : Any> internal constructor(private val defaultCursor: Cursor?) {
    /** What to do about fires missed while the node was down. */
    public var catchUp: CatchUp = CatchUp.RunOnce

    /** What to do when the previous run is still going. Distinct from the job type's `maxConcurrent`. */
    public var overlap: Overlap = Overlap.Skip

    /** Spreads the fire time over a random window, to avoid a thundering herd. */
    public var jitter: Duration = Duration.ZERO

    /** The cursor each fire starts with. Required unless the job type's cursor has a no-argument constructor. */
    public var cursor: Cursor? = defaultCursor

    internal fun require(): Cursor =
        requireNotNull(cursor) { "A cron schedule must declare the 'cursor' each fire starts with" }
}

@DslMarker
internal annotation class JobsSpecificationMarker

/**
 * What jobs the application has: the job types it can run, the schedules it runs them on, and the policy deciding
 * what may be queued. Built by `SpecificationBuilder.jobs { ... }`.
 *
 * How those jobs are *operated* — parallelism, polling, retention, backoff — is [JobSettings] instead.
 */
public class JobsSpecification<C : KlerkContext, V> internal constructor(
    types: Map<JobName, JobType<*, C, V>>,
    public val crons: List<CronSchedule<C, V>>,
    public val admission: (AdmissionArgs<C>) -> AdmissionDecision,
) {

    /**
     * Runs the steps a blob's declaration declares. Klerk schedules it itself, so it is registered whether or not the
     * application configured any jobs at all.
     */
    internal val processAttachedData: ProcessAttachedData<C, V> = ProcessAttachedData()

    /** Every registered job type, by name. Nothing else can be loaded from storage. */
    public val types: Map<JobName, JobType<*, C, V>> = types + (processAttachedData.name to processAttachedData)

    public companion object {
        /** No job types and no crons, with the default admission policy. */
        public fun <C : KlerkContext, V> empty(): JobsSpecification<C, V> = JobsSpecification(
            types = emptyMap(),
            crons = emptyList(),
            admission = AdmissionPolicy::delayBudget,
        )
    }

    /**
     * The same specification with more job types and crons in it, and the admission policy untouched.
     *
     * Deliberately not a `copy()`: who may queue work is the application's decision, and a plugin quietly replacing
     * it would be very hard to notice.
     */
    internal fun with(
        types: Map<JobName, JobType<*, C, V>>,
        crons: List<CronSchedule<C, V>>,
    ): JobsSpecification<C, V> = JobsSpecification(
        types = types,
        crons = crons,
        admission = admission,
    )
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
     * storage and audit history, not about freeing resources.
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

        /** How long a terminal job is kept by default: long enough for a human to notice, short enough to bound storage. */
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
                onUnloadableJob = envEnum<UnloadableJobPolicy>("${prefix}ON_UNLOADABLE_JOB") ?: defaults.onUnloadableJob,
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

/**
 * What a [dev.klerkframework.klerk.KlerkPlugin] may add to the job module: its own job types and crons, and nothing
 * else. See `Specification.withJobs`.
 */
@JobsSpecificationMarker
public class PluginJobsBlock<C : KlerkContext, V> internal constructor(private val delegate: JobsBlock<C, V>) {

    /**
     * Makes a job type loadable by name.
     *
     * @throws IllegalArgumentException if the application, or another plugin, already registered this name. Prefix
     * the name with the plugin's own to avoid collisions.
     */
    public fun register(type: JobType<*, C, V>): Unit = delegate.register(type)

    /** Declares a recurring run of [type], which must have been registered first. */
    public fun <Cursor : Any> cron(
        type: JobType<Cursor, C, V>,
        expression: String,
        init: CronBuilder<Cursor>.() -> Unit,
    ): Unit = delegate.cron(type, expression, init)
}

/**
 * The `jobs { ... }` block of [dev.klerkframework.klerk.SpecificationBuilder].
 *
 * ```
 * jobs {
 *     register(ImportBooks)
 *     register(NightlyCleanup)
 *     cron(NightlyCleanup, "0 3 * * *") {
 *         catchUp = CatchUp.RunOnce
 *         jitter = 5.minutes
 *         cursor = CleanupCursor(olderThan = 30.days)
 *     }
 * }
 * ```
 *
 * Operational knobs (parallelism, polling, retention, what happens to an unloadable job) are in
 * [dev.klerkframework.klerk.KlerkSettings.jobs] instead.
 */
@JobsSpecificationMarker
public class JobsBlock<C : KlerkContext, V> internal constructor() {

    private val types = mutableMapOf<JobName, JobType<*, C, V>>()
    private val crons = mutableListOf<CronSchedule<C, V>>()
    private var admissionPolicy: (AdmissionArgs<C>) -> AdmissionDecision = AdmissionPolicy::delayBudget

    /**
     * Makes a job type loadable by name. A persisted job whose name is not registered here cannot be run — see
     * [JobSettings.onUnloadableJob].
     *
     * @throws IllegalArgumentException if another type with the same [JobName] is already registered, or if the
     * type's cursor cannot be serialized.
     */
    public fun register(type: JobType<*, C, V>) {
        require(type.name.value != PROCESS_ATTACHED_DATA) {
            "The job name '$PROCESS_ATTACHED_DATA' belongs to Klerk itself"
        }
        require(!types.containsKey(type.name)) {
            "There is already a job type registered under the name '${type.name.value}'"
        }
        type.validateCursorCodec()
        require(type.maxRetries >= 0) { "maxRetries cannot be negative on the job type '${type.name.value}'" }
        require(type.maxConcurrent?.let { it > 0 } != false) {
            "maxConcurrent must be positive on the job type '${type.name.value}'"
        }
        require(type.maxSteps?.let { it > 0 } != false) {
            "maxSteps must be positive on the job type '${type.name.value}'"
        }
        require(type.maxDepth > 0) { "maxDepth must be positive on the job type '${type.name.value}'" }
        require(type.maxDescendants >= 0) { "maxDescendants cannot be negative on the job type '${type.name.value}'" }
        types[type.name] = type
    }

    /**
     * Declares a recurring run of [type].
     *
     * @param expression a five-field UTC cron expression: `minute hour day-of-month month day-of-week`. Supports `*`,
     * single values, `a-b` ranges, `base/step` steps (`&#42;&#47;15` meaning "every 15") and comma-separated lists.
     * @throws IllegalArgumentException if [expression] is not a valid cron expression, or if [type] has not been
     * registered.
     */
    public fun <Cursor : Any> cron(
        type: JobType<Cursor, C, V>,
        expression: String,
        init: CronBuilder<Cursor>.() -> Unit,
    ) {
        require(types.containsKey(type.name)) {
            "The job type '${type.name.value}' must be register()ed before a cron can be declared for it"
        }
        val builder = CronBuilder<Cursor>(null)
        builder.init()
        require(builder.jitter >= Duration.ZERO) { "jitter cannot be negative" }
        crons.add(
            CronSchedule(
                type = type,
                expression = expression,
                parsed = CronExpression.parse(expression),
                encodedCursor = type.encodeCursor(builder.require()),
                catchUp = builder.catchUp,
                overlap = builder.overlap,
                jitter = builder.jitter,
            )
        )
    }

    /**
     * Replaces the admission policy. Must be a named function reference, and should be a pure function — it runs
     * inside command processing on the single writer.
     *
     * Defaults to [AdmissionPolicy.delayBudget].
     */
    public fun admission(policy: (AdmissionArgs<C>) -> AdmissionDecision) {
        admissionPolicy = policy
    }

    /** The types registered so far. Used when a plugin's registrations are merged into an existing specification. */
    internal fun types(): Map<JobName, JobType<*, C, V>> = types.toMap()

    internal fun crons(): List<CronSchedule<C, V>> = crons.toList()

    /**
     * Starts from what an application already configured, so that a plugin's `register` sees existing names (and
     * rejects a collision) and its `cron` can refer to a type either of them registered.
     */
    internal fun seedFrom(existing: JobsSpecification<C, V>) {
        types.putAll(existing.types)
        crons.addAll(existing.crons)
    }

    internal fun build(): JobsSpecification<C, V> {
        require(crons.map { it.id }.toSet().size == crons.size) {
            "Two cron schedules for the same job type cannot have the same expression"
        }
        return JobsSpecification(
            types = types.toMap(),
            crons = crons.toList(),
            admission = admissionPolicy,
        )
    }
}

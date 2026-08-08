package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import kotlin.time.Duration
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
     * Refuse to start. The default: a job you can no longer run is a deploy mistake, and silently discarding durable
     * work is worse than not starting.
     *
     * Control this from an environment variable so that changing it does not require a rebuild.
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
 * A recurring schedule, declared statically in config next to the job type it runs.
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
@JobsConfigMarker
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
internal annotation class JobsConfigMarker

/**
 * The assembled job-module configuration. Built by `ConfigBuilder.jobs { ... }`.
 */
public class JobsConfig<C : KlerkContext, V> internal constructor(
    /** Every registered job type, by name. Nothing else can be loaded from storage. */
    public val types: Map<JobName, JobType<*, C, V>>,
    public val crons: List<CronSchedule<C, V>>,
    public val onUnloadableJob: UnloadableJobPolicy,
    public val execution: JobExecution,
    public val admission: (AdmissionArgs<C>) -> AdmissionDecision,
    public val deadLetterRetention: Duration?,
    public val hardQueueLimit: Int,
    public val maxParallelSteps: Int,
    public val pollInterval: Duration,
    public val backoffBase: Duration,
) {
    public companion object {
        /** The default job configuration: no job types, everything else at its default. */
        public fun <C : KlerkContext, V> empty(): JobsConfig<C, V> = JobsConfig(
            types = emptyMap(),
            crons = emptyList(),
            onUnloadableJob = UnloadableJobPolicy.FailToStart,
            execution = JobExecution.Automatic,
            admission = AdmissionPolicy::delayBudget,
            deadLetterRetention = null,
            hardQueueLimit = DEFAULT_HARD_QUEUE_LIMIT,
            maxParallelSteps = DEFAULT_MAX_PARALLEL_STEPS,
            pollInterval = 1.seconds,
            backoffBase = 3.seconds,
        )

        internal const val DEFAULT_HARD_QUEUE_LIMIT: Int = 100_000
        internal const val DEFAULT_MAX_PARALLEL_STEPS: Int = 4
    }
}

/**
 * The `jobs { ... }` block of [dev.klerkframework.klerk.ConfigBuilder].
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
 *     onUnloadableJob = UnloadableJobPolicy.DeadLetter
 * }
 * ```
 */
@JobsConfigMarker
public class JobsBlock<C : KlerkContext, V> internal constructor() {

    private val types = mutableMapOf<JobName, JobType<*, C, V>>()
    private val crons = mutableListOf<CronSchedule<C, V>>()
    private var admissionPolicy: (AdmissionArgs<C>) -> AdmissionDecision = AdmissionPolicy::delayBudget

    /**
     * What to do at startup with a job whose name is no longer registered, or whose cursor no longer deserializes.
     */
    public var onUnloadableJob: UnloadableJobPolicy = UnloadableJobPolicy.FailToStart

    /** Whether jobs run on their own, or only when a test drives them. */
    public var execution: JobExecution = JobExecution.Automatic

    /** How long a dead-lettered job is kept before it is deleted. Null (the default) keeps it forever. */
    public var deadLetterRetention: Duration? = null

    /**
     * The hard cap on non-terminal jobs, enforced before the admission policy runs so that a policy which always
     * returns `Allow` still cannot exhaust memory.
     */
    public var hardQueueLimit: Int = JobsConfig.DEFAULT_HARD_QUEUE_LIMIT

    /** How many job steps may run at the same time. Commits are serialized regardless. */
    public var maxParallelSteps: Int = JobsConfig.DEFAULT_MAX_PARALLEL_STEPS

    /** How often the dispatcher looks for work that has become ready. Ignored in [JobExecution.Manual]. */
    public var pollInterval: Duration = 1.seconds

    /** The base of the exponential retry backoff: attempt *n* waits `base * 3^(n-1)`. */
    public var backoffBase: Duration = 3.seconds

    /**
     * Makes a job type loadable by name. A persisted job whose name is not registered here cannot be run — see
     * [onUnloadableJob].
     *
     * @throws IllegalArgumentException if another type with the same [JobName] is already registered, or if the
     * type's cursor cannot be serialized.
     */
    public fun register(type: JobType<*, C, V>) {
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

    internal fun build(): JobsConfig<C, V> {
        require(hardQueueLimit > 0) { "hardQueueLimit must be positive" }
        require(maxParallelSteps > 0) { "maxParallelSteps must be positive" }
        require(pollInterval > Duration.ZERO) { "pollInterval must be positive" }
        require(backoffBase > Duration.ZERO) { "backoffBase must be positive" }
        require(deadLetterRetention?.let { it > Duration.ZERO } != false) {
            "deadLetterRetention must be positive, or null to keep dead letters forever"
        }
        require(crons.map { it.id }.toSet().size == crons.size) {
            "Two cron schedules for the same job type cannot have the same expression"
        }
        return JobsConfig(
            types = types.toMap(),
            crons = crons.toList(),
            onUnloadableJob = onUnloadableJob,
            execution = execution,
            admission = admissionPolicy,
            deadLetterRetention = deadLetterRetention,
            hardQueueLimit = hardQueueLimit,
            maxParallelSteps = maxParallelSteps,
            pollInterval = pollInterval,
            backoffBase = backoffBase,
        )
    }
}

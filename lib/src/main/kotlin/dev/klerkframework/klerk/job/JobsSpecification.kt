package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.attacheddata.PROCESS_ATTACHED_DATA
import dev.klerkframework.klerk.attacheddata.ProcessAttachedData
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
    /** The recurring runs. */
    public val crons: List<CronSchedule<C, V>>,
    /** Decides whether new work may be queued. */
    public val admission: (AdmissionArgs<C>) -> AdmissionDecision,
) {

    /**
     * Runs the steps a blob's declaration declares. Klerk schedules it itself, so it is registered whether or not the
     * application configured any jobs at all.
     */
    internal val processAttachedData: ProcessAttachedData<C, V> = ProcessAttachedData()

    /** The job types the application and its plugins registered, by name. */
    public val types: Map<JobName, JobType<*, C, V>> = types

    /** [types] plus the job types Klerk registers itself. Nothing else can be loaded from storage. */
    internal val allTypes: Map<JobName, JobType<*, C, V>> =
        types + (processAttachedData.name to processAttachedData)

    override fun toString(): String =
        "JobsSpecification(types=${types.keys.joinToString(", ")}, crons=${crons.size})"

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

    public companion object {
        /** No job types and no crons, with the default admission policy. */
        public fun <C : KlerkContext, V> empty(): JobsSpecification<C, V> = JobsSpecification(
            types = emptyMap(),
            crons = emptyList(),
            admission = AdmissionPolicy::delayBudget,
        )
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
    public fun register(type: JobType<*, C, V>) {
        delegate.register(type)
    }

    /** Declares a recurring run of [type], which must have been registered first. */
    public fun <Cursor : Any> cron(
        type: JobType<Cursor, C, V>,
        expression: String,
        init: CronBuilder<Cursor>.() -> Unit,
    ) {
        delegate.cron(type, expression, init)
    }
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
     * [expression] is a five-field UTC cron expression: `minute hour day-of-month month day-of-week`. It supports `*`,
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
            ),
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

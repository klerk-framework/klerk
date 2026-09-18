package dev.klerkframework.klerk

import dev.klerkframework.klerk.job.JobsBlock
import dev.klerkframework.klerk.job.JobsSpecification
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.view.ModelViews
import java.util.SortedSet
import kotlin.reflect.KClass
import kotlin.time.Duration

@DslMarker
internal annotation class SpecificationMarker

/**
 * DSL entry point for building a [Specification]. Typical usage:
 * ```
 * val specification = SpecificationBuilder<MyContext, MyViews>(views).build {
 *     systemContextProvider { MyContext(SystemIdentity) }
 *     managedModels { model(MyModel::class, myModelStateMachine, myModelViews) }
 *     authorization { ... }
 * }
 * ```
 * [systemContextProvider], [managedModels] and [authorization] are all required; [build] throws
 * [IllegalConfigurationException] if any is missing. [migrations] and [jobs] are optional.
 *
 * Where the data is stored, what the clock is and how the job dispatcher is tuned are not part of the specification
 * — they go in [KlerkSettings].
 */
@SpecificationMarker
public class SpecificationBuilder<C : KlerkContext, V>(private val views: V) {

    /**
     * Runs [init] against this builder and assembles the resulting [Specification].
     *
     * @throws IllegalConfigurationException if [systemContextProvider], [authorization] or [managedModels] was not
     * called inside [init]
     */
    public fun build(init: SpecificationBuilder<C, V>.() -> Unit): Specification<C, V> {
        this.init()
        runCatching { systemContextProviderValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingSystemContextProvider,
                "'systemContextProvider' is missing in the specification",
            )
        }
        runCatching { authorizationRulesBlock }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingAuthorization,
                "'authorization' is missing in the specification",
            )
        }
        runCatching { managedModelsValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingManagedModels,
                "'managedModels' is missing in the specification",
            )
        }

        return Specification(
            views = views,
            authorization = AuthorizationConfig(
                readModelPositiveRules = authorizationRulesBlock.readModelPositiveRules,
                readModelNegativeRules = authorizationRulesBlock.readModelNegativeRules,
                readPropertyPositiveRules = authorizationRulesBlock.readPropertyPositiveRules,
                readPropertyNegativeRules = authorizationRulesBlock.readPropertyNegativeRules,
                eventPositiveRules = authorizationRulesBlock.eventPositiveRules,
                eventNegativeRules = authorizationRulesBlock.eventNegativeRules,
                eventLogPositiveRules = authorizationRulesBlock.eventLogPositiveRules,
                eventLogNegativeRules = authorizationRulesBlock.eventLogNegativeRules,
                attachedDataReadPositiveRules = authorizationRulesBlock.attachedDataReadPositiveRules,
                attachedDataReadNegativeRules = authorizationRulesBlock.attachedDataReadNegativeRules,
                attachedDataWritePositiveRules = authorizationRulesBlock.attachedDataWritePositiveRules,
                attachedDataWriteNegativeRules = authorizationRulesBlock.attachedDataWriteNegativeRules,
                jobPositiveRules = authorizationRulesBlock.jobPositiveRules,
                jobNegativeRules = authorizationRulesBlock.jobNegativeRules,
            ),
            managedModels = managedModelsValue,
            migrationSteps = migrationStepsValue,
            systemContextProvider = systemContextProviderValue,
            jobs = jobsValue,
            jobContextProvider = jobContextProviderValue,
            eraseEventLogAfterModelDeletion = eraseEventLogValue,
        ).let { spec -> pluginsValue.fold(spec) { acc, plugin -> acc.withPlugin(plugin) } }
    }

    private var migrationStepsValue: SortedSet<MigrationStep> = sortedSetOf()
    private var pluginsValue: List<KlerkPlugin<C, V>> = emptyList()
    private var jobsValue: JobsSpecification<C, V> = JobsSpecification.empty()
    private var jobContextProviderValue: ((JobContextRequest) -> C)? = null
    private var eraseEventLogValue: Duration? = null
    private lateinit var authorizationRulesBlock: AuthorizationRulesBlock<C, V>
    private lateinit var managedModelsValue: Set<ManagedModel<*, *, C, V>>
    private lateinit var systemContextProviderValue: (() -> C)

    /**
     * Erases the event log of a model when the model is deleted. Only [Duration.ZERO] (erase immediately) is
     * currently supported; the default is to never erase.
     */
    public fun eraseEventLogAfterModelDeletion(after: Duration) {
        eraseEventLogValue = after
    }

    /**
     * Registers [KlerkPlugin]s. Each plugin contributes its own models, events, rules and jobs, and is started and
     * stopped together with Klerk. They are merged in the order given, so a plugin that builds on another must come
     * after it.
     *
     * ```kotlin
     * val images = ImagesPlugin()
     * SpecificationBuilder<Ctx, Views>(views).build {
     *     plugins(images, AssetsPlugin(setOf(css), images = images))
     *     // ...
     * }
     * ```
     */
    public fun plugins(vararg plugin: KlerkPlugin<C, V>) {
        pluginsValue = pluginsValue + plugin
    }

    /**
     * Registers the [MigrationStep]s used to evolve model shapes across schema versions. Steps are reordered by
     * [MigrationStep.migratesToVersion]; [Specification] additionally requires them to form a contiguous chain starting
     * at version 2 (version 1 is implicit). Optional — omit if the schema has never changed.
     */
    public fun migrations(vararg migrationSteps: MigrationStep) {
        migrationStepsValue =
            sortedSetOf(Comparator.comparingInt(MigrationStep::migratesToVersion), *migrationSteps)
    }

    /**
     * Klerk may execute commands in the background (e.g. if you have a statemachine with after(5.minutes) {...}). And
     * since a context is required when executing a command, Klerk needs a way to create such a context with the
     * SystemIdentity.
     */
    public fun systemContextProvider(provider: () -> C) {
        systemContextProviderValue = provider
    }

    /**
     * Registers the models Klerk manages. Call [ManagedModelsBlock.model] once per model class inside [init].
     * Required — at least the models used by the application must be declared here.
     */
    public fun managedModels(init: ManagedModelsBlock<C, V>.() -> Unit) {
        val block = ManagedModelsBlock<C, V>()
        block.init()
        managedModelsValue = block.value
    }

    /**
     * Declares the authorization rules that govern reads, commands, the event log, and attached data. Required — see
     * [AuthorizationRulesBlock] for the sub-blocks, or [AuthorizationRulesBlock.allowEverythingInsecurely] to opt out
     * of authorization entirely (development/testing only).
     *
     * A rule runs under the lock of whatever it guards — the command mutex, or the read lock — so one that throws
     * propagates out of `handle`/`read`, and one that does IO delays everything else. See docs/concurrency.md.
     */
    public fun authorization(init: AuthorizationRulesBlock<C, V>.() -> Unit) {
        authorizationRulesBlock = AuthorizationRulesBlock<C, V>()
        authorizationRulesBlock.init()
    }

    /**
     * Registers job types and cron schedules, and configures the job module. Optional — omit it if the application has
     * no jobs.
     *
     * ```
     * jobs {
     *     register(ImportBooks)
     *     cron(NightlyCleanup, "0 3 * * *") { cursor = CleanupCursor() }
     * }
     * ```
     */
    public fun jobs(init: JobsBlock<C, V>.() -> Unit) {
        val block = JobsBlock<C, V>()
        block.init()
        jobsValue = block.build()
    }

    /**
     * Builds the context each job step runs under, from the job's agent, the configured clock and the job itself.
     * Optional; without it, [systemContextProvider] is used and the job runs as the system.
     *
     * Required if any registered job type declares [dev.klerkframework.klerk.job.JobAgent.Scheduler], since Klerk
     * cannot construct a context for an arbitrary actor on its own.
     *
     * ```
     * jobContextProvider(::jobContext)
     *
     * fun jobContext(request: JobContextRequest): Ctx = Ctx(actor = request.actor, time = request.time)
     * ```
     *
     * The actor is rebuilt from storage, so an actor identified by a model arrives as a [ModelReferenceIdentity] — the
     * id, not the model — and this function has no reader with which to load it. Rules that a job's commands must pass
     * should therefore compare the actor's id rather than a model the context only carries on a request.
     */
    public fun jobContextProvider(provider: (JobContextRequest) -> C) {
        jobContextProviderValue = provider
    }

    /** Receiver of `managedModels { }`, where the models Klerk manages are registered. */
    @SpecificationMarker
    public class ManagedModelsBlock<C : KlerkContext, V> {

        internal val value = mutableSetOf<ManagedModel<*, *, C, V>>()

        /**
         * Registers [clazz] as a managed model with its [stateMachine] and [view] (the [ModelViews] holding its
         * Kotlin collections). [clazz] must be a data class that Klerk can handle (see [ObjectSchema]); every managed
         * model's simple name must be unique within the specification.
         *
         * @throws IllegalArgumentException if [clazz] is not a data class, or another managed model already has the
         * same simple name
         * @throws IllegalConfigurationException if [clazz] has a `var`, or a property Klerk cannot store
         */
        public fun <T : Any, ModelStates : Enum<*>> model(
            clazz: KClass<T>,
            stateMachine: StateMachine<T, ModelStates, C, V>,
            view: ModelViews<T, C>,
        ) {
            validateModelClass(clazz)
            require(value.none { it.kClass.simpleName == clazz.simpleName }) {
                "Managed models must have unique simpleNames ('${clazz.simpleName!!}' is not unique)"
            }
            value.add(ManagedModel(clazz, stateMachine, view))
        }
    }

    /**
     * The seven independent authorization categories — [readModels], [readProperties], [commands], [eventLog],
     * [readAttachedData], [writeAttachedData] and [jobs] — each taking an [AuthorizationRules] block. A category with
     * no rules denies everything in it. See the "Authorization" doc for how positive/negative rules combine, or
     * [allowEverythingInsecurely] to disable authorization for development.
     *
     * Every rule must be a named function reference, e.g. `positive(::myRule)`. A lambda is rejected when Klerk starts.
     */
    @SpecificationMarker
    public class AuthorizationRulesBlock<C : KlerkContext, V> {

        internal val readModelPositiveRules =
            mutableSetOf<(ModelReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val readModelNegativeRules =
            mutableSetOf<(ModelReadRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val readPropertyPositiveRules =
            mutableSetOf<(PropertyReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val readPropertyNegativeRules =
            mutableSetOf<(PropertyReadRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val eventPositiveRules =
            mutableSetOf<(CommandRuleArgs<*, C, V>) -> PositiveAuthorization>()
        internal val eventNegativeRules =
            mutableSetOf<(CommandRuleArgs<*, C, V>) -> NegativeAuthorization>()
        internal val eventLogPositiveRules =
            mutableSetOf<(args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val eventLogNegativeRules =
            mutableSetOf<(args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()
        internal val attachedDataReadPositiveRules =
            mutableSetOf<(AttachedDataReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val attachedDataReadNegativeRules =
            mutableSetOf<(AttachedDataReadRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val attachedDataWritePositiveRules =
            mutableSetOf<(AttachedDataWriteRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val attachedDataWriteNegativeRules =
            mutableSetOf<(AttachedDataWriteRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val jobPositiveRules = mutableSetOf<(JobReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val jobNegativeRules = mutableSetOf<(JobReadRuleArgs<C, V>) -> NegativeAuthorization>()

        /**
         * Rules deciding who may read a model as a whole (returned by `get`/`list`/views).
         */
        public fun readModels(init: AuthorizationRules<ModelReadRuleArgs<C, V>>.() -> Unit) {
            val block = AuthorizationRules<ModelReadRuleArgs<C, V>>()
            block.init()
            readModelPositiveRules.addAll(block.positiveRules)
            readModelNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Rules deciding who may read individual model properties. Evaluated per property, independently of
         * [readModels].
         */
        public fun readProperties(init: AuthorizationRules<PropertyReadRuleArgs<C, V>>.() -> Unit) {
            val block = AuthorizationRules<PropertyReadRuleArgs<C, V>>()
            block.init()
            readPropertyPositiveRules.addAll(block.positiveRules)
            readPropertyNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Rules deciding who may trigger which events/commands, i.e. who is allowed to call [Klerk.handle] for a
         * given command.
         */
        public fun commands(init: AuthorizationRules<CommandRuleArgs<*, C, V>>.() -> Unit) {
            val block = AuthorizationRules<CommandRuleArgs<*, C, V>>()
            block.init()
            eventPositiveRules.addAll(block.positiveRules)
            eventNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Rules deciding who may read the event log, i.e. [dev.klerkframework.klerk.read.Reader.eventLog].
         */
        public fun eventLog(init: AuthorizationRules<EventLogRuleArgs<C, V>>.() -> Unit) {
            val block = AuthorizationRules<EventLogRuleArgs<C, V>>()
            block.init()
            eventLogPositiveRules.addAll(block.positiveRules)
            eventLogNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Rules deciding who may read attached data, i.e. `klerk.attachedData.get(...)` and
         * `klerk.attachedData.getMetadata(...)`.
         *
         * Note that these rules are only consulted for private data. Data prepared as
         * [dev.klerkframework.klerk.AttachedDataVisibility.Public] is readable by anyone, and not even a negative rule
         * here will stop that — the point of public data is that the decision cannot change over time.
         * See [dev.klerkframework.klerk.KlerkAttachedData].
         */
        public fun readAttachedData(init: AuthorizationRules<AttachedDataReadRuleArgs<C, V>>.() -> Unit) {
            val block = AuthorizationRules<AttachedDataReadRuleArgs<C, V>>()
            block.init()
            attachedDataReadPositiveRules.addAll(block.positiveRules)
            attachedDataReadNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Rules deciding who may prepare attached data, i.e. `klerk.attachedData.prepare(...)`.
         *
         * Note that this is weak by construction: at that point there is no model and no command. The real gate on
         * *attaching* data to a model is the normal event authorization of the command that claims it. What these
         * rules are good at is the visibility: they can let anyone upload while allowing only some actors to publish
         * something the whole world may read. See [dev.klerkframework.klerk.KlerkAttachedData].
         */
        public fun writeAttachedData(init: AuthorizationRules<AttachedDataWriteRuleArgs<C, V>>.() -> Unit) {
            val block = AuthorizationRules<AttachedDataWriteRuleArgs<C, V>>()
            block.init()
            attachedDataWritePositiveRules.addAll(block.positiveRules)
            attachedDataWriteNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Rules deciding who may see a job's metadata — its status, progress and log — via
         * [JobManager.get]/[JobManager.all]/[JobManager.subscribe].
         *
         * The same rules gate [JobManager.cancel], so a user who can watch their own progress bar can also cancel
         * their own job. [JobReadRuleArgs.isOwnedBy] answers "did this actor schedule it?".
         */
        public fun jobs(init: AuthorizationRules<JobReadRuleArgs<C, V>>.() -> Unit) {
            val block = AuthorizationRules<JobReadRuleArgs<C, V>>()
            block.init()
            jobPositiveRules.addAll(block.positiveRules)
            jobNegativeRules.addAll(block.negativeRules)
        }

        /**
         * Allows every actor to do everything: read all models/properties/event log/attached data and trigger all
         * commands. Logs a warning when applied.
         *
         * ```kotlin
         * authorization { allowEverythingInsecurely() }
         * ```
         *
         * For development/testing only — never use in production.
         */
        public fun allowEverythingInsecurely() {
            logger.warn { "The authorization rules allows everything. The application is insecure!" }
            readModels { positive(this@AuthorizationRulesBlock::everybodyCanReadModels) }
            readProperties { positive(this@AuthorizationRulesBlock::everybodyCanReadAllProperties) }
            commands { positive(this@AuthorizationRulesBlock::everybodyCanDoEverything) }
            eventLog { positive(this@AuthorizationRulesBlock::everybodyCanReadEventLog) }
            readAttachedData { positive(this@AuthorizationRulesBlock::everybodyCanReadAllAttachedData) }
            writeAttachedData { positive(this@AuthorizationRulesBlock::everybodyCanWriteAttachedData) }
            jobs { positive(this@AuthorizationRulesBlock::everybodyCanSeeAllJobs) }
        }

        private fun everybodyCanSeeAllJobs(args: JobReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadModels(args: ModelReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadAllAttachedData(args: AttachedDataReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanWriteAttachedData(args: AttachedDataWriteRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadAllProperties(args: PropertyReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanDoEverything(args: CommandRuleArgs<*, C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadEventLog(args: EventLogRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow
    }

    /**
     * The rules of one authorization category, where [A] is the argument type its rules take (e.g.
     * [ModelReadRuleArgs] for `readModels`).
     *
     * ```kotlin
     * readModels {
     *     positive(::everybodyCanReadBooks, ::authorsCanReadTheirOwnDrafts)
     *     negative(::nobodyCanReadADeletedBook)
     * }
     * ```
     *
     * An actor is allowed when at least one positive rule allows it and no negative rule denies it. A category with
     * no positive rule therefore denies everything in it.
     */
    @SpecificationMarker
    public class AuthorizationRules<A> {

        internal val positiveRules = mutableSetOf<(A) -> PositiveAuthorization>()
        internal val negativeRules = mutableSetOf<(A) -> NegativeAuthorization>()

        /** Rules that can allow the actor. Each must be a named function reference, e.g. `positive(::myRule)`. */
        public fun positive(vararg rule: (A) -> PositiveAuthorization) {
            positiveRules.addAll(rule)
        }

        /** Rules that can deny the actor, overriding any positive rule. Each must be a named function reference. */
        public fun negative(vararg rule: (A) -> NegativeAuthorization) {
            negativeRules.addAll(rule)
        }
    }
}

private fun <T : Any> validateModelClass(clazz: KClass<T>) {
    require(clazz.isData) { "${clazz.qualifiedName} must be a data class" }
    ObjectSchema.of(clazz)
}

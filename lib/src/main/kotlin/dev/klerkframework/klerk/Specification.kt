package dev.klerkframework.klerk

import dev.klerkframework.klerk.validation.ContextValidity
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.misc.*
import dev.klerkframework.klerk.statemachine.DeclaredEventRules
import dev.klerkframework.klerk.statemachine.StateMachine
import mu.KotlinLogging
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.time.Duration

internal val logger = KotlinLogging.logger {}

/**
 * What the application *is*: its models and state machines, the events they accept, who is allowed to do what, the
 * jobs it runs and how its data evolves. Built via [SpecificationBuilder.build] (typically through
 * `SpecificationBuilder(views).build { ... }`), then passed to [Klerk.Companion.create] together with a
 * [KlerkSettings].
 *
 * Nothing here varies between deployments: two instances of the same application share a specification and differ
 * only in their settings. Where the data is stored, what the clock is and how hard the job dispatcher works are
 * therefore in [KlerkSettings], not here.
 *
 * Most of the functions on this class are introspection helpers used internally by the framework (validation,
 * the JSON serializer, `klerk-web`/`klerk-graphql`) to look up state machines, events and views by reference.
 * Application code normally doesn't need to call them directly.
 */
public data class Specification<C : KlerkContext, V>(
    /** The application's views. */
    public val views: V,
    /** The authorization rules. */
    public val authorization: AuthorizationConfig<C, V>,
    val managedModels: Set<ManagedModel<*, *, C, V>>,
    val migrationSteps: SortedSet<MigrationStep>,
    val plugins: List<KlerkPlugin<C, V>> = listOf(),
    val systemContextProvider: (() -> C),
    /** The job types and cron schedules this application declares, built by `SpecificationBuilder.jobs { ... }`. */
    val jobs: JobsSpecification<C, V> = JobsSpecification.empty(),
    /**
     * Builds the context a job step runs under. Optional; when absent, [systemContextProvider] is used.
     *
     * Configure it if you use [dev.klerkframework.klerk.job.JobAgent.Scheduler] (which needs a context for an actor
     * other than the system), or if you want a job step's `context.time` to come from [KlerkSettings.clock] — which
     * is what makes a job's own view of time controllable in tests.
     */
    val jobContextProvider: ((JobContextRequest) -> C)? = null,
    /**
     * Whether the event log of a model is erased when the model is deleted. A requirement about the application (a
     * privacy promise, typically), which is why it lives here and not in [KlerkSettings].
     *
     * Only `null` (never erase, the default) and [Duration.ZERO] (erase immediately on model deletion) are
     * currently supported; any other value is rejected on startup.
     */
    val eraseEventLogAfterModelDeletion: Duration? = null,
) {
    internal fun initialize(settings: KlerkSettings) {
        validate(settings)
        validateMigrations()
        managedModels.map { it.stateMachine.onKlerkStart(this) }
    }

    /** Every [AttachedBlobContainer] class a model property or event parameter uses, by qualified name. */
    internal val attachedBlobContainers: Map<String, KClass<out AttachedBlobContainer>> by lazy {
        declaredAttachedBlobContainers().keys
            .mapNotNull { kClass -> kClass.qualifiedName?.let { it to kClass } }
            .toMap()
    }

    /**
     * The model classes registered via `managedModels { model(...) }` when the specification was built.
     */
    public val managedClasses: Set<KClass<out Any>> get() = managedModels.map { it.kClass }.toSet()

    /**
     * The [ModelViews] registered for the model class [clazz] (i.e. the same instance exposed as a property on [V]).
     *
     * @throws NoSuchElementException if [clazz] is not a managed model
     */
    public fun <T : Any> modelViews(clazz: KClass<T>): ModelViews<T, C> {
        val mm = managedModels.find { it.kClass == clazz }
            ?: throw NoSuchElementException("Cannot find views for ${clazz.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return mm.views as ModelViews<T, C>
    }

    /** Every [ModelView] declared on any managed model's [ModelViews], with the model class it belongs to. */
    public val registeredViews: List<RegisteredView<C>> get() =
        managedModels.flatMap { managed ->
            managed.views.views.map { RegisteredView(managed.kClass, it) }
        }

    /**
     * Looks up a single [ModelView] by its [ViewID].
     *
     * @throws NoSuchElementException if no view matches [id]
     */
    public fun view(id: ViewID): ModelView<out Any, C> =
        registeredViews.firstOrNull { it.view.id == id }?.view
            ?: throw NoSuchElementException("Cannot find view '$id'")

    /**
     * The [ModelView] the ids in [field] must be found in, as declared with `validReferences(...)` for the event
     * [eventReference]. Null if the event has no parameters, or if [field] is not a [ModelID] — every [ModelID]
     * has a view, since Klerk rejects the specification at startup otherwise.
     */
    public fun validReferencesFor(
        eventReference: EventReference,
        field: SchemaField,
    ): ModelView<out Any, C>? {
        val parametersClass = parametersClassOf(eventReference) ?: return null
        return validReferencesOf(eventReference)[PropertyKey(parametersClass, field.name)]
    }

    /** What the `event(...)` block declared for [eventReference], looked up in the state machine that declared it. */
    internal fun rulesOf(eventReference: EventReference): DeclaredEventRules =
        getStateMachine(eventReference).rulesFor(eventReference)

    /**
     * The rules declared with `validateWithContext(...)` for [eventReference] — the ones that run against the
     * context alone, before anything is read. Mainly for tooling that documents an event.
     */
    public fun contextRulesFor(eventReference: EventReference): Set<(C) -> ContextValidity> =
        rulesOf(eventReference).forContext()

    /** The views declared with `validReferences` for the event, by property. */
    @Suppress("UNCHECKED_CAST")
    internal fun validReferencesOf(eventReference: EventReference): Map<PropertyKey, ModelView<out Any, C>> =
        rulesOf(eventReference).validRefs as Map<PropertyKey, ModelView<out Any, C>>

    /** The values declared with `validEnums` for the event, by property. */
    internal fun validEnumsOf(eventReference: EventReference): Map<PropertyKey, Set<Enum<*>>> =
        rulesOf(eventReference).validEnums

    private fun parametersClassOf(eventReference: EventReference): KClass<*>? =
        when (val event = event(eventReference)) {
            is InstanceEventWithParameters<*, *> -> event.parametersClass
            is VoidEventWithParameters<*, *> -> event.parametersClass
            else -> null
        }

    /**
     * The set of allowed values declared with `validEnums(...)` for the parameter [field] of the event
     * [eventReference], or null if none was declared (in which case all enum values are allowed).
     */
    public fun validEnumsFor(
        eventReference: EventReference,
        field: SchemaField,
    ): Set<Enum<*>>? {
        val parametersClass = parametersClassOf(eventReference) ?: return null
        return validEnumsOf(eventReference)[PropertyKey(parametersClass, field.name)]
    }

    /**
     * Looks up the declared [Event] for [reference].
     *
     * @throws NoSuchElementException if no managed model declares an event with this reference
     */
    public fun event(reference: EventReference): Event<Any, Any?> {
        @Suppress("UNCHECKED_CAST")
        return getStateMachine(reference).states.flatMap { it.getEvents() }
            .first { it.id == reference } as Event<Any, Any?>
    }

    internal fun getStateMachine(eventReference: EventReference): StateMachine<out Any, out Enum<*>, C, V> {
        return managedModels.find { it.stateMachine.type.simpleName == eventReference.modelName }?.stateMachine
            ?: throw RuntimeException("Can't find state machine for event '$eventReference'")
    }

    internal fun <T : Any> getStateMachine(model: Model<T>): StateMachine<T, out Enum<*>, C, V> {
        val sm = managedModels.find { it.kClass == model.props::class }?.stateMachine
            ?: throw InternalException()
        @Suppress("UNCHECKED_CAST")
        return sm as StateMachine<T, out Enum<*>, C, V>
    }

    internal fun <T : Any> getStateMachine(clazz: KClass<T>): StateMachine<out Any, out Enum<*>, C, V> {
        val sm = managedModels.find { it.kClass == clazz }?.stateMachine
            ?: throw InternalException()
        return sm
    }

    /**
     * The [ObjectSchema] of the parameters class of the event [eventReference], or null if the event takes no
     * parameters.
     */
    public fun parametersSchema(eventReference: EventReference): ObjectSchema<*>? =
        parametersClassOf(eventReference)?.let { ObjectSchema.of(it) }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any, P> getStateMachineForEvent(event: Event<T, P>): StateMachine<T, out Enum<*>, C, V> =
        getStateMachine(event.id) as StateMachine<T, out Enum<*>, C, V>

    /**
     * The same configuration with a plugin's own job types and crons added, for use from
     * [KlerkPlugin.mergeSpecification]:
     *
     * ```kotlin
     * override fun mergeSpecification(previous: Specification<C, V>): Specification<C, V> =
     *     previous.withJobs {
     *         register(sweepStagingArea)
     *         cron(sweepStagingArea, "0 * * * *") { cursor = "" }
     *     }
     * ```
     *
     * A plugin can add work, not change how the job module runs: how many steps run at once, how often the dispatcher
     * polls and what happens to an unloadable job stay the application's decisions.
     *
     * @throws IllegalArgumentException if a job name is already registered — prefix names with the plugin's own.
     */
    public fun withJobs(init: PluginJobsBlock<C, V>.() -> Unit): Specification<C, V> {
        val block = JobsBlock<C, V>()
        block.seedFrom(jobs)
        PluginJobsBlock(block).init()
        return copy(jobs = jobs.with(block.types(), block.crons()))
    }

    /** Returns this specification with [plugin] registered and its specification merged in. */
    public fun withPlugin(plugin: KlerkPlugin<C, V>): Specification<C, V> {
        val updatedPlugins = plugins.toMutableList()
        updatedPlugins.add(plugin)
        return plugin.mergeSpecification(this).copy(plugins = updatedPlugins)
    }

}

/**
 * The assembled authorization rule sets, one property per category. Built by [SpecificationBuilder.authorization]; not
 * meant to be constructed directly by application code.
 */
public data class AuthorizationConfig<C : KlerkContext, V>(
    val readModelPositiveRules: Set<(ModelReadRuleArgs<C, V>) -> PositiveAuthorization>,
    val readModelNegativeRules: Set<(ModelReadRuleArgs<C, V>) -> NegativeAuthorization>,
    val readPropertyPositiveRules: Set<(PropertyReadRuleArgs<C, V>) -> PositiveAuthorization>,
    val readPropertyNegativeRules: Set<(PropertyReadRuleArgs<C, V>) -> NegativeAuthorization>,
    val eventPositiveRules: Set<(CommandRuleArgs<*, C, V>) -> PositiveAuthorization>,
    val eventNegativeRules: Set<(CommandRuleArgs<*, C, V>) -> NegativeAuthorization>,
    val eventLogPositiveRules: Set<(args: EventLogRuleArgs<C, V>) -> PositiveAuthorization>,
    val eventLogNegativeRules: Set<(args: EventLogRuleArgs<C, V>) -> NegativeAuthorization>,
    val attachedDataReadPositiveRules: Set<(AttachedDataReadRuleArgs<C, V>) -> PositiveAuthorization> = emptySet(),
    val attachedDataReadNegativeRules: Set<(AttachedDataReadRuleArgs<C, V>) -> NegativeAuthorization> = emptySet(),
    val attachedDataWritePositiveRules: Set<(AttachedDataWriteRuleArgs<C, V>) -> PositiveAuthorization> = emptySet(),
    val attachedDataWriteNegativeRules: Set<(AttachedDataWriteRuleArgs<C, V>) -> NegativeAuthorization> = emptySet(),
    val jobPositiveRules: Set<(JobReadRuleArgs<C, V>) -> PositiveAuthorization> = emptySet(),
    val jobNegativeRules: Set<(JobReadRuleArgs<C, V>) -> NegativeAuthorization> = emptySet(),
)

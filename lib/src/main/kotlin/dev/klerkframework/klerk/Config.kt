package dev.klerkframework.klerk

import com.google.gson.Gson
import dev.klerkframework.klerk.attacheddata.ContentTypeDetector
import dev.klerkframework.klerk.attacheddata.DefaultContentTypeDetector
import dev.klerkframework.klerk.attacheddata.instantiateDeclaration
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.datatypes.BlobContainer
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.propertiesMustInheritFrom
import dev.klerkframework.klerk.job.JobAgent
import dev.klerkframework.klerk.job.JobsBlock
import dev.klerkframework.klerk.job.JobsConfig
import dev.klerkframework.klerk.job.PluginJobsBlock
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.misc.*
import dev.klerkframework.klerk.statemachine.Block
import dev.klerkframework.klerk.statemachine.InstanceState
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransitionWhen
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventTransitionWhen
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.Persistence
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KotlinLogging
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal val logger = KotlinLogging.logger {}

/**
 * The fully-built, immutable configuration of a Klerk instance. Built via [ConfigBuilder.build] (typically through
 * `ConfigBuilder(views).build { ... }`), then passed to [Klerk.Companion.create].
 *
 * Most of the functions on this class are introspection helpers used internally by the framework (validation,
 * the JSON serializer, `klerk-web`/`klerk-graphql`) to look up state machines, events and views by reference.
 * Application code normally doesn't need to call them directly.
 */
public data class Config<C : KlerkContext, V>(
    public val views: V,
    public val authorization: AuthorizationConfig<C, V>,
    public val meterRegistry: MeterRegistry,
    val managedModels: Set<ManagedModel<*, *, C, V>>,
    val persistence: Persistence,
    /**
     * Where the bytes of attached blobs are kept. If null, the config cannot declare any [AttachedBlobID] anywhere.
     */
    val attachedBlobStore: AttachedBlobStore? = null,
    val migrationSteps: SortedSet<MigrationStep>,
    val plugins: List<KlerkPlugin<C, V>> = listOf(),
    val systemContextProvider: ((SystemIdentity) -> C),
    /**
     * Where *background* work gets the current time from: job scheduling, retry backoff, cron, delay-based admission
     * and state-machine time triggers.
     *
     * Actor-driven work reads its time from the caller's [KlerkContext.time] instead and is unaffected by this. The
     * split is deliberate: a test can control actor-driven time simply by constructing a context, and this clock is
     * how it controls everything else. See [dev.klerkframework.klerk.misc.MutableClock].
     */
    val clock: Clock = Clock.System,
    /** The job module's configuration, built by `ConfigBuilder.jobs { ... }`. */
    val jobs: JobsConfig<C, V> = JobsConfig.empty(),
    /**
     * Builds the context a job step runs under. Optional; when absent, [systemContextProvider] is used.
     *
     * Configure it if you use [dev.klerkframework.klerk.job.JobAgent.Scheduler] (which needs a context for an actor
     * other than the system), or if you want a job step's `context.time` to come from [clock] — which is what makes
     * a job's own view of time controllable in tests.
     */
    val jobContextProvider: ((JobContextRequest) -> C)? = null,
) {
    internal lateinit var gson: Gson

    internal fun initialize(): Unit {
        validate()
        gson = createGson(this)
        validateMigrations()
        managedModels.map { it.stateMachine.onKlerkStart(this) }
    }

    /**
     * The current time for background work, at the precision Klerk persists timestamps with (see
     * [makeExactSerializable]), so that a value read here survives a round-trip through storage unchanged.
     */
    internal fun now(): Instant = makeExactSerializable(clock.now())

    private fun validateMigrations() {
        migrationSteps.forEach {
            require(it.migratesToVersion > 1)
            require(it.description.length < 200)
        }
        migrationSteps.fold(1) { acc, migrationStep ->
            require(acc + 1 == migrationStep.migratesToVersion) { "Missing migration to version ${migrationStep.migratesToVersion}" }
            migrationStep.migratesToVersion
        }
    }

    private fun validate() {
        modelsMustHavePropertiesOfDataContainer()
        parametersWithReferencesMustHaveCollectionValidation()
        allEventsMustBeDeclared()
        noTransitionToCurrentState()
        checkContextProviderExistIfConfigContainsTimeTriggers()
        schedulerJobsMustHaveAJobContextProvider()
        attachedBlobStoreMustMatchDeclarations()
        blobContainersMustDeclareAPreAttachStep()
        plugins.forEach { require(!it.name.contains(" ")) { "Plugin name cannot contain space: ${it.name}" } }
    }

    /**
     * A job running as [dev.klerkframework.klerk.job.JobAgent.Scheduler] needs a context for an actor other than the
     * system, and Klerk cannot construct one for an application-defined context type on its own.
     */
    private fun schedulerJobsMustHaveAJobContextProvider() {
        if (jobContextProvider != null) {
            return
        }
        val needsOne = jobs.types.values.filter { it.agent == JobAgent.Scheduler }
        if (needsOne.isEmpty()) {
            return
        }
        throw IllegalConfigurationException(
            KlerkErrorCode.MissingJobContextProvider,
            "The job type(s) ${needsOne.joinToString(", ") { "'${it.name.value}'" }} run as JobAgent.Scheduler, " +
                    "which means their commands are applied as the actor that scheduled them. Klerk therefore needs " +
                    "'jobContextProvider(...)' in the config to build a context for that actor."
        )
    }

    /**
     * Where blob bytes are kept decides what a database backup contains, and Klerk cannot guess it. An application
     * that declares a blob anywhere must say; one that declares none needs no store at all.
     */
    private fun attachedBlobStoreMustMatchDeclarations() {
        val bare = declaredBlobProperties(BlobDeclaration.BareId)
        if (bare.isNotEmpty()) {
            throw IllegalConfigurationException(
                KlerkErrorCode.BlobMustBeDeclaredInAContainer,
                "${bare.sorted().joinToString(", ")} is an AttachedBlobID. Declare a BlobContainer subclass for it " +
                        "instead, the way every other property has a DataContainer:\n\n" +
                        "    class Portrait(id: AttachedBlobID) : BlobContainer(id) {\n" +
                        "        override val accept = setOf(\"image/png\", \"image/jpeg\")\n" +
                        "        override val maxSize = 5_000_000L\n" +
                        "        override val preAttachSteps = listOf(::stripExif)\n" +
                        "    }\n\n" +
                        "That is what says which files are acceptable, how large they may be, and whether they may " +
                        "be read by anyone — and it is checked when a command attaches the file, whichever caller " +
                        "sent it."
            )
        }

        val declarations = declaredBlobProperties(BlobDeclaration.Container)
        if (declarations.isEmpty()) {
            return
        }
        val where = declarations.sorted().joinToString(", ")
        if (attachedBlobStore == null) {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingAttachedBlobStore,
                "$where holds a blob, so 'attachedBlobStore(...)' is required in the config. Choose " +
                        "AttachedBlobStore.Database to keep the bytes in the database, or FileBlobStore(path) to " +
                        "keep them on disk. Pick before you have data: Klerk does not move blobs between stores."
            )
        }
        if (attachedBlobStore == AttachedBlobStore.None) {
            throw IllegalConfigurationException(
                KlerkErrorCode.AttachedBlobStoreIsNone,
                "The config says attachedBlobStore(None), which means this application has no blobs, but $where " +
                        "holds one."
            )
        }
    }

    private enum class BlobDeclaration { BareId, Container }

    /** Descriptions of every place a blob is declared: model properties and event parameters. */
    private fun declaredBlobProperties(kind: BlobDeclaration): List<String> {
        val found = mutableListOf<String>()
        managedModels.forEach { managed ->
            blobPropertyNames(managed.kClass, kind).forEach { found.add("${managed.kClass.simpleName}.$it") }
            managed.stateMachine.mutableStates.flatMap { it.getEvents() }.forEach { event ->
                val parameters = when (event) {
                    is InstanceEventWithParameters<*, *> -> event.parametersClass
                    is VoidEventWithParameters<*, *> -> event.parametersClass
                    else -> null
                }
                parameters?.let { kClass ->
                    blobPropertyNames(kClass, kind).forEach { found.add("${event.id.eventName}.$it") }
                }
            }
        }
        return found.distinct()
    }

    private fun blobPropertyNames(kClass: KClass<*>, kind: BlobDeclaration): List<String> {
        val wanted = when (kind) {
            BlobDeclaration.BareId -> AttachedBlobID::class
            BlobDeclaration.Container -> BlobContainer::class
        }.starProjectedType

        fun matches(type: KType): Boolean {
            val bare = type.withNullability(false)
            // A BlobContainer is not an AttachedBlobID, so the two kinds never match each other.
            return bare.isSubtypeOf(wanted) ||
                    // a List<...> or Set<...> of them
                    (bare.isSubtypeOf(Collection::class.starProjectedType) &&
                            bare.arguments.singleOrNull()?.type?.withNullability(false)?.isSubtypeOf(wanted) == true)
        }

        return kClass.memberProperties.filter { matches(it.returnType) }.map { it.name }
    }

    /**
     * An uploaded file has to be looked at before it is kept, so a [BlobContainer] must declare at least one
     * preAttachStep — [dev.klerkframework.klerk.datatypes.noPreAttachProcessing] if it truly wants none. Checked here,
     * since a property that is only reached from an upload page would otherwise not complain until someone uploads a
     * file.
     */
    private fun blobContainersMustDeclareAPreAttachStep() {
        declaredBlobContainers().forEach { (kClass, where) ->
            val container = try {
                instantiateDeclaration(kClass, AttachedBlobID(0))
            } catch (e: IllegalArgumentException) {
                throw IllegalConfigurationException(
                    KlerkErrorCode.BlobMustBeDeclaredInAContainer,
                    "$where: ${e.message}"
                )
            }
            try {
                container.stepNames
            } catch (e: IllegalArgumentException) {
                throw IllegalConfigurationException(KlerkErrorCode.MissingPreAttachStep, "$where: ${e.message}")
            }
        }
    }

    /** Every [BlobContainer] class a model property or event parameter uses, and where it was found. */
    private fun declaredBlobContainers(): Map<KClass<out BlobContainer>, String> {
        val found = mutableMapOf<KClass<out BlobContainer>, String>()

        fun collect(kClass: KClass<*>, describe: (String) -> String) {
            kClass.memberProperties.forEach { property ->
                val bare = property.returnType.withNullability(false)
                val type = if (bare.isSubtypeOf(Collection::class.starProjectedType)) {
                    bare.arguments.singleOrNull()?.type?.withNullability(false)
                } else {
                    bare
                } ?: return@forEach
                if (!type.isSubtypeOf(BlobContainer::class.starProjectedType)) {
                    return@forEach
                }
                @Suppress("UNCHECKED_CAST")
                val container = type.classifier as? KClass<out BlobContainer> ?: return@forEach
                found.putIfAbsent(container, describe(property.name))
            }
        }

        managedModels.forEach { managed ->
            collect(managed.kClass) { "${managed.kClass.simpleName}.$it" }
            managed.stateMachine.mutableStates.flatMap { it.getEvents() }.forEach { event ->
                val parameters = when (event) {
                    is InstanceEventWithParameters<*, *> -> event.parametersClass
                    is VoidEventWithParameters<*, *> -> event.parametersClass
                    else -> null
                }
                parameters?.let { kClass -> collect(kClass) { "${event.id.eventName}.$it" } }
            }
        }
        return found
    }

    private fun modelsMustHavePropertiesOfDataContainer() {
        managedModels.forEach { managed ->
            checkDataContainerProperties(managed.kClass)
        }
    }

    private fun checkContextProviderExistIfConfigContainsTimeTriggers() {
        // TODO("Not yet implemented")
    }

    /**
     * Checks that all transitions lead to another state
     */
    private fun noTransitionToCurrentState() {
        fun checkBlock(block: Block<*, *, *, *>, state: StateId) {
            val problem = when (block) {
                is Block.InstanceEventBlock<*, *, *, *, *> -> block.executables.any {
                    it is InstanceEventTransition<*, *, *, *, *> && it.targetState.name == state.stateName ||
                            it is InstanceEventTransitionWhen<*, *, *, *, *> && it.branches.any { branch -> branch.value.name == state.stateName }
                }

                is Block.InstanceNonEventBlock -> block.executables.any {
                    it is InstanceNonEventTransition<*, *, *, *> && it.targetState.name == state.stateName ||
                            it is InstanceNonEventTransitionWhen<*, *, *, *> && it.branches.any { branch -> branch.value.name == state.stateName }
                }

                is Block.VoidEventBlock<*, *, *, *, *> -> false         // there can be no transitions in void-states
                is Block.VoidNonEventBlock -> false                     // there can be no transitions in void-states
            }
            check(!problem) { "State ${state.withoutPrefix()} has a transition to itself" }
        }
        managedModels.forEach { managed ->
            managed.stateMachine.mutableStates.forEach { state ->
                checkBlock(state.enterBlock, state.id)
                checkBlock(state.exitBlock, state.id)
                when (state) {
                    is VoidState -> state.onEventBlocks.forEach { checkBlock(it.second, state.id) }
                    is InstanceState -> state.onEventBlocks.forEach { checkBlock(it.second, state.id) }
                }
            }
        }
    }

    /**
     * Makes sure that events has been declared before used in onEvent(). This is important because of two reasons:
     * 1. We want to make explicit all validation rules. If undeclared, it is not visible that the event has no
     * validation.
     * 2. The Event is the container of the declared rules. This is perhaps not optimal, but it means that the Event
     * has state. If the event is not declared, old state may be used, causing unit tests to fail.
     */
    private fun allEventsMustBeDeclared() {
        managedModels.map { it.stateMachine }.forEach { sm ->
            sm.mutableStates.flatMap { state ->
                when (state) {
                    is VoidState -> state.onEventBlocks.map { it.first }
                    is InstanceState -> state.onEventBlocks.map { it.first }
                }
            }
                .forEach {
                    if (!sm.declaredEvents.contains(it)) {
                        throw IllegalConfigurationException(
                            KlerkErrorCode.EventNotDeclared,
                            "The event '${it.id}' must be declared before used in state"
                        )
                    }
                }
        }
    }

    private fun parametersWithReferencesMustHaveCollectionValidation() {
        managedModels.map { it.stateMachine }.forEach { sm ->
            sm.mutableStates.flatMap { state ->
                when (state) {
                    is VoidState -> state.onEventBlocks.map { it.first }
                    is InstanceState -> state.onEventBlocks.map { it.first }
                }
            }
                .forEach { event ->
                    when (event) {
                        is InstanceEventNoParameters -> {}
                        is InstanceEventWithParameters -> checkRefParam(getParameters(event.id), event.validRefs, event)
                        is VoidEventNoParameters -> {}
                        is VoidEventWithParameters -> checkRefParam(getParameters(event.id), event.validRefs, event)
                    }
                }
        }
    }

    private fun checkRefParam(
        params: EventParameters<*>?,
        validRefs: Map<String, ModelView<out Any, *>?>,
        event: Event<*, *>
    ) {
        if (params == null) {
            return
        }
        val refParameters = params.all.filter { it.type == PropertyType.Ref }
        refParameters.firstOrNull { refParam -> !validRefs.containsKey(refParam.name) }?.let {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingValidReferences, """
                The parameter '${it.name}' in '${params.raw.simpleName}' for '$event' contains a property of type Reference, but there is no 'validReferences' declared for that parameter in the state machine.
                E.g. to declare that all references are valid, add this to the state machine for ${params.raw.simpleName}:
                event(${event.name}) {
                    validReferences(${params.raw.simpleName}::${it.name}, views.the-view-of-the-referenced-model.all)
                }
                """.trimIndent()
            )
        }
    }

    /**
     * The model classes registered via `managedModels { model(...) }` when the config was built.
     */
    public fun getManagedClasses(): Set<KClass<out Any>> {
        return managedModels.map { it.kClass }.toSet()
    }

    /**
     * The [ModelViews] registered for the model class [clazz] (i.e. the same instance exposed as a property on [V]).
     *
     * @throws NoSuchElementException if [clazz] is not a managed model
     */
    public fun <T : Any> getView(clazz: KClass<*>): ModelViews<T, C> {
        val mm = managedModels.find { it.kClass == clazz }
            ?: throw NoSuchElementException("Cannot find view for ${clazz.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return mm.collections as ModelViews<T, C>
    }

    /**
     * Returns a list of method names from views that returns lists of ModelId<type>
     */
    internal fun getViewLists(type: KType): List<String> {
        return getViewMembers(type).map { it.name }
    }

    /**
     * Finds the method in any view and calls it.
     */
    internal fun getViewList(type: KType, methodName: String): List<ModelID<Any>> {
        val desiredType = List::class.createType(
            listOf(
                KTypeProjection(
                    type = type.withNullability(false),
                    variance = KVariance.INVARIANT
                )
            )
        )
        val view = managedModels.map { it.collections }.single {
            it::class.members.any { m -> m.name == methodName && m.returnType == desiredType }
        }

        val callable = view::class.members.single { m -> m.name == methodName && m.returnType == desiredType }
        @Suppress("UNCHECKED_CAST")
        return callable.call(view) as List<ModelID<Any>>
    }

    private fun getViewMembers(type: KType): List<KCallable<*>> {
        val desiredType = List::class.createType(
            listOf(
                KTypeProjection(
                    type = type.withNullability(false),
                    variance = KVariance.INVARIANT
                )
            )
        )
        return managedModels.flatMap { mm ->
            mm.collections::class.members.filter { view -> view.returnType == desiredType }
        }
        //   .filter { it.parameters.isEmpty() }
    }

    /**
     * Every [ModelView] declared on any managed model's [ModelViews], paired with the model class it belongs to.
     */
    public fun getCollections(): List<Pair<KClass<out Any>, ModelView<out Any, C>>> {
        return managedModels.flatMap { managed ->
            managed.collections.getCollections().map { Pair(managed.kClass, it) }
        }
    }

    /**
     * Looks up a single [ModelView] by its [CollectionId].
     *
     * @throws NoSuchElementException if no view, or no managed model, matches [id]
     */
    public fun getCollection(id: CollectionId): ModelView<out Any, C> {
        val managed = managedModels.single { it.kClass.simpleName == id.modelName }
        return managed.collections.getCollections().single { it.getFullId() == id }
    }

    /**
     * The [ModelView] declared with `validReferences(...)` for [parameter] of the event [eventReference], or null if
     * the event has no parameters, or none was declared for that parameter (see `validReferences` in
     * [dev.klerkframework.klerk.statemachine.InstanceEventRulesWithParameters] /
     * [dev.klerkframework.klerk.statemachine.VoidEventRulesWithParameters]).
     */
    public fun getValidationCollectionFor(
        eventReference: EventReference,
        parameter: EventParameter
    ): ModelView<out Any, C>? {
        val event = getEvent(eventReference)
        return when (event) {
            is InstanceEventNoParameters -> null
            is InstanceEventWithParameters<*, *> -> event.getValidRefs(parameter.name)
            is VoidEventNoParameters -> null
            is VoidEventWithParameters<*, *> -> event.getValidRefs(parameter.name)
        }
    }

    /**
     * The set of allowed values declared with `validEnums(...)` for [parameter] of the event [eventReference], or
     * null if none was declared (in which case all enum values are allowed).
     */
    public fun getValidEnumsFor(
        eventReference: EventReference,
        parameter: EventParameter
    ): Set<Enum<*>>? {
        val event = getEvent(eventReference)
        return when (event) {
            is InstanceEventNoParameters -> null
            is InstanceEventWithParameters<*, *> -> event.getValidEnums(parameter.name)
            is VoidEventNoParameters -> null
            is VoidEventWithParameters<*, *> -> event.getValidEnums(parameter.name)
        }
    }

    /**
     * Looks up the declared [Event] for [eventId].
     *
     * @throws NoSuchElementException if no managed model declares an event with this reference
     */
    public fun getEvent(eventId: EventReference): Event<Any, Any?> {
        @Suppress("UNCHECKED_CAST")
        return getStateMachine(eventId).mutableStates.flatMap { it.getEvents() }
            .first { it.id == eventId } as Event<Any, Any?>
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
     * The declared parameter class of the event [eventReference], reflectively wrapped as [EventParameters], or null
     * if the event takes no parameters.
     */
    public fun getParameters(eventReference: EventReference): EventParameters<*>? {
        @Suppress("UNCHECKED_CAST")
        return when (val event = getEvent(eventReference)) {
            is InstanceEventNoParameters -> null
            is InstanceEventWithParameters<*, *> -> EventParameters((event as InstanceEventWithParameters<Any, Any>).parametersClass) // is this cast needed?
            is VoidEventNoParameters -> null
            is VoidEventWithParameters<*, *> -> EventParameters((event as VoidEventWithParameters<Any, Any>).parametersClass)
        }
    }

    /**
     * The void events (i.e. events that create a new instance of [clazz]) that [context]'s actor is currently
     * allowed to trigger, restricted to [visibility]. Used by [dev.klerkframework.klerk.read.Reader.getPossibleVoidEvents].
     *
     * @param visibility only [EventVisibility.CODE] and [EventVisibility.EXTERNAL] return results; anything else
     * yields an empty set.
     */
    public fun <T : Any> getPossibleVoidEvents(
        clazz: KClass<T>,
        context: C,
        visibility: EventVisibility = EventVisibility.CODE
    ): Set<EventReference> {
        if (visibility != EventVisibility.CODE && visibility != EventVisibility.EXTERNAL) {
            return emptySet()
        }
        return getStateMachine(clazz).getEventsForVoidState(context, visibility)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any, P> getStateMachineForEvent(event: Event<T, P>): StateMachine<T, out Enum<*>, C, V> =
        getStateMachine(event.id) as StateMachine<T, out Enum<*>, C, V>

    /**
     * The same configuration with a plugin's own job types and crons added, for use from
     * [KlerkPlugin.mergeConfig]:
     *
     * ```kotlin
     * override fun mergeConfig(previous: Config<C, V>): Config<C, V> =
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
    public fun withJobs(init: PluginJobsBlock<C, V>.() -> Unit): Config<C, V> {
        val block = JobsBlock<C, V>()
        block.seedFrom(jobs)
        PluginJobsBlock(block).init()
        return copy(jobs = jobs.with(block.types(), block.crons()))
    }

    public fun withPlugin(plugin: KlerkPlugin<C, V>): Config<C, V> {
        val updatedPlugins = plugins.toMutableList()
        updatedPlugins.add(plugin)
        return plugin.mergeConfig(this).copy(plugins = updatedPlugins)
    }

    /**
     * This exists so that it is possible to use the configuration of the Gson instance.
     * It will probably be removed in the future so don't rely on this.
     */
    @Deprecated("Do not use")
    public fun <T> fromJson(json: String, typeOfT: Class<T>): T = gson.fromJson(json, typeOfT)

    @Deprecated("Do not use")
    public fun toJson(props: Any): String = gson.toJson(props)

}

/**
 * The assembled authorization rule sets, one property per category. Built by [ConfigBuilder.authorization]; not
 * meant to be constructed directly by application code.
 */
public data class AuthorizationConfig<C : KlerkContext, V>(
    val readModelPositiveRules: Set<(ArgModelContextReader<C, V>) -> PositiveAuthorization>,
    val readModelNegativeRules: Set<(ArgModelContextReader<C, V>) -> NegativeAuthorization>,
    val readPropertyPositiveRules: Set<(ArgsForPropertyAuth<C, V>) -> PositiveAuthorization>,
    val readPropertyNegativeRules: Set<(ArgsForPropertyAuth<C, V>) -> NegativeAuthorization>,
    val eventPositiveRules: Set<(ArgCommandContextReader<*, C, V>) -> PositiveAuthorization>,
    val eventNegativeRules: Set<(ArgCommandContextReader<*, C, V>) -> NegativeAuthorization>,
    val eventLogPositiveRules: Set<(args: ArgContextReader<C, V>) -> PositiveAuthorization>,
    val eventLogNegativeRules: Set<(args: ArgContextReader<C, V>) -> NegativeAuthorization>,
    val attachedDataReadPositiveRules: Set<(ArgsForAttachedDataRead<C, V>) -> PositiveAuthorization> = emptySet(),
    val attachedDataReadNegativeRules: Set<(ArgsForAttachedDataRead<C, V>) -> NegativeAuthorization> = emptySet(),
    val attachedDataWritePositiveRules: Set<(ArgsForAttachedDataWrite<C, V>) -> PositiveAuthorization> = emptySet(),
    val attachedDataWriteNegativeRules: Set<(ArgsForAttachedDataWrite<C, V>) -> NegativeAuthorization> = emptySet(),
    val jobPositiveRules: Set<(ArgsForJobRead<C, V>) -> PositiveAuthorization> = emptySet(),
    val jobNegativeRules: Set<(ArgsForJobRead<C, V>) -> NegativeAuthorization> = emptySet(),
)

@DslMarker
internal annotation class ConfigMarker

/**
 * DSL entry point for building a [Config]. Typical usage:
 * ```
 * val config = ConfigBuilder<MyContext, MyViews>(views).build {
 *     persistence(...)
 *     systemContextProvider { SystemIdentity -> ... }
 *     managedModels { model(MyModel::class, myModelStateMachine, myModelViews) }
 *     authorization { ... }
 * }
 * ```
 * [persistence], [systemContextProvider], [managedModels] and [authorization] are all required; [build] throws
 * [IllegalConfigurationException] if any is missing. [migrations] and [micrometerRegistry] are optional.
 */
@ConfigMarker
public class ConfigBuilder<C : KlerkContext, V>(private val views: V) {

    /**
     * Runs [init] against this builder and assembles the resulting [Config].
     *
     * @throws IllegalConfigurationException if [persistence], [systemContextProvider], [authorization] or
     * [managedModels] was not called inside [init]
     */
    public fun build(init: ConfigBuilder<C, V>.() -> Unit): Config<C, V> {
        this.init()
        runCatching { systemContextProviderValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingSystemContextProvider,
                "'systemContextProvider' is missing in the config"
            )
        }
        runCatching { persistenceValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingPersistence,
                "'persistence' is missing in the config"
            )
        }
        runCatching { authorizationRulesBlock }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingAuthorization,
                "'authorization' is missing in the config"
            )
        }
        runCatching { managedModelsValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingManagedModels,
                "'managedModels' is missing in the config"
            )
        }

        //val valueClasses = managedModelsValue.flatMap { managed -> managed.kClass.memberProperties.map { (it.returnType.classifier!! as KClass<*>) } }.toSet()


        /*            .mapNotNull { it.parameters?.raw }
                    .mapNotNull { it.primaryConstructor?.parameters}
                    .flatMap { it }
                    .map { (it.type.classifier!! as KClass<*>) }
                    .toSet()

         */

        return Config(
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
            meterRegistry = registry,
            managedModels = managedModelsValue,
            persistence = persistenceValue,
            attachedBlobStore = attachedBlobStoreValue,
            migrationSteps = migrationStepsValue,
            systemContextProvider = systemContextProviderValue,
            clock = clockValue,
            jobs = jobsValue,
            jobContextProvider = jobContextProviderValue,
        )
    }

    private var migrationStepsValue: SortedSet<MigrationStep> = sortedSetOf()
    private var registry: MeterRegistry = SimpleMeterRegistry()
    private var clockValue: Clock = Clock.System
    private var jobsValue: JobsConfig<C, V> = JobsConfig.empty()
    private var jobContextProviderValue: ((JobContextRequest) -> C)? = null
    private lateinit var authorizationRulesBlock: AuthorizationRulesBlock<C, V>
    private lateinit var managedModelsValue: Set<ManagedModel<*, *, C, V>>
    private lateinit var persistenceValue: Persistence
    private var attachedBlobStoreValue: AttachedBlobStore? = null
    private lateinit var systemContextProviderValue: ((SystemIdentity) -> C)

    /**
     * The storage backend to use (e.g. [dev.klerkframework.klerk.storage.SqlPersistence]). Required.
     */
    public fun persistence(persistence: Persistence) {
        persistenceValue = persistence
    }

    /**
     * Where the bytes of attached blobs are kept: [AttachedBlobStore.Database],
     * [dev.klerkframework.klerk.storage.FileBlobStore] or [AttachedBlobStore.None].
     *
     * Required as soon as any model property or event parameter is an [AttachedBlobID] — the choice decides what a
     * database backup contains, so Klerk will not pick one for you. Attached *strings* are unaffected; they always
     * live in the database.
     *
     * Choose before the application has data: Klerk does not move blobs between stores, and refuses to start if the
     * configured store does not have the bytes it expects.
     */
    public fun attachedBlobStore(store: AttachedBlobStore) {
        attachedBlobStoreValue = store
    }

    /**
     * Registers the [MigrationStep]s used to evolve model shapes across schema versions. Steps are reordered by
     * [MigrationStep.migratesToVersion]; [Config] additionally requires them to form a contiguous chain starting at
     * version 2 (version 1 is implicit). Optional — omit if the schema has never changed.
     */
    public fun migrations(migrationSteps: Set<MigrationStep>) {
        migrationStepsValue =
            sortedSetOf(Comparator.comparingInt(MigrationStep::migratesToVersion), *migrationSteps.toTypedArray())
    }

    /**
     * Klerk may execute commands in the background (e.g. if you have a statemachine with after(5.minutes) {...}). And
     * since a context is required when executing a command, Klerk needs a way to create such a context with the
     * SystemIdentity.
     */
    public fun systemContextProvider(provider: (SystemIdentity) -> C) {
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
     * [AuthorizationRulesBlock] for the sub-blocks, or [AuthorizationRulesBlock.insecureAllowEverything] to opt out
     * of authorization entirely (development/testing only).
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
     * The clock background work reads the time from — job scheduling, retry backoff, cron, delay-based admission and
     * state-machine time triggers. Optional; defaults to [Clock.System].
     *
     * Set it to a [dev.klerkframework.klerk.misc.MutableClock] in tests to make everything time-dependent
     * deterministic. Note that it does *not* affect the time seen by commands and reads, which comes from the caller's
     * [KlerkContext.time].
     */
    public fun clock(clock: Clock) {
        clockValue = clock
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
     */
    public fun jobContextProvider(provider: (JobContextRequest) -> C) {
        jobContextProviderValue = provider
    }

    @ConfigMarker
    public class ManagedModelsBlock<C : KlerkContext, V> {

        internal val value = mutableSetOf<ManagedModel<*, *, C, V>>()

        /**
         * Registers [clazz] as a managed model with its [stateMachine] and [view] (the [ModelViews] holding its
         * collections). [clazz] must be a data class with only `val` properties, each of a [DataContainer] type (or
         * a collection thereof); every managed model's simple name must be unique within the config.
         *
         * @throws IllegalArgumentException if [clazz] has a `var` property or a property that isn't a [DataContainer]
         * @throws IllegalArgumentException if another managed model already has the same simple name
         */
        public fun <T : Any, ModelStates : Enum<*>> model(
            clazz: KClass<T>,
            stateMachine: StateMachine<T, ModelStates, C, V>,
            view: ModelViews<T, C>
        ) {
            validateModelClass(clazz)
            require(!value.map { it.kClass.simpleName!! }
                .contains(clazz.simpleName!!)) { "Managed models must have unique simpleNames ('${clazz.simpleName!!}' is not unique)" }
            value.add(ManagedModel(clazz, stateMachine, view))
        }

    }

    /**
     * The six independent authorization categories, each with a `positive { rule(...) }` / `negative { rule(...) }`
     * pair. A category with no rules denies everything in it. See the "Authorization" doc for how positive/negative
     * rules combine, or [insecureAllowEverything] to disable authorization for development.
     */
    @ConfigMarker
    public class AuthorizationRulesBlock<C : KlerkContext, V> {

        internal val readModelPositiveRules =
            mutableSetOf<(ArgModelContextReader<C, V>) -> PositiveAuthorization>()
        internal val readModelNegativeRules =
            mutableSetOf<(ArgModelContextReader<C, V>) -> NegativeAuthorization>()
        internal val readPropertyPositiveRules =
            mutableSetOf<(ArgsForPropertyAuth<C, V>) -> PositiveAuthorization>()
        internal val readPropertyNegativeRules =
            mutableSetOf<(ArgsForPropertyAuth<C, V>) -> NegativeAuthorization>()
        internal val eventPositiveRules =
            mutableSetOf<(ArgCommandContextReader<*, C, V>) -> PositiveAuthorization>()
        internal val eventNegativeRules =
            mutableSetOf<(ArgCommandContextReader<*, C, V>) -> NegativeAuthorization>()
        internal val eventLogPositiveRules =
            mutableSetOf<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val eventLogNegativeRules =
            mutableSetOf<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()
        internal val attachedDataReadPositiveRules =
            mutableSetOf<(ArgsForAttachedDataRead<C, V>) -> PositiveAuthorization>()
        internal val attachedDataReadNegativeRules =
            mutableSetOf<(ArgsForAttachedDataRead<C, V>) -> NegativeAuthorization>()
        internal val attachedDataWritePositiveRules =
            mutableSetOf<(ArgsForAttachedDataWrite<C, V>) -> PositiveAuthorization>()
        internal val attachedDataWriteNegativeRules =
            mutableSetOf<(ArgsForAttachedDataWrite<C, V>) -> NegativeAuthorization>()
        internal val jobPositiveRules = mutableSetOf<(ArgsForJobRead<C, V>) -> PositiveAuthorization>()
        internal val jobNegativeRules = mutableSetOf<(ArgsForJobRead<C, V>) -> NegativeAuthorization>()


        /**
         * Rules deciding who may read a model as a whole (returned by `get`/`list`/views).
         */
        public fun readModels(init: AuthorizationReadRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationReadRulesBlock<C, V>()
            block.init()
            readModelPositiveRules.addAll(block.positiveBlock.rules)
            readModelNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may read individual model properties. Evaluated per property, independently of [readModels].
         */
        public fun readProperties(init: AuthorizationReadPropertiesRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationReadPropertiesRulesBlock<C, V>()
            block.init()
            readPropertyPositiveRules.addAll(block.positiveBlock.rules)
            readPropertyNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may trigger which events/commands, i.e. who is allowed to call [Klerk.handle] for a
         * given command.
         */
        public fun commands(init: AuthorizationEventsRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationEventsRulesBlock<C, V>()
            block.init()
            eventPositiveRules.addAll(block.positiveBlock.rules)
            eventNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may read the audit log, i.e. [EventsManager.getEventsInAuditLog].
         */
        public fun eventLog(init: AuthorizationEventLogRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationEventLogRulesBlock<C, V>()
            block.init()
            eventLogPositiveRules.addAll(block.positiveBlock.rules)
            eventLogNegativeRules.addAll(block.negativeBlock.rules)
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
        public fun readAttachedData(init: AuthorizationAttachedDataReadRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationAttachedDataReadRulesBlock<C, V>()
            block.init()
            attachedDataReadPositiveRules.addAll(block.positiveBlock.rules)
            attachedDataReadNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may prepare attached data, i.e. `klerk.attachedData.prepare(...)`.
         *
         * Note that this is weak by construction: at that point there is no model and no command. The real gate on
         * *attaching* data to a model is the normal event authorization of the command that claims it. What these
         * rules are good at is the visibility: they can let anyone upload while allowing only some actors to publish
         * something the whole world may read. See [dev.klerkframework.klerk.KlerkAttachedData].
         */
        public fun writeAttachedData(init: AuthorizationAttachedDataWriteRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationAttachedDataWriteRulesBlock<C, V>()
            block.init()
            attachedDataWritePositiveRules.addAll(block.positiveBlock.rules)
            attachedDataWriteNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may see a job's metadata — its status, progress and log — via
         * [JobManager.getJob]/[JobManager.getAllJobs]/[JobManager.subscribe].
         *
         * The same rules gate [JobManager.cancel], so a user who can watch their own progress bar can also cancel
         * their own job. [ArgsForJobRead.isOwnedBy] answers "did this actor schedule it?".
         */
        public fun jobs(init: AuthorizationJobRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationJobRulesBlock<C, V>()
            block.init()
            jobPositiveRules.addAll(block.positiveBlock.rules)
            jobNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Returns an `init` block for [ConfigBuilder.authorization] that allows every actor to do everything (read
         * all models/properties/event log/attached data, trigger all commands). Logs a warning when applied.
         *
         * For development/testing only — never use in production.
         */
        public fun insecureAllowEverything(): AuthorizationRulesBlock<C, V>.() -> Unit = {
            logger.warn { "The authorization rules allows everything. The application is insecure!" }
            readModels {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadModels)
                }
                negative {
                }
            }

            readProperties {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadAllProperties)
                }
                negative {
                }
            }
            commands {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanDoEverything)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadEventLog)
                }
                negative {}
            }
            readAttachedData {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadAllAttachedData)
                }
                negative {}
            }
            writeAttachedData {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanWriteAttachedData)
                }
                negative {}
            }
            jobs {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanSeeAllJobs)
                }
                negative {}
            }
        }

        private fun everybodyCanSeeAllJobs(args: ArgsForJobRead<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadModels(args: ArgModelContextReader<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadAllAttachedData(args: ArgsForAttachedDataRead<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanWriteAttachedData(args: ArgsForAttachedDataWrite<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadAllProperties(args: ArgsForPropertyAuth<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanDoEverything(args: ArgCommandContextReader<*, C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadEventLog(args: ArgContextReader<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

    }


    @ConfigMarker
    public class AuthorizationReadRulesBlock<C : KlerkContext, V> {

        internal lateinit var positiveBlock: AuthorizationReadPositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationReadNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationReadPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationReadPositiveRulesBlock<C, V>()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationReadNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationReadNegativeRulesBlock()
            negativeBlock.init()
        }

    }

    @ConfigMarker
    public class AuthorizationReadPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationReadNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    // properties
    @ConfigMarker
    public class AuthorizationReadPropertiesRulesBlock<C : KlerkContext, V> {

        internal lateinit var positiveBlock: AuthorizationReadPropertyPositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationReadPropertyNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationReadPropertyPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationReadPropertyPositiveRulesBlock<C, V>()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationReadPropertyNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationReadPropertyNegativeRulesBlock()
            negativeBlock.init()
        }

    }

    @ConfigMarker
    public class AuthorizationReadPropertyPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationReadPropertyNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    // attached data
    @ConfigMarker
    public class AuthorizationAttachedDataReadRulesBlock<C : KlerkContext, V> {

        internal lateinit var positiveBlock: AuthorizationAttachedDataReadPositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationAttachedDataReadNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationAttachedDataReadPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationAttachedDataReadPositiveRulesBlock()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationAttachedDataReadNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationAttachedDataReadNegativeRulesBlock()
            negativeBlock.init()
        }
    }

    @ConfigMarker
    public class AuthorizationAttachedDataReadPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(ArgsForAttachedDataRead<C, V>) -> PositiveAuthorization>()

        public fun rule(function: (ArgsForAttachedDataRead<C, V>) -> PositiveAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationAttachedDataReadNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(ArgsForAttachedDataRead<C, V>) -> NegativeAuthorization>()

        public fun rule(function: (ArgsForAttachedDataRead<C, V>) -> NegativeAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationAttachedDataWriteRulesBlock<C : KlerkContext, V> {

        internal lateinit var positiveBlock: AuthorizationAttachedDataWritePositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationAttachedDataWriteNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationAttachedDataWritePositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationAttachedDataWritePositiveRulesBlock()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationAttachedDataWriteNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationAttachedDataWriteNegativeRulesBlock()
            negativeBlock.init()
        }
    }

    @ConfigMarker
    public class AuthorizationAttachedDataWritePositiveRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(ArgsForAttachedDataWrite<C, V>) -> PositiveAuthorization>()

        public fun rule(function: (ArgsForAttachedDataWrite<C, V>) -> PositiveAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationAttachedDataWriteNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(ArgsForAttachedDataWrite<C, V>) -> NegativeAuthorization>()

        public fun rule(function: (ArgsForAttachedDataWrite<C, V>) -> NegativeAuthorization) {
            rules.add(function)
        }
    }

    // jobs
    @ConfigMarker
    public class AuthorizationJobRulesBlock<C : KlerkContext, V> {

        internal lateinit var positiveBlock: AuthorizationJobPositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationJobNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationJobPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationJobPositiveRulesBlock()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationJobNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationJobNegativeRulesBlock()
            negativeBlock.init()
        }
    }

    @ConfigMarker
    public class AuthorizationJobPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(ArgsForJobRead<C, V>) -> PositiveAuthorization>()

        public fun rule(function: (ArgsForJobRead<C, V>) -> PositiveAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationJobNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(ArgsForJobRead<C, V>) -> NegativeAuthorization>()

        public fun rule(function: (ArgsForJobRead<C, V>) -> NegativeAuthorization) {
            rules.add(function)
        }
    }

    // events
    @ConfigMarker
    public class AuthorizationEventsRulesBlock<C : KlerkContext, V> {
        internal lateinit var positiveBlock: AuthorizationEventsPositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationEventsNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationEventsPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationEventsPositiveRulesBlock()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationEventsNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationEventsNegativeRulesBlock()
            negativeBlock.init()
        }
    }

    @ConfigMarker
    public class AuthorizationEventsPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (args: ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }

    }

    @ConfigMarker
    public class AuthorizationEventsNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationEventLogRulesBlock<C : KlerkContext, V> {
        internal lateinit var positiveBlock: AuthorizationEventLogPositiveRulesBlock<C, V>
        internal lateinit var negativeBlock: AuthorizationEventLogNegativeRulesBlock<C, V>

        public fun positive(init: AuthorizationEventLogPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock = AuthorizationEventLogPositiveRulesBlock()
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationEventLogNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock = AuthorizationEventLogNegativeRulesBlock()
            negativeBlock.init()
        }
    }

    @ConfigMarker
    public class AuthorizationEventLogPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }
    }

    @ConfigMarker
    public class AuthorizationEventLogNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    /**
     * The [MeterRegistry] Klerk publishes metrics to. Optional — defaults to a private [SimpleMeterRegistry] that
     * isn't exported anywhere, so set this to integrate with your application's metrics backend.
     */
    public fun micrometerRegistry(registry: MeterRegistry) {
        this.registry = registry
    }

}

private fun <T : Any> validateModelClass(clazz: KClass<T>) {
    require(clazz.isData)
    val problematic = clazz.memberProperties.filterIsInstance<KMutableProperty<*>>()
    if (problematic.isNotEmpty()) {
        throw IllegalArgumentException(
            "Properties in models must be immutable. Change var -> val for ${
                problematic.map { "'${it.name}'" }.joinToString(", ")
            } in ${clazz.qualifiedName}"
        )
    }

    clazz.memberProperties.forEach { prop ->
        require(prop.returnType.toString() != DataContainer::class.qualifiedName)
        {
            "Illegal property: ${clazz.simpleName}.${prop.name} is ${prop.returnType}. The properties in your model should inherit from (or be a collection of) ${
                propertiesMustInheritFrom.map { it.simpleName }.joinToString(", ")
            }"
        }
        require(propertiesMustInheritFrom.none { it.qualifiedName == prop.returnType.toString() })
        {
            "Illegal property: ${clazz.simpleName}.${prop.name} is ${prop.returnType}. The properties in your model should inherit from (or be a collection of) ${
                propertiesMustInheritFrom.map { it.simpleName }.joinToString(", ")
            }"
        }

    }

    // do we also need to check so that the user provided value classes are completely immutable?
}

/**
 * Runtime settings for a [Klerk] instance, passed to [Klerk.Companion.create] alongside [Config].
 */
public data class KlerkSettings(

    /**
     * Only `null` (never erase, the default) and [Duration.ZERO] (erase immediately on model deletion) are
     * currently supported; any other value is rejected on startup.
     */
    val eraseAuditLogAfterModelDeletion: Duration? = null,

    /**
     * Gates the "escape hatch" functions on [KlerkModels] ([KlerkModels.unsafeCreate], [KlerkModels.unsafeUpdate],
     * [KlerkModels.unsafeDelete]), which bypass the state machine, validation and authorization entirely. Off by
     * default; enable only if you understand the risk.
     */
    val allowUnsafeOperations: Boolean = false,

    /**
     * How long attached data that has been prepared but not yet claimed by a command survives (see
     * [KlerkAttachedData.prepare]). Mainly here so that tests don't have to wait a minute.
     */
    val unclaimedAttachedDataLifetime: Duration = 1.minutes,

    /**
     * The longest lease [KlerkAttachedData.prepare] will grant. A lease keeps storage occupied by data that no model
     * refers to, so there is an upper bound; who may ask for a long one is decided by the `writeAttachedData` rules.
     */
    val maxAttachedDataLease: Duration = 24.hours,

    /**
     * How Klerk recognises the content type of an attached value from its first bytes (see
     * [dev.klerkframework.klerk.attacheddata.ContentTypeDetector]). Defaults to
     * [dev.klerkframework.klerk.attacheddata.DefaultContentTypeDetector], a small dependency-free set of magic-byte
     * signatures; replace it to recognise more formats, e.g. with a detector backed by Apache Tika.
     */
    val contentTypeDetector: ContentTypeDetector = DefaultContentTypeDetector,
)

/**
 * Converts a camelCase identifier (e.g. a validation rule or function name) to a human-readable phrase, e.g.
 * `"mustBeEven"` -> `"Must be even"`. Not currently wired into [DefaultKlerkTranslation] or any other framework code.
 */
public fun defaultTranslator(rule: String): String {
    return camelCaseToPretty(rule)
}

package dev.klerkframework.klerk

import com.google.gson.Gson
import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.datatypes.propertiesMustInheritFrom
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.misc.*
import dev.klerkframework.klerk.statemachine.*
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransitionWhen
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventTransitionWhen
import dev.klerkframework.klerk.storage.Persistence
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KotlinLogging
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.time.Duration

internal val logger = KotlinLogging.logger {}

public data class Config<C : KlerkContext, V>(
    internal val collections: V,
    public val authorization: AuthorizationConfig<C, V>,
    val meterRegistry: MeterRegistry,
    val managedModels: Set<ManagedModel<*, *, C, V>>,
    val persistence: Persistence,
    val migrationSteps: SortedSet<MigrationStep>,
    val plugins: List<KlerkPlugin<C, V>> = listOf(),
    val contextProvider: ((ActorIdentity) -> C)?,
) {
    lateinit var gson: Gson

    internal fun initialize(): Unit {
        validate()
        gson = createGson(this)
        validateMigrations()
        managedModels.map { it.stateMachine.onKlerkStart(this) }
    }

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
        parametersWithReferencesMustHaveCollectionValidation()
        allEventsMustBeDeclared()
        noTransitionToCurrentState()
        checkContextProviderExistIfConfigContainsTimeTriggers()
        plugins.forEach { require(!it.name.contains(" ")) { "Plugin name cannot contain space: ${it.name}" } }
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
            managed.stateMachine.states.forEach { state ->
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
            sm.states.flatMap { state ->
                when (state) {
                    is VoidState -> state.onEventBlocks.map { it.first }
                    is InstanceState -> state.onEventBlocks.map { it.first }
                }
            }
                .forEach {
                    if (!sm.declaredEvents.contains(it)) {
                        throw IllegalConfigurationException("The event '${it.id}' must be declared before used in state")
                    }
                }
        }
    }

    private fun parametersWithReferencesMustHaveCollectionValidation() {
        managedModels.map { it.stateMachine }.forEach { sm ->
            sm.states.flatMap { state ->
                when (state) {
                    is VoidState -> state.onEventBlocks.map { it.first }
                    is InstanceState -> state.onEventBlocks.map { it.first }
                    else -> error("") // should use sealed class...
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
        validRefs: Map<String, ModelCollection<out Any, *>?>,
        event: Event<*, *>
    ) {
        if (params == null) {
            return
        }
        val refParameters = params.all.filter { it.type == PropertyType.Ref }
        refParameters.firstOrNull { refParam -> !validRefs.containsKey(refParam.name) }?.let {
            throw IllegalConfigurationException("The parameter '${it.name}' in '${params.raw.simpleName}' for '$event' contains a property of type Reference, but there is no 'validReferences' declared for that parameter in the state machine.")
        }
    }

    internal fun getManagedClasses(): Set<KClass<out Any>> {
        return managedModels.map { it.kClass }.toSet()
    }

    internal fun <T : Any> getView(clazz: KClass<*>): ModelCollections<T, C> {
        val mm = managedModels.find { it.kClass == clazz }
            ?: throw NoSuchElementException("Cannot find view for ${clazz.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return mm.collections as ModelCollections<T, C>
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

    internal fun getCollections(): List<Pair<KClass<out Any>, ModelCollection<out Any, C>>> {
        return managedModels.flatMap { managed ->
            managed.collections.getCollections().map { Pair(managed.kClass, it) }
        }
    }

    internal fun getCollection(id: CollectionId): ModelCollection<out Any, C> {
        val managed = managedModels.single { it.kClass.simpleName == id.modelName }
        return managed.collections.getCollections().single { it.getFullId() == id }
    }

    internal fun getValidationCollectionFor(
        eventReference: EventReference,
        parameter: EventParameter
    ): ModelCollection<out Any, C>? {
        val event = getEvent(eventReference)
        return when (event) {
            is InstanceEventNoParameters -> null
            is InstanceEventWithParameters<*, *> -> event.getValidRefs(parameter.name)
            is VoidEventNoParameters -> null
            is VoidEventWithParameters<*, *> -> event.getValidRefs(parameter.name)
        }
    }

    internal fun getEvent(eventId: EventReference): Event<Any, Any?> {
        @Suppress("UNCHECKED_CAST")
        return getStateMachine(eventId).states.flatMap { it.getEvents() }
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

    internal fun getParameters(eventReference: EventReference): EventParameters<*>? {
        @Suppress("UNCHECKED_CAST")
        return when (val event = getEvent(eventReference)) {
            is InstanceEventNoParameters -> null
            is InstanceEventWithParameters<*, *> -> EventParameters((event as InstanceEventWithParameters<Any, Any>).parametersClass) // is this cast needed?
            is VoidEventNoParameters -> null
            is VoidEventWithParameters<*, *> -> EventParameters((event as VoidEventWithParameters<Any, Any>).parametersClass)
        }
    }

    internal fun <T : Any> getPossibleVoidEvents(clazz: KClass<T>, context: C): Set<EventReference> {
        return getStateMachine(clazz).getExternalEventsForVoidState(context)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any, P> getStateMachineForEvent(event: Event<T, P>): StateMachine<T, out Enum<*>, C, V> =
        getStateMachine(event.id) as StateMachine<T, out Enum<*>, C, V>

    public fun withPlugin(plugin: KlerkPlugin<C, V>): Config<C, V> {
        val updatedPlugins = plugins.toMutableList()
        updatedPlugins.add(plugin)
        return plugin.mergeConfig(this).copy(plugins = updatedPlugins)
    }

}

public data class AuthorizationConfig<C : KlerkContext, V>(
    val readModelPositiveRules: Set<(ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>,
    val readModelNegativeRules: Set<(ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>,
    val readPropertyPositiveRules: Set<(ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>,
    val readPropertyNegativeRules: Set<(ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>,
    val eventPositiveRules: Set<(ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>,
    val eventNegativeRules: Set<(ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>,
    val eventLogPositiveRules: Set<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>,
    val eventLogNegativeRules: Set<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>
)

@DslMarker
internal annotation class ConfigMarker

@ConfigMarker
public class ConfigBuilder<C : KlerkContext, D>(private val dataValue: D) {

    /**
     * @throws IllegalConfigurationException
     */
    public fun build(init: ConfigBuilder<C, D>.() -> Unit): Config<C, D> {
        this.init()


        //val valueClasses = managedModelsValue.flatMap { managed -> managed.kClass.memberProperties.map { (it.returnType.classifier!! as KClass<*>) } }.toSet()


        /*            .mapNotNull { it.parameters?.raw }
                    .mapNotNull { it.primaryConstructor?.parameters}
                    .flatMap { it }
                    .map { (it.type.classifier!! as KClass<*>) }
                    .toSet()

         */

        return Config(
            collections = dataValue,
            authorization = AuthorizationConfig(
                readModelPositiveRules = authorizationRulesBlock.readModelPositiveRules,
                readModelNegativeRules = authorizationRulesBlock.readModelNegativeRules,
                readPropertyPositiveRules = authorizationRulesBlock.readPropertyPositiveRules,
                readPropertyNegativeRules = authorizationRulesBlock.readPropertyNegativeRules,
                eventPositiveRules = authorizationRulesBlock.eventPositiveRules,
                eventNegativeRules = authorizationRulesBlock.eventNegativeRules,
                eventLogPositiveRules = authorizationRulesBlock.eventLogPositiveRules,
                eventLogNegativeRules = authorizationRulesBlock.eventLogNegativeRules
            ),
            meterRegistry = registry,
            managedModels = managedModelsValue,
            persistence = persistenceValue,
            migrationSteps = migrationStepsValue,
            contextProvider = contextProviderValue,
        )
    }

    private var migrationStepsValue: SortedSet<MigrationStep> = sortedSetOf()
    private var registry: MeterRegistry = SimpleMeterRegistry()
    private lateinit var authorizationRulesBlock: AuthorizationRulesBlock<C, D>
    private lateinit var managedModelsValue: Set<ManagedModel<*, *, C, D>>

    private lateinit var persistenceValue: Persistence
    private var contextProviderValue: ((dev.klerkframework.klerk.ActorIdentity) -> C)? = null

    public fun persistence(persistence: Persistence) {
        persistenceValue = persistence
    }

    public fun migrations(migrationSteps: Set<MigrationStep>) {
        migrationStepsValue =
            sortedSetOf(Comparator.comparingInt(MigrationStep::migratesToVersion), *migrationSteps.toTypedArray())
    }

    public fun contextProvider(provider: (dev.klerkframework.klerk.ActorIdentity) -> C) {
        contextProviderValue = provider
    }

    public fun managedModels(init: ManagedModelsBlock<C, D>.() -> Unit) {
        val block = ManagedModelsBlock<C, D>()
        block.init()
        managedModelsValue = block.value
    }

    public fun authorization(init: AuthorizationRulesBlock<C, D>.() -> Unit) {
        authorizationRulesBlock = AuthorizationRulesBlock<C, D>()
        authorizationRulesBlock.init()
    }

    @ConfigMarker
    public class ManagedModelsBlock<C : KlerkContext, V> {

        internal val value = mutableSetOf<ManagedModel<*, *, C, V>>()

        public fun <T : Any, ModelStates : Enum<*>> model(
            clazz: KClass<T>,
            stateMachine: StateMachine<T, ModelStates, C, V>,
            view: ModelCollections<T, C>
        ) {
            validateModelClass(clazz)
            require(!value.map { it.kClass.simpleName!! }
                .contains(clazz.simpleName!!)) { "Managed models must have unique simpleNames ('${clazz.simpleName!!}' is not unique)" }
            value.add(ManagedModel(clazz, stateMachine, view))
        }

    }

    @ConfigMarker
    public class AuthorizationRulesBlock<C : KlerkContext, V> {

        internal val readModelPositiveRules =
            mutableSetOf<(ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val readModelNegativeRules =
            mutableSetOf<(ArgModelContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()
        internal val readPropertyPositiveRules =
            mutableSetOf<(ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val readPropertyNegativeRules =
            mutableSetOf<(ArgsForPropertyAuth<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()
        internal val eventPositiveRules =
            mutableSetOf<(ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val eventNegativeRules =
            mutableSetOf<(ArgCommandContextReader<*, C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()
        internal val eventLogPositiveRules =
            mutableSetOf<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val eventLogNegativeRules =
            mutableSetOf<(args: ArgContextReader<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()


        public fun readModels(init: AuthorizationReadRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationReadRulesBlock<C, V>()
            block.init()
            readModelPositiveRules.addAll(block.positiveBlock.rules)
            readModelNegativeRules.addAll(block.negativeBlock.rules)
        }

        public fun readProperties(init: AuthorizationReadPropertiesRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationReadPropertiesRulesBlock<C, V>()
            block.init()
            readPropertyPositiveRules.addAll(block.positiveBlock.rules)
            readPropertyNegativeRules.addAll(block.negativeBlock.rules)
        }

        public fun commands(init: AuthorizationEventsRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationEventsRulesBlock<C, V>()
            block.init()
            eventPositiveRules.addAll(block.positiveBlock.rules)
            eventNegativeRules.addAll(block.negativeBlock.rules)
        }

        public fun eventLog(init: AuthorizationEventLogRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationEventLogRulesBlock<C, V>()
            block.init()
            eventLogPositiveRules.addAll(block.positiveBlock.rules)
            eventLogNegativeRules.addAll(block.negativeBlock.rules)
        }
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

public data class KlerkSettings(
    val requireEventParamsValidation: Boolean = true,
    val requireAnyValidation: Boolean = true,
    val eraseAuditLogAfterModelDeletion: Duration? = null,
    val allowUnsafeOperations: Boolean = false,
)

public fun defaultTranslator(rule: String): String {
    return camelCaseToPretty(rule)
}

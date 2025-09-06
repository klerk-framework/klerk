package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command

import dev.klerkframework.klerk.storage.ModelCache
import mu.KotlinLogging
import kotlin.reflect.KClass

@ConfigMarker
public class StateMachine<T : Any, ModelStates : Enum<*>, C:KlerkContext, V>(
    internal val type: KClass<T>
) {

    private val log = KotlinLogging.logger {}

    internal lateinit var modelViews: ModelViews<T, C>
    private lateinit var currentState: State<T, ModelStates, C, V>
    public val states: MutableList<State<T, ModelStates, C, V>> = mutableListOf<State<T, ModelStates, C, V>>()
    public lateinit var voidState: VoidState<T, ModelStates, C, V>
    public val instanceStates: List<InstanceState<T, ModelStates, C, V>>
        get() = states.filterIsInstance<InstanceState<T, ModelStates, C, V>>()

    internal val declaredEvents = mutableListOf<Event<T, *>>()

    // internal lateinit var externalEvents: ExternalEvents<V, T>

    internal fun setView(view: ModelViews<*, C>) {
        @Suppress("UNCHECKED_CAST")
        modelViews = view as ModelViews<T, C>
    }

    internal fun getStateByName(name: String?): State<T, ModelStates, C, V> {
        if (name == null) {
            return voidState
        }
        return states.firstOrNull { it.name == name }
            ?: throw InternalException("State $name doesn't exist in statemachine for ${this.type.simpleName}. Do you need to migrate the data?")
    }

    internal fun knowsAboutEvent(eventReference: EventReference): Boolean {
        if (eventReference.modelName != type.simpleName) {
            return false
        }
        try {
            return getAllStates().any { state -> state.canHandle(eventReference) }
        } catch (e: NoSuchElementException) {
            return false
        }
    }

    /**
     * Checks if the state machine is currently in a state where it can handle the event.
     */
    internal fun <P> canHandle(command: Command<T, P>): Problem? {
        val state = getState(command.model)
        if (state.canHandle(command.event.id)) {
            return null
        }
        return StateProblem(
            "The statemachine for '${type.simpleName}' for modelId=${command.model} is in state '${state.name}' and cannot handle the event '${command.event}'."
        )
    }

    /**
     * Note that this will use the id to look in ModelRamStore. If ModelRamStore hasn't been updated yet, you may get
     * the wrong state. Perhaps use the other getState function instead?
     */
    private fun getState(id: ModelID<T>?): State<T, ModelStates, C, V> {
        if (id == null) {
            return voidState
        }
        val model = ModelCache.read(id).getOrThrow()
        return getStateByModel(model)
    }

    internal fun getStateByModel(model: Model<T>?): State<T, ModelStates, C, V> {
        if (model == null) {
            return voidState
        }
        return states.find { it.name == model.state }
            ?: throw IllegalStateException(
                "The statemachine has not defined the state '${model.state}'. The defined available are: ${
                    states.joinToString(
                        ","
                    ) { it.name }
                }"
            )
    }

    private fun getAllStates(): Set<State<T, ModelStates, C, V>> {
        val allStates = HashSet<State<T, ModelStates, C, V>>()
        allStates.addAll(states)
        allStates.add(voidState)
        return allStates
    }

    internal fun handlesType(type: KClass<Model<T>>): Boolean {
        return type == this.type
    }

    internal fun getAvailableExternalEventsForModel(
        model: Model<*>,
        context: C
    ): Set<EventReference> {
        return getStateByName(model.state).getEvents().filter { it.isExternal }.map { it.id }.toSet()
    }

    internal fun getExternalEventsForVoidState(context: C): Set<EventReference> {
        return voidState.getEvents().filter { it.isExternal } .map { it.id }.toSet()
    }

    public fun getExternalEvents(): Set<EventReference> =
        states.flatMap { state -> state.getEvents().filter { it.isExternal }.map { it.id } }.toSet()

    public fun getAllEvents(): Set<EventReference> = states.flatMap { state -> state.getEvents().map { it.id } }.toSet()


    // -------- Builder ---------------------

    public fun voidState(init: VoidState<T, ModelStates, C, V>.() -> Unit) {
        val state = VoidState<T, ModelStates, C, V>("void", type.simpleName!!)
        state.init()
        voidState = state
        states.add(state)
    }

    public fun state(modelState: ModelStates, init: InstanceState<T, ModelStates, C, V>.() -> Unit) {
        val state = InstanceState<T, ModelStates, C, V>(modelState.name, type.simpleName!!)
        state.init()
       // state.verifyAllUsedEventsAreDeclared(externalEvents.getEvents(), AnyType)
        states.add(state)
    }

    internal fun onKlerkStart(config: Config<C, V>) {
        states.forEach { it.onKlerkStart(config) }
    }

    public fun event(event: VoidEventNoParameters<T>, init: VoidEventRulesNoParameters<T, C, V>.() -> Unit) {
        declaredEvents.add(event)
        val rules = VoidEventRulesNoParameters<T, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules = (rules.withoutParametersValidationRules as Set<(ArgForVoidEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
    }

    public fun <P:Any> event(event: VoidEventWithParameters<T, P>, init: VoidEventRulesWithParameters<T, P, C, V>.() -> Unit) {
        declaredEvents.add(event)
        val rules = VoidEventRulesWithParameters<T, P, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules = (rules.withoutParametersValidationRules as Set<(ArgForVoidEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
        @Suppress("UNCHECKED_CAST")
        event.paramRulesForVoidEvent = (rules.withParametersValidationRules as Set<(ArgForVoidEvent<T, P, *, *>) -> PropertyCollectionValidity>)
        event.validRefs = rules.validRefs
    }

    public fun event(event: InstanceEventNoParameters<T>, init: InstanceEventRulesNoParameters<T, C, V>.() -> Unit) {
        declaredEvents.add(event)
        val rules = InstanceEventRulesNoParameters<T, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules = (rules.withoutParametersValidationRules as Set<(ArgForInstanceEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
    }

    public fun <P:Any> event(event: InstanceEventWithParameters<T, P>, init: InstanceEventRulesWithParameters<T, P, C, V>.() -> Unit) {
        declaredEvents.add(event)
        val rules = InstanceEventRulesWithParameters<T, P, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules = (rules.withoutParametersValidationRules as Set<(ArgForInstanceEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
        @Suppress("UNCHECKED_CAST")
        event.paramRulesForInstanceEvent = (rules.withParametersValidationRules as Set<(ArgForInstanceEvent<T, P, *, *>) -> PropertyCollectionValidity>)
        event.validRefs = rules.validRefs    }

}

public inline fun <reified T : Any, reified ModelStates : Enum<*>, C:KlerkContext, V> stateMachine(init: StateMachine<T, ModelStates, C, V>.() -> Unit): StateMachine<T, ModelStates, C, V> {
    val stateMachine = StateMachine<T, ModelStates, C, V>(T::class)
    stateMachine.init()
    return stateMachine
}

internal data class EventNameAndParameters(val name: String, val parameters: KClass<*>?)

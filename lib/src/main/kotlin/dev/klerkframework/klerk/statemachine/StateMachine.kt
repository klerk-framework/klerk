package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.storage.ModelCache
import kotlin.reflect.KClass

@SpecificationMarker
public class StateMachine<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val type: KClass<T>
) {

    internal lateinit var modelViews: ModelViews<T, C>
    internal val mutableStates: MutableList<State<T, ModelStates, C, V>> = mutableListOf<State<T, ModelStates, C, V>>()
    public val states: List<State<T, ModelStates, C, V>> get() = mutableStates
    public lateinit var voidState: VoidState<T, ModelStates, C, V>
    public val instanceStates: List<InstanceState<T, ModelStates, C, V>>
        get() = mutableStates.filterIsInstance<InstanceState<T, ModelStates, C, V>>()

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
        return mutableStates.firstOrNull { it.name == name }
            ?: throw InternalException(message = "State $name doesn't exist in statemachine for ${this.type.simpleName}. Do you need to migrate the data?")
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
        return mutableStates.find { it.name == model.state }
            ?: throw IllegalStateException(
                "The statemachine has not defined the state '${model.state}'. The defined available are: ${
                    mutableStates.joinToString(
                        ","
                    ) { it.name }
                }"
            )
    }

    private fun getAllStates(): Set<State<T, ModelStates, C, V>> {
        val allStates = HashSet<State<T, ModelStates, C, V>>()
        allStates.addAll(mutableStates)
        allStates.add(voidState)
        return allStates
    }

    internal fun handlesType(type: KClass<Model<T>>): Boolean {
        return type == this.type
    }

    internal fun getAvailableEventsForModel(
        model: Model<*>,
        context: C,
        visibility: EventVisibility,
    ): Set<EventReference> {
        return getStateByName(model.state).getEvents().filter { it.visibility.level >= visibility.level }.map { it.id }
            .toSet()
    }

    internal fun getEventsForVoidState(context: C, visibility: EventVisibility): Set<EventReference> {
        return voidState.getEvents().filter { it.visibility.level >= visibility.level }.map { it.id }.toSet()
    }

    /*    public fun getExternalEvents(): Set<EventReference> =
            states.flatMap { state -> state.getEvents().filter { it.visibility == EXTERNAL }.map { it.id } }.toSet()

     */

    /**
     * All events declared with any of the [event] overloads for this state machine, regardless of which state(s)
     * reference them in `onEvent`.
     */
    public fun getAllEvents(): Set<EventReference> =
        mutableStates.flatMap { state -> state.getEvents().map { it.id } }.toSet()


    // -------- Builder ---------------------

    /**
     * Declares the void state — where a model of type [T] is before it exists. Only creation-related `onEvent`
     * blocks (calling `createModel`) belong here. Every state machine must call this exactly once.
     */
    public fun voidState(init: VoidState<T, ModelStates, C, V>.() -> Unit) {
        val state = VoidState<T, ModelStates, C, V>("void", type.simpleName!!)
        state.init()
        voidState = state
        mutableStates.add(state)
    }

    /**
     * Declares one instance state, corresponding to a value of [modelState]. Call once per enum value.
     */
    public fun state(modelState: ModelStates, init: InstanceState<T, ModelStates, C, V>.() -> Unit) {
        val state = InstanceState<T, ModelStates, C, V>(modelState.name, type.simpleName!!)
        state.init()
        // state.verifyAllUsedEventsAreDeclared(externalEvents.getEvents(), AnyType)
        mutableStates.add(state)
    }

    internal fun onKlerkStart(specification: Specification<C, V>) {
        mutableStates.forEach { it.onKlerkStart(specification) }
    }

    /**
     * Declares [event] (a void event without parameters) as usable by this state machine and attaches its
     * validation rules via [init]. Must be called before [event] is referenced in any `onEvent` block — Klerk
     * throws `IllegalConfigurationException` at startup otherwise.
     */
    public fun event(event: VoidEventNoParameters<T>, init: VoidEventRulesNoParameters<T, C, V>.() -> Unit) {
        declaredEvents.add(event)
        val rules = VoidEventRulesNoParameters<T, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules =
            (rules.withoutParametersValidationRules as Set<(ArgForVoidEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
    }

    /**
     * Declares [event] (a void event with parameters of type [P]) as usable by this state machine and attaches its
     * validation rules via [init]. Must be called before [event] is referenced in any `onEvent` block — Klerk
     * throws `IllegalConfigurationException` at startup otherwise, and also if a [P] property of type `ModelID`
     * has no `validReferences` rule declared in [init].
     */
    public fun <P : Any> event(
        event: VoidEventWithParameters<T, P>,
        init: VoidEventRulesWithParameters<T, P, C, V>.() -> Unit
    ) {
        declaredEvents.add(event)
        val rules = VoidEventRulesWithParameters<T, P, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules =
            (rules.withoutParametersValidationRules as Set<(ArgForVoidEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
        @Suppress("UNCHECKED_CAST")
        event.paramRulesForVoidEvent =
            (rules.withParametersValidationRules as Set<(ArgForVoidEvent<T, P, *, *>) -> PropertyCollectionValidity>)
        event.validRefs = rules.validRefs
        event.validEnums = rules.validEnumsMap
    }

    /**
     * Declares [event] (an instance event without parameters) as usable by this state machine and attaches its
     * validation rules via [init]. Must be called before [event] is referenced in any `onEvent` block — Klerk
     * throws `IllegalConfigurationException` at startup otherwise.
     */
    public fun event(event: InstanceEventNoParameters<T>, init: InstanceEventRulesNoParameters<T, C, V>.() -> Unit) {
        declaredEvents.add(event)
        val rules = InstanceEventRulesNoParameters<T, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules =
            (rules.withoutParametersValidationRules as Set<(ArgForInstanceEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
    }

    /**
     * Declares [event] (an instance event with parameters of type [P]) as usable by this state machine and attaches
     * its validation rules via [init]. Must be called before [event] is referenced in any `onEvent` block — Klerk
     * throws `IllegalConfigurationException` at startup otherwise, and also if a [P] property of type `ModelID`
     * has no `validReferences` rule declared in [init].
     */
    public fun <P : Any> event(
        event: InstanceEventWithParameters<T, P>,
        init: InstanceEventRulesWithParameters<T, P, C, V>.() -> Unit
    ) {
        declaredEvents.add(event)
        val rules = InstanceEventRulesWithParameters<T, P, C, V>()
        rules.init()
        event.setContextRules(rules.contextValidations)
        @Suppress("UNCHECKED_CAST")
        event.noParamRules =
            (rules.withoutParametersValidationRules as Set<(ArgForInstanceEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity>)
        @Suppress("UNCHECKED_CAST")
        event.paramRulesForInstanceEvent =
            (rules.withParametersValidationRules as Set<(ArgForInstanceEvent<T, P, *, *>) -> PropertyCollectionValidity>)
        event.validRefs = rules.validRefs
        event.validEnums = rules.validEnumsMap
    }

}

public inline fun <reified T : Any, reified ModelStates : Enum<*>, C : KlerkContext, V> stateMachine(init: StateMachine<T, ModelStates, C, V>.() -> Unit): StateMachine<T, ModelStates, C, V> {
    val stateMachine = StateMachine<T, ModelStates, C, V>(T::class)
    stateMachine.init()
    return stateMachine
}

internal data class EventNameAndParameters(val name: String, val parameters: KClass<*>?)

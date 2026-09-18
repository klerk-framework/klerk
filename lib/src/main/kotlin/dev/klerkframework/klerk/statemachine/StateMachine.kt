package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.InstanceEvent
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.InternalException
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.SpecificationMarker
import dev.klerkframework.klerk.VoidEvent
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.misc.PropertyKey
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.klerk.validation.ContextValidity
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import kotlin.reflect.KClass

/** The states and events of a managed model of type [T]. Built with [stateMachine]. */
@SpecificationMarker
public class StateMachine<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(internal val type: KClass<T>) {

    internal lateinit var modelViews: ModelViews<T, C>
    private val _states: MutableList<State<T, ModelStates, C, V>> = mutableListOf()

    /** Every state, including the void state, in declaration order. */
    public val states: List<State<T, ModelStates, C, V>> get() = _states.toList()
    private lateinit var _voidState: VoidState<T, ModelStates, C, V>

    /** The void state — where a model of type [T] is before it exists. Declared with `voidState { }`. */
    public val voidState: VoidState<T, ModelStates, C, V> get() = _voidState

    /** Every state except the void state, in declaration order. */
    public val instanceStates: List<InstanceState<T, ModelStates, C, V>>
        get() = _states.filterIsInstance<InstanceState<T, ModelStates, C, V>>()

    internal val declaredEvents = mutableListOf<Event<T, *>>()

    /**
     * What each `event(...)` block declared, keyed by event. Kept here rather than on the `Event` object: an event is
     * a singleton `object`, so state written onto it would outlive the state machine that declared it and leak between
     * specifications — which is exactly what used to make tests order-dependent.
     */
    private val declaredRules = mutableMapOf<EventReference, DeclaredEventRules>()

    internal fun rulesFor(event: EventReference): DeclaredEventRules = declaredRules[event] ?: DeclaredEventRules()

    override fun toString(): String =
        "StateMachine(${type.simpleName}, states=${_states.joinToString(", ") { it.name }})"

    private var voidStateDeclared = false
    private val declaredModelStates = mutableSetOf<ModelStates>()
    private var modelStatesClass: Class<out ModelStates>? = null

    /** The enum the states were declared from, or null if no state was declared. */
    internal val statesEnumClass: Class<out ModelStates>? get() = modelStatesClass

    // internal lateinit var externalEvents: ExternalEvents<V, T>

    internal fun setView(view: ModelViews<*, C>) {
        @Suppress("UNCHECKED_CAST")
        modelViews = view as ModelViews<T, C>
    }

    internal fun getStateByName(name: String?): State<T, ModelStates, C, V> {
        if (name == null) {
            return voidState
        }
        return _states.firstOrNull { it.name == name }
            ?: throw InternalException(
                message = "State $name doesn't exist in statemachine for ${type.simpleName}. " +
                    "Do you need to migrate the data?",
            )
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
        return _states.find { it.name == model.state }
            ?: throw IllegalStateException(
                "The statemachine has not defined the state '${model.state}'. The defined available are: ${
                    _states.joinToString(
                        ",",
                    ) { it.name }
                }",
            )
    }

    private fun getAllStates(): Set<State<T, ModelStates, C, V>> {
        val allStates = HashSet<State<T, ModelStates, C, V>>()
        allStates.addAll(_states)
        allStates.add(voidState)
        return allStates
    }

    internal fun handlesType(type: KClass<Model<T>>): Boolean = type == this.type

    internal fun getAvailableEventsForModel(model: Model<*>, visibility: EventVisibility): Set<InstanceEvent<T, *>> =
        getStateByName(model.state).getEvents().filter { it.visibility.level >= visibility.level }
            .map { it as InstanceEvent<T, *> }.toSet()

    internal fun getEventsForVoidState(visibility: EventVisibility): Set<VoidEvent<T, *>> =
        voidState.getEvents().filter { it.visibility.level >= visibility.level }.map { it as VoidEvent<T, *> }.toSet()

    /*    public fun getExternalEvents(): Set<EventReference> =
            states.flatMap { state -> state.getEvents().filter { it.visibility == EXTERNAL }.map { it.id } }.toSet()

     */

    /**
     * All events declared with any of the [event] overloads for this state machine, regardless of which state(s)
     * reference them in `onEvent`.
     */
    public val eventReferences: Set<EventReference> get() =
        _states.flatMap { state -> state.getEvents().map { it.id } }.toSet()

    // -------- Builder ---------------------

    /**
     * Declares the void state — where a model of type [T] is before it exists. Only creation-related `onEvent`
     * blocks (calling `createModel`) belong here. Every state machine must call this exactly once.
     */
    public fun voidState(init: VoidState<T, ModelStates, C, V>.() -> Unit) {
        if (voidStateDeclared) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state machine for ${type.simpleName} declares voidState more than once",
            )
        }
        val state = VoidState<T, ModelStates, C, V>("void", type.simpleName!!)
        state.init()
        _voidState = state
        voidStateDeclared = true
        _states.add(state)
    }

    /**
     * Declares one instance state, corresponding to a value of [modelState]. Call once per enum value.
     */
    public fun state(modelState: ModelStates, init: InstanceState<T, ModelStates, C, V>.() -> Unit) {
        if (!declaredModelStates.add(modelState)) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state machine for ${type.simpleName} declares the state ${modelState.name} more than once",
            )
        }
        modelStatesClass = modelState.javaClass
        val state = InstanceState<T, ModelStates, C, V>(modelState.name, type.simpleName!!)
        state.init()
        _states.add(state)
    }

    /** Fails when the state machine is incomplete: no void state, or an enum value without a `state(...)` block. */
    internal fun validateStatesAreComplete() {
        if (!voidStateDeclared) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state machine for ${type.simpleName} does not declare a voidState",
            )
        }
        val missing = (modelStatesClass?.enumConstants ?: emptyArray()).filterNot { it in declaredModelStates }
        if (missing.isNotEmpty()) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state machine for ${type.simpleName} does not declare a state for " +
                    missing.joinToString(", ") { it.name },
            )
        }
    }

    internal fun onKlerkStart(specification: Specification<C, V>) {
        for (_state in _states) {
            _state.onKlerkStart(specification)
        }
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
        declare(event, rules.contextValidations, rules.withoutParametersValidationRules)
    }

    /**
     * Declares [event] (a void event with parameters of type [P]) as usable by this state machine and attaches its
     * validation rules via [init]. Must be called before [event] is referenced in any `onEvent` block — Klerk
     * throws `IllegalConfigurationException` at startup otherwise, and also if a [P] property of type `ModelID`
     * has no `validReferences` rule declared in [init].
     */
    public fun <P : Any> event(
        event: VoidEventWithParameters<T, P>,
        init: VoidEventRulesWithParameters<T, P, C, V>.() -> Unit,
    ) {
        declaredEvents.add(event)
        val rules = VoidEventRulesWithParameters<T, P, C, V>()
        rules.init()
        declare(
            event,
            rules.contextValidations,
            rules.withoutParametersValidationRules,
            rules.withParametersValidationRules,
            rules.validRefs,
            rules.validEnumsMap,
        )
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
        declare(event, rules.contextValidations, rules.withoutParametersValidationRules)
    }

    /**
     * Declares [event] (an instance event with parameters of type [P]) as usable by this state machine and attaches
     * its validation rules via [init]. Must be called before [event] is referenced in any `onEvent` block — Klerk
     * throws `IllegalConfigurationException` at startup otherwise, and also if a [P] property of type `ModelID`
     * has no `validReferences` rule declared in [init].
     */
    public fun <P : Any> event(
        event: InstanceEventWithParameters<T, P>,
        init: InstanceEventRulesWithParameters<T, P, C, V>.() -> Unit,
    ) {
        declaredEvents.add(event)
        val rules = InstanceEventRulesWithParameters<T, P, C, V>()
        rules.init()
        declare(
            event,
            rules.contextValidations,
            rules.withoutParametersValidationRules,
            rules.withParametersValidationRules,
            rules.validRefs,
            rules.validEnumsMap,
        )
    }

    /**
     * Records what an `event(...)` block declared. The rule sets are stored erased: every consumer already knows the
     * event kind it is asking about, and casts back to it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun declare(
        event: Event<T, *>,
        contextRules: Set<(C) -> ContextValidity>,
        noParamRules: Set<*>,
        paramRules: Set<*> = emptySet<Any>(),
        validRefs: Map<PropertyKey, ModelView<out Any, *>> = emptyMap(),
        validEnums: Map<PropertyKey, Set<Enum<*>>> = emptyMap(),
    ) {
        require(
            declaredRules.put(
                event.id,
                DeclaredEventRules(
                    contextRules = contextRules as Set<(KlerkContext) -> ContextValidity>,
                    noParamRules = noParamRules as Set<(Nothing) -> PropertyCollectionValidity>,
                    paramRules = paramRules as Set<(Nothing) -> PropertyCollectionValidity>,
                    validRefs = validRefs,
                    validEnums = validEnums,
                ),
            ) == null,
        ) { "The event ${event.id} is declared more than once in this state machine" }
    }
}

/**
 * The rules one `event(...)` block declared, as stored by [StateMachine]. Erased: each consumer casts the rule sets
 * back to the argument type its event kind uses.
 */
internal data class DeclaredEventRules(
    val contextRules: Set<(KlerkContext) -> ContextValidity> = emptySet(),
    val noParamRules: Set<(Nothing) -> PropertyCollectionValidity> = emptySet(),
    val paramRules: Set<(Nothing) -> PropertyCollectionValidity> = emptySet(),
    val validRefs: Map<PropertyKey, ModelView<out Any, *>> = emptyMap(),
    val validEnums: Map<PropertyKey, Set<Enum<*>>> = emptyMap(),
) {
    /** Every rule declared here, for the "rules must be named" check. */
    val allRules: List<Function<*>> get() = contextRules.toList() + noParamRules + paramRules

    /** The rules that run without the event's parameters, as the argument type [A] of this event kind. */
    @Suppress("UNCHECKED_CAST")
    fun <A> withoutParameters(): Set<(A) -> PropertyCollectionValidity> =
        noParamRules as Set<(A) -> PropertyCollectionValidity>

    /** The rules that run with the event's parameters, as the argument type [A] of this event kind. */
    @Suppress("UNCHECKED_CAST")
    fun <A> withParameters(): Set<(A) -> PropertyCollectionValidity> =
        paramRules as Set<(A) -> PropertyCollectionValidity>

    /** The rules that run against the context alone. */
    @Suppress("UNCHECKED_CAST")
    fun <C : KlerkContext> forContext(): Set<(C) -> ContextValidity> = contextRules as Set<(C) -> ContextValidity>
}

/** Builds the state machine of the model [T], whose states are the constants of [ModelStates]. */
public inline fun <reified T : Any, reified ModelStates : Enum<*>, C : KlerkContext, V> stateMachine(
    init: StateMachine<T, ModelStates, C, V>.() -> Unit,
): StateMachine<T, ModelStates, C, V> {
    val stateMachine = StateMachine<T, ModelStates, C, V>(T::class)
    stateMachine.init()
    return stateMachine
}

internal data class EventNameAndParameters(val name: String, val parameters: KClass<*>?)

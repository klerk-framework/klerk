package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.validation.ContextValidity
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.misc.PropertyKey
import kotlin.reflect.KProperty1

/**
 * Base of the receiver passed to an `event(...) { }` block in the `stateMachine` DSL — where an event's validation
 * rules are attached. See [dev.klerkframework.klerk.statemachine.StateMachine.event].
 *
 * Every rule must be a named function reference, e.g. `validate(::myRule)`. A lambda is rejected when Klerk starts.
 */
@SpecificationMarker
public abstract class EventRules<C : KlerkContext> {
    internal val contextValidations: MutableSet<((C) -> ContextValidity)> = mutableSetOf()

    /** Adds a rule that rejects the event based on the context alone (e.g. actor permissions independent of params). */
    public fun validateWithContext(function: (C) -> ContextValidity) {
        contextValidations.add(function)
    }
}

/**
 * Rules for an event with parameters of type [P].
 *
 * `validReferences` and `validEnums` take a property of [P] or of a class nested in [P], e.g. `MyParams::author` or
 * `Address::owner`.
 */
public abstract class EventRulesWithParameters<P : Any, C : KlerkContext> : EventRules<C>() {
    internal val validRefs: MutableMap<PropertyKey, ModelView<out Any, *>> = mutableMapOf()
    internal val validEnumsMap: MutableMap<PropertyKey, Set<Enum<*>>> = mutableMapOf()

    /**
     * Declares which models the `ModelID` [property] may point to: an id outside [modelView] fails validation.
     * Required for every `ModelID` in the parameters — Klerk rejects the specification at startup otherwise.
     */
    public fun <T : Any> validReferences(property: KProperty1<*, ModelID<out T>?>, modelView: ModelView<T, C>) {
        validRefs[PropertyKey.of(property)] = modelView
    }

    /** Like the single-id variant, for a `List` or `Set` of ids: every id must be in [modelView]. */
    @JvmName("validReferencesInCollection")
    public fun <T : Any> validReferences(
        property: KProperty1<*, Collection<ModelID<out T>>?>,
        modelView: ModelView<T, C>,
    ) {
        validRefs[PropertyKey.of(property)] = modelView
    }

    /** Restricts the [EnumContainer] [property] to [validValues]; any other value fails validation. */
    public fun <E : Enum<E>> validEnums(property: KProperty1<*, EnumContainer<E>?>, validValues: Set<E>) {
        validEnumsMap[PropertyKey.of(property)] = validValues
    }

    /**
     * Like the single-value variant, for a `List` or `Set` of [EnumContainer]s: every value must be in [validValues].
     */
    @JvmName("validEnumsInCollection")
    public fun <E : Enum<E>> validEnums(property: KProperty1<*, Collection<EnumContainer<E>>?>, validValues: Set<E>) {
        validEnumsMap[PropertyKey.of(property)] = validValues
    }
}

internal typealias ValidationRule<A> = (A) -> PropertyCollectionValidity

/**
 * Rules receiver for an instance event with parameters (see
 * [dev.klerkframework.klerk.statemachine.StateMachine.event]).
 */
public class InstanceEventRulesWithParameters<T : Any, P : Any, C : KlerkContext, V> :
    EventRulesWithParameters<P, C>() {

    internal val withoutParametersValidationRules = mutableSetOf<ValidationRule<InstanceEventArgs<T, Nothing?, C, V>>>()
    internal val withParametersValidationRules = mutableSetOf<ValidationRule<InstanceEventArgs<T, P, C, V>>>()

    /** Adds a rule that validates against [InstanceEventArgs] but without access to the event's parameters. */
    public fun validate(function: (InstanceEventArgs<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(function)
    }

    /** Adds a rule that validates against [InstanceEventArgs], with access to the event's parameters. */
    public fun validateWithParameters(function: (InstanceEventArgs<T, P, C, V>) -> PropertyCollectionValidity) {
        withParametersValidationRules.add(function)
    }
}

/** Rules receiver for a void event with parameters (see [dev.klerkframework.klerk.statemachine.StateMachine.event]). */
public class VoidEventRulesWithParameters<T : Any, P : Any, C : KlerkContext, V> : EventRulesWithParameters<P, C>() {

    internal val withoutParametersValidationRules = mutableSetOf<ValidationRule<VoidEventArgs<T, Nothing?, C, V>>>()
    internal val withParametersValidationRules = mutableSetOf<ValidationRule<VoidEventArgs<T, P, C, V>>>()

    /** Adds a rule that validates against [VoidEventArgs] but without access to the event's parameters. */
    public fun validate(function: (VoidEventArgs<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(function)
    }

    /** Adds a rule that validates against [VoidEventArgs], with access to the event's parameters. */
    public fun validateWithParameters(function: (VoidEventArgs<T, P, C, V>) -> PropertyCollectionValidity) {
        withParametersValidationRules.add(function)
    }
}

/**
 * Rules receiver for a void event with no parameters (see [dev.klerkframework.klerk.statemachine.StateMachine.event]).
 */
public class VoidEventRulesNoParameters<T : Any, C : KlerkContext, V> : EventRules<C>() {
    internal val withoutParametersValidationRules = mutableSetOf<ValidationRule<VoidEventArgs<T, Nothing?, C, V>>>()

    /** Adds a rule that validates against [VoidEventArgs]. */
    public fun validate(f: (VoidEventArgs<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(f)
    }
}

/**
 * Rules receiver for an instance event with no parameters (see
 * [dev.klerkframework.klerk.statemachine.StateMachine.event]).
 */
public class InstanceEventRulesNoParameters<T : Any, C : KlerkContext, V> : EventRules<C>() {
    internal val withoutParametersValidationRules = mutableSetOf<ValidationRule<InstanceEventArgs<T, Nothing?, C, V>>>()

    /** Adds a rule that validates against [InstanceEventArgs]. */
    public fun validate(f: (InstanceEventArgs<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(f)
    }
}

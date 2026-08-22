package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.misc.EventParameter
import kotlin.reflect.KProperty1

/**
 * Base of the receiver passed to an `event(...) { }` block in the `stateMachine` DSL — where an event's validation
 * rules are attached. See [dev.klerkframework.klerk.statemachine.StateMachine.event].
 */
@SpecificationMarker
public abstract class EventRules<C : KlerkContext> {
    internal val contextValidations: MutableSet<((C) -> PropertyCollectionValidity)> = mutableSetOf()

    /** Adds a rule that rejects the event based on the context alone (e.g. actor permissions independent of params). */
    public fun validateWithContext(function: (C) -> PropertyCollectionValidity) {   // TODO: not PropertyCollectionValidity
        contextValidations.add(function)
    }
}

public abstract class EventRulesWithParameters<P : Any, C : KlerkContext> : EventRules<C>() {
    internal val parametersValidations: MutableSet<((P) -> PropertyCollectionValidity)> = mutableSetOf()

    /** Adds a rule that validates the event's parameters of type [P] in isolation, without model/context access. */
    public fun validateParameters(function: (P) -> PropertyCollectionValidity) {
        parametersValidations.add(function)
    }
}

public class InstanceEventRulesWithParameters<T : Any, P : Any, C : KlerkContext, V> :
    EventRulesWithParameters<P, C>() {

    internal val validRefs: MutableMap<String, ModelView<*, C>> = mutableMapOf()
    internal var referencesThatAllowsEverything: MutableSet<String> = mutableSetOf()
    internal val validEnumsMap: MutableMap<String, Set<Enum<*>>> = mutableMapOf()
    internal val withoutParametersValidationRules: MutableSet<(ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> =
        mutableSetOf()
    internal val withParametersValidationRules: MutableSet<(ArgForInstanceEvent<T, P, C, V>) -> PropertyCollectionValidity> =
        mutableSetOf()

    /** Restricts [property] (an [EnumContainer] parameter) to [validValues]; any other value fails validation. */
    public fun <E : Enum<E>> validEnums(property: KProperty1<*, EnumContainer<E>?>, validValues: Set<E>) {
        @Suppress("UNCHECKED_CAST")
        validEnumsMap[property.name] = validValues as Set<Enum<*>>
    }

    /**
     * Declares which models [property] (a `ModelID` parameter) may point to. Required for every `ModelID` event
     * parameter — Klerk rejects the specification at startup otherwise. Pass `modelView = null` to allow any existing
     * model id of that type through with no membership check.
     */
    public fun <T : Any> validReferences(property: KProperty1<*, ModelID<T>?>, modelView: ModelView<T, C>?) {
        if (modelView == null) {
            referencesThatAllowsEverything.add(property.name)
        } else {
            validRefs[property.name] = modelView
        }
    }

    /** Adds a rule that validates against [ArgForInstanceEvent] but without access to the event's parameters. */
    public fun validate(function: (ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(function)
    }

    /** Adds a rule that validates against [ArgForInstanceEvent], with access to the event's parameters. */
    public fun validateWithParameters(function: (ArgForInstanceEvent<T, P, C, V>) -> PropertyCollectionValidity) {
        withParametersValidationRules.add(function)
    }

    internal fun getValidationCollectionFor(parameter: EventParameter): ModelView<out Any, C>? {
        if (referencesThatAllowsEverything.contains(parameter.name)) {
            return null
        }
        return validRefs[parameter.name]
            ?: throw NotFoundProblem("No validation listsource found for '${parameter.name}'").asException()
    }
}

public class VoidEventRulesWithParameters<T : Any, P : Any, C : KlerkContext, V> : EventRulesWithParameters<P, C>() {

    internal val validRefs: MutableMap<String, ModelView<*, C>?> = mutableMapOf()
    internal var referencesThatAllowsEverything: MutableSet<String> = mutableSetOf()
    internal val validEnumsMap: MutableMap<String, Set<Enum<*>>> = mutableMapOf()
    internal val withoutParametersValidationRules: MutableSet<(ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> =
        mutableSetOf()
    internal val withParametersValidationRules: MutableSet<(ArgForVoidEvent<T, P, C, V>) -> PropertyCollectionValidity> =
        mutableSetOf()

    /** Restricts [property] (an [EnumContainer] parameter) to [validValues]; any other value fails validation. */
    public fun <E : Enum<E>> validEnums(property: KProperty1<*, EnumContainer<E>?>, validValues: Set<E>) {
        @Suppress("UNCHECKED_CAST")
        validEnumsMap[property.name] = validValues as Set<Enum<*>>
    }

    /**
     * Declares which models [property] (a `ModelID` parameter) may point to. Required for every `ModelID` event
     * parameter — Klerk rejects the specification at startup otherwise.
     */
    public fun <T : Any> validReferences(property: KProperty1<*, ModelID<out T>?>, modelView: ModelView<T, C>?) {
        //if (collection == null) {
        //  referencesThatAllowsEverything.add(property.name)
        //} else {
        validRefs[property.name] = modelView
        //}
    }

    /** Adds a rule that validates against [ArgForVoidEvent] but without access to the event's parameters. */
    public fun validate(function: (ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(function)
    }

    /** Adds a rule that validates against [ArgForVoidEvent], with access to the event's parameters. */
    public fun validateWithParameters(function: (ArgForVoidEvent<T, P, C, V>) -> PropertyCollectionValidity) {
        withParametersValidationRules.add(function)
    }

    internal fun getValidationCollectionFor(parameter: EventParameter): ModelView<out Any, C>? {
        if (referencesThatAllowsEverything.contains(parameter.name)) {
            return null
        }
        return validRefs[parameter.name]
            ?: throw NotFoundProblem("No validation listsource found for '${parameter.name}'").asException()
    }
}

/** Rules receiver for a void event with no parameters (see [dev.klerkframework.klerk.statemachine.StateMachine.event]). */
public class VoidEventRulesNoParameters<T : Any, C : KlerkContext, V> : EventRules<C>() {
    internal val withoutParametersValidationRules: MutableSet<(ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> =
        mutableSetOf()

    /** Adds a rule that validates against [ArgForVoidEvent]. */
    public fun validate(f: (ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(f)
    }
}

/** Rules receiver for an instance event with no parameters (see [dev.klerkframework.klerk.statemachine.StateMachine.event]). */
public class InstanceEventRulesNoParameters<T : Any, C : KlerkContext, V> : EventRules<C>() {
    internal val withoutParametersValidationRules: MutableSet<(ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> =
        mutableSetOf()

    /** Adds a rule that validates against [ArgForInstanceEvent]. */
    public fun validate(f: (ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(f)
    }
}

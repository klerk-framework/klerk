package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.EventParameter
import dev.klerkframework.klerk.collection.ModelCollection

import kotlin.reflect.KProperty1

@ConfigMarker
public abstract class EventRules<C:KlerkContext>  {
    internal val contextValidations: MutableSet<((C) -> PropertyCollectionValidity)> = mutableSetOf()
    public fun validateContext(function: (C) -> PropertyCollectionValidity) {
        contextValidations.add(function)
    }
}

public abstract class EventRulesWithParameters<P:Any, C:KlerkContext> : EventRules<C>() {
    internal val parametersValidations: MutableSet<((P) -> PropertyCollectionValidity)> = mutableSetOf()
    public fun validateParameters(function: (P) -> PropertyCollectionValidity) {
        parametersValidations.add(function)
    }
}

public class InstanceEventRulesWithParameters<T:Any, P:Any, C:KlerkContext, V> : EventRulesWithParameters<P, C>() {

    internal val validRefs: MutableMap<String, ModelCollection<*, C>> = mutableMapOf()
    internal var referencesThatAllowsEverything: MutableSet<String> = mutableSetOf()
    internal val withoutParametersValidationRules: MutableSet<(ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> = mutableSetOf()
    internal val withParametersValidationRules: MutableSet<(ArgForInstanceEvent<T, P, C, V>) -> PropertyCollectionValidity> = mutableSetOf()

    public fun <T : Any> validReferences(property: KProperty1<*, ModelID<T>>, modelCollection: ModelCollection<T, C>?) {
        if (modelCollection == null) {
            referencesThatAllowsEverything.add(property.name)
        } else {
            validRefs[property.name] = modelCollection
        }
    }

    public fun validate(function: (ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(function)
    }

    public fun validateWithParameters(function: (ArgForInstanceEvent<T, P, C, V>) -> PropertyCollectionValidity) {
        withParametersValidationRules.add(function)
    }

    internal fun getValidationCollectionFor(parameter: EventParameter): ModelCollection<out Any, C>? {
        if (referencesThatAllowsEverything.contains(parameter.name)) {
            return null
        }
        return validRefs[parameter.name]
            ?: throw NotFoundProblem("No validation listsource found for '${parameter.name}'").asException()
    }
}

public class VoidEventRulesWithParameters<T:Any, P:Any, C:KlerkContext, V> : EventRulesWithParameters<P, C>() {

    internal val validRefs: MutableMap<String, ModelCollection<*, C>?> = mutableMapOf()
    internal var referencesThatAllowsEverything: MutableSet<String> = mutableSetOf()
    internal val withoutParametersValidationRules: MutableSet<(ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> = mutableSetOf()
    internal val withParametersValidationRules: MutableSet<(ArgForVoidEvent<T, P, C, V>) -> PropertyCollectionValidity> = mutableSetOf()

    public fun <T : Any> validReferences(property: KProperty1<*, ModelID<out T>?>, modelCollection: ModelCollection<T, C>?) {
        //if (collection == null) {
          //  referencesThatAllowsEverything.add(property.name)
        //} else {
            validRefs[property.name] = modelCollection
        //}
    }

    public fun validate(function: (ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(function)
    }

    public fun validateWithParameters(function: (ArgForVoidEvent<T, P, C, V>) -> PropertyCollectionValidity) {
        withParametersValidationRules.add(function)
    }

    internal fun getValidationCollectionFor(parameter: EventParameter): ModelCollection<out Any, C>? {
        if (referencesThatAllowsEverything.contains(parameter.name)) {
            return null
        }
        return validRefs[parameter.name]
            ?: throw NotFoundProblem("No validation listsource found for '${parameter.name}'").asException()
    }
}

public class VoidEventRulesNoParameters<T:Any, C:KlerkContext, V> : EventRules<C>() {
    internal val withoutParametersValidationRules: MutableSet<(ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> = mutableSetOf()
    public fun validate(f: (ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(f)
    }
}

public class InstanceEventRulesNoParameters<T:Any, C:KlerkContext, V> : EventRules<C>() {
    internal val withoutParametersValidationRules: MutableSet<(ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity> = mutableSetOf()
    public fun validate(f: (ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity) {
        withoutParametersValidationRules.add(f)
    }
}

package dev.klerkframework.klerk.validation

import dev.klerkframework.klerk.InvalidPropertyCollectionProblem
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.functionName
import kotlin.reflect.KProperty0

/** What a validation rule returns: [Valid], or the `Invalid` of the kind of rule. */
public sealed interface Validity

/** The rule found nothing wrong. Returned by every kind of rule. */
public data object Valid : PropertyValidity, PropertyCollectionValidity, ContextValidity

/** The result of a single [DataContainer] validator function (see `DataContainer.validators`). */
public sealed interface PropertyValidity : Validity {

    /** The value is invalid. */
    public class Invalid(
        /** Optional detail passed to [Translation] when building the end-user error message. */
        public val translationInfo: String? = null,
    ) : PropertyValidity
}

/**
 * Describes the validity of a collection of properties (i.e. a class) given that each individual property is valid.
 * E.g. for a class containing two properties x: EvenIntContainer and y: OddIntContainer where x and y are valid,
 * a PropertyCollectionValidity can express that {x, y} is not valid since x > y.
 */
public sealed interface PropertyCollectionValidity : Validity {

    /** The combination of properties is invalid. */
    public class Invalid(
        /**
         * Optional detail passed to [dev.klerkframework.klerk.KlerkTranslation.invalidPropertyCollection] when building
         * the end-user message. The message itself comes from the [Translation], so a rule never spells it out.
         */
        public val translationInfo: String? = null,
        /** The property the rule requires to be null, e.g. to grey out an input. */
        public val fieldMustBeNull: KProperty0<DataContainer<*>?>? = null,
        /** The property the rule requires to be non-null. */
        public val fieldMustNotBeNull: KProperty0<DataContainer<*>?>? = null,
    ) : PropertyCollectionValidity {
        /**
         * The end-user message for this result, as [Translation] builds it from the name of [rule] (the validator
         * function that returned this) and [translationInfo].
         */
        public fun message(rule: Function<Any>, translation: Translation): String =
            translation.klerk.invalidPropertyCollection(
                functionName(rule) ?: translation.klerk.invalid,
                translationInfo,
            )

        /**
         * The problem Klerk reports when a parameters class rejects itself. Use it when evaluating
         * [dev.klerkframework.klerk.Validatable.validators] yourself, e.g. to show the errors in a form before
         * submitting the command, so the message is built the same way.
         */
        public fun toProblem(rule: Function<Any>, translation: Translation): InvalidPropertyCollectionProblem =
            InvalidPropertyCollectionProblem(
                endUserTranslatedMessage = message(rule, translation),
                fieldsMustBeNull = setOfNotNull(fieldMustBeNull),
                fieldsMustNotBeNull = setOfNotNull(fieldMustNotBeNull),
            )
    }
}

/**
 * Describes whether a command may proceed given the context alone — no property is examined. Returned by a rule
 * registered with `validateWithContext`.
 */
public sealed interface ContextValidity : Validity {

    /** The command may not proceed. */
    public class Invalid(
        /**
         * Optional detail passed to [dev.klerkframework.klerk.KlerkTranslation.preventedByRule] when building the
         * end-user message.
         */
        public val translationInfo: String? = null,
    ) : ContextValidity
}

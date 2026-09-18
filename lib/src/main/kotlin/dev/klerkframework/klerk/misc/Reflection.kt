package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.camelCaseToPretty
import kotlin.reflect.KFunction

/**
 * The name of a validator function, as shown in generated documentation: the name of a function reference in
 * backticks as written, otherwise un-camel-cased.
 */
internal fun validatorName(function: Function<*>): String {
    val name = functionName(function) ?: return extractNameFromFunctionString(function.toString())
    return if (' ' in name) name else camelCaseToPretty(name)
}

internal fun extractNameFromFunctionString(funString: String): String {
    var startIndex = funString.indexOf("`") + 1
    var endIndex = funString.indexOf("`", startIndex + 1)
    if (startIndex == 0 || endIndex == -1 || startIndex > endIndex) {
        startIndex = funString.indexOf(" ") + 1
        endIndex = funString.indexOf("(")
        return camelCaseToPretty(funString.substring(startIndex, endIndex))
    }
    return funString.substring(startIndex, endIndex)
}

/** The name of [function] if it is a function reference such as `::mustBeEven`, otherwise null (e.g. a lambda). */
internal fun functionName(function: Function<*>): String? = (function as? KFunction<*>)?.name

/**
 * Returns the name of [rule]. [where] is the kind and owner of the rule, e.g. `A validator of BookTitle`.
 *
 * @throws IllegalConfigurationException if [rule] is not a named function reference such as `::myRule`
 */
internal fun requireNamedRule(rule: Function<*>, where: String): String = functionName(rule)
    ?: throw IllegalConfigurationException(
        KlerkErrorCode.RuleMustBeNamed,
        "$where is a lambda, but it must be a named function reference (::myRule), since its name identifies the rule.",
    )

/**
 * A human-readable name for a function, e.g. `Must be even` for `::mustBeEven`, or `? unknown function name` for a
 * lambda.
 */
internal fun extractNameFromFunction(f: Function<*>): String {
    val name = functionName(f) ?: return "? unknown function name"
    if (name == "execute") {
        return f.toString().split(".execute").first().split(".").last()
    }
    return camelCaseToPretty(name)
}

/** The enum class a constant was declared in, also when the constant has a class body of its own. */
internal fun Enum<*>.declaringClass(): Class<*> = (this as java.lang.Enum<*>).declaringClass

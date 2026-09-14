package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.StateMachine
import java.util.*
import kotlin.reflect.KFunction
import kotlin.time.Instant

internal fun <T : Any, P, C : KlerkContext, V> getStateMachine(
    command: Command<T, P>,
    managedModels: Set<ManagedModel<out Any, *, C, V>>
): StateMachine<T, *, C, V> {
    val stateMachine =
        managedModels.find { it.stateMachine.knowsAboutEvent(command.event.id) }?.stateMachine
            ?: throw RuntimeException("Can't find state machine for event '${command.event}'")
    @Suppress("UNCHECKED_CAST")
    return stateMachine as StateMachine<T, *, C, V>
}


/** Checks that every [ModelID] in the model's props, also in collections and nested objects, refers to a model. */
internal fun <C : KlerkContext, V> verifyReferencesExist(model: Model<*>, reader: ModelReader<C, V>): Problem? {
    var problem: Problem? = null
    ObjectSchema.of(model.props::class).forEachLeaf(model.props) { leaf ->
        val id = leaf.value as? ModelID<*> ?: return@forEachLeaf
        if (problem != null) {
            return@forEachLeaf
        }
        try {
            @Suppress("UNCHECKED_CAST")
            reader.get(id as ModelID<Any>)
        } catch (e: NoSuchElementException) {
            problem = NotFoundProblem(e.message ?: "Could not find the model with id $id")
        }
    }
    return problem
}

/**
 * The name of a validator function, as shown in generated documentation: the name of a function reference in
 * backticks as written, otherwise un-camel-cased.
 */
internal fun validatorName(function: Function<*>): String {
    val name = functionName(function) ?: return extractNameFromFunctionString(function.toString())
    return if (name.contains(' ')) name else camelCaseToPretty(name)
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
 * @param where the kind and owner of the rule, e.g. `A validator of BookTitle`
 * @throws IllegalConfigurationException if [rule] is not a named function reference such as `::myRule`
 */
internal fun requireNamedRule(rule: Function<*>, where: String): String = functionName(rule)
    ?: throw IllegalConfigurationException(
        KlerkErrorCode.RuleMustBeNamed,
        "$where is a lambda, but it must be a named function reference (::myRule), since its name identifies the rule."
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

// why does Ktor have its own implementation of these?
internal fun String.encodeBase64(): String = Base64.getEncoder().encodeToString(this.toByteArray())
internal fun String.decodeBase64String(): String = String(Base64.getDecoder().decode(this))

/** Base64 without `+`, `/` or `=`, so the result is safe to put in a URL without escaping. */
internal fun String.encodeBase64UrlSafe(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this.toByteArray())

/** @throws IllegalArgumentException if this is not valid URL-safe base64 */
internal fun String.decodeBase64UrlSafeString(): String = String(Base64.getUrlDecoder().decode(this))

/**
 * Klerk uses 64bit microseconds for timestamps, but Instant has higher precision than that. When serializing and
 * deserializing, the result can differ from the original. This function removes precision to make the two equal.
 */
internal fun makeExactSerializable(instant: Instant): Instant =
    decode64bitMicroseconds(instant.to64bitMicroseconds())

/**
 * Get an Instant which is guaranteed to be the same after serialization and deserialization.
 */
internal fun getCurrentInstant(): Instant = makeExactSerializable(kotlin.time.Clock.System.now())

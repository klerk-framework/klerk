package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.StateMachine
import java.util.*
import kotlin.reflect.KFunction

internal fun <T : Any, P, C:KlerkContext, V> getStateMachine(
    command: Command<T, P>,
    managedModels: Set<ManagedModel<out Any, *, C, V>>
): StateMachine<T, *, C, V> {
    val stateMachine =
        managedModels.find { it.stateMachine.knowsAboutEvent(command.event.id) }?.stateMachine
            ?: throw RuntimeException("Can't find state machine for event '${command.event}'")
    @Suppress("UNCHECKED_CAST")
    return stateMachine as StateMachine<T, *, C, V>
}

public fun camelCaseToPretty(s: String): String {
    var result = ""
    s.toCharArray().forEachIndexed { index, c ->
        run {
            if (index == 0) {
                result += c.titlecase(Locale.getDefault())
            }
            if (index > 0) {
                result = if (c.isUpperCase()) {
                    result + " " + c.lowercase()
                } else {
                    result + c.lowercase()
                }
            }
        }
    }
    return result
}


internal fun <C:KlerkContext, V> verifyReferencesExist(model: Model<*>, reader: Reader<C, V>): Problem? {
    val reflected = ReflectedModel(model)
    try {
        reader.apply(reflected.populateRelations())
    } catch (e: NoSuchElementException) {
        return NotFoundProblem(e.message!!)
    }
    return null
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

public fun extractNameFromFunction(f: Function<Any>, pretty: Boolean = true): String {
    try {
        val kFunction = (f as KFunction<*>)
        if (kFunction.name == "execute") {
            return kFunction.toString().split(".execute").first().split(".")
                .last() // should probably lookup the algorithm in config and use that to find the name (primarily using annotations)
        }
        return try {
            kFunction.annotations.filterIsInstance<HumanReadable>().firstOrNull()?.name
                ?: if (pretty) camelCaseToPretty(kFunction.name) else kFunction.name
        } catch (e: Error) {
            if (pretty) camelCaseToPretty(kFunction.name) else kFunction.name
        }
    } catch (e: ClassCastException) {
        logger.warn { "Could not figure out function name. It is probably a lambda." }
        return "?"
    }
}

// why does Ktor have its own implementation of these?
internal fun String.encodeBase64(): String = Base64.getEncoder().encodeToString(this.toByteArray())
internal fun String.decodeBase64String(): String = String(Base64.getDecoder().decode(this))

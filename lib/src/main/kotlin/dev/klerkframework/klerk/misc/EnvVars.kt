package dev.klerkframework.klerk.misc

import kotlin.time.Duration

/** Shared helpers for the `fromEnvVars` builders on [dev.klerkframework.klerk.KlerkSettings] and its sub-settings. */

internal fun envBoolean(name: String): Boolean? {
    val value = System.getenv(name) ?: return null
    return value.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("Environment variable $name must be 'true' or 'false', was '$value'")
}

internal fun envInt(name: String): Int? {
    val value = System.getenv(name) ?: return null
    return value.toIntOrNull()
        ?: throw IllegalArgumentException("Environment variable $name must be an integer, was '$value'")
}

internal fun envDuration(name: String): Duration? {
    val value = System.getenv(name) ?: return null
    return try {
        Duration.parse(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Environment variable $name is not a valid duration: '$value'", e)
    }
}

internal inline fun <reified T : Enum<T>> envEnum(name: String): T? {
    val value = System.getenv(name) ?: return null
    return enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException(
        "Environment variable $name must be one of ${enumValues<T>().joinToString { it.name }}, was '$value'"
    )
}

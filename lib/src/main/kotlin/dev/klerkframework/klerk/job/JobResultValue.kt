package dev.klerkframework.klerk.job

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Encodes [value] for `JobResult.Success(result = ...)`, in the format [resultAs] decodes — the same JSON job cursors
 * use, so a result is a persisted schema in the same way a cursor is.
 */
public inline fun <reified T> encodeJobResult(value: T): String = encodeJobResult(value, serializer())

/** As [encodeJobResult], for a type whose serializer cannot be derived from the call site. */
public fun <T> encodeJobResult(value: T, serializer: KSerializer<T>): String =
    cursorJson.encodeToString(serializer, value)

/**
 * The child's [ChildOutcome.result] decoded as [T], or null if the child passed none or did not succeed. Only the
 * parent knows what its children return, which is why the type is given here rather than declared on the job type.
 *
 * @throws kotlinx.serialization.SerializationException if the result was not encoded as a [T]
 */
public inline fun <reified T> ChildOutcome.resultAs(): T? = resultAs(serializer<T>())

/** As [resultAs], for a type whose serializer cannot be derived from the call site. */
public fun <T> ChildOutcome.resultAs(serializer: KSerializer<T>): T? =
    result?.let { cursorJson.decodeFromString(serializer, it) }

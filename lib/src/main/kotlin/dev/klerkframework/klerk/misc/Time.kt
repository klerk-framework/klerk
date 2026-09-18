package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.decode64bitMicroseconds
import dev.klerkframework.klerk.to64bitMicroseconds
import kotlin.time.Instant

/**
 * Klerk uses 64bit microseconds for timestamps, but Instant has higher precision than that. When serializing and
 * deserializing, the result can differ from the original. This function removes precision to make the two equal.
 */
internal fun makeExactSerializable(instant: Instant): Instant = decode64bitMicroseconds(instant.to64bitMicroseconds())

/**
 * Get an Instant which is guaranteed to be the same after serialization and deserialization.
 */
internal fun getCurrentInstant(): Instant = makeExactSerializable(kotlin.time.Clock.System.now())

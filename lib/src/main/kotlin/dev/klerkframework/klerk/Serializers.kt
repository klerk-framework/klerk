package dev.klerkframework.klerk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * `kotlinx.serialization` support for [Instant] at Klerk's own precision.
 *
 * Klerk stores timestamps as 64-bit microseconds since 1970 (see [to64bitMicroseconds]), so encoding an instant any
 * more precisely than that would produce a value that does not survive a round-trip through storage. Annotate a
 * cursor property with `@Serializable(with = KlerkInstantSerializer::class)` to use it.
 */
public object KlerkInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.klerkframework.klerk.Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.to64bitMicroseconds())
    }

    override fun deserialize(decoder: Decoder): Instant = decode64bitMicroseconds(decoder.decodeLong())
}

/**
 * `kotlinx.serialization` support for [ModelID], so that it can appear in a job cursor (which nearly every cursor
 * needs). Registered on the type itself via `@Serializable(with = ...)`, so job authors write nothing.
 *
 * A model id is written as the bare integer it is. The type parameter is not part of the encoding — an id identifies a
 * model, and the model's own type is what says what it is.
 */
public object ModelIDSerializer : KSerializer<ModelID<*>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.klerkframework.klerk.ModelID", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: ModelID<*>) {
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): ModelID<*> = ModelID<Any>(decoder.decodeInt())
}

/** `kotlinx.serialization` support for [AttachedBlobID]. See [ModelIDSerializer]. */
public object AttachedBlobIDSerializer : KSerializer<AttachedBlobID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.klerkframework.klerk.AttachedBlobID", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: AttachedBlobID) {
        encoder.encodeInt(value.id)
    }

    override fun deserialize(decoder: Decoder): AttachedBlobID = AttachedBlobID(decoder.decodeInt())
}

/** `kotlinx.serialization` support for [AttachedStringID]. See [ModelIDSerializer]. */
public object AttachedStringIDSerializer : KSerializer<AttachedStringID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.klerkframework.klerk.AttachedStringID", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: AttachedStringID) {
        encoder.encodeInt(value.id)
    }

    override fun deserialize(decoder: Decoder): AttachedStringID = AttachedStringID(decoder.decodeInt())
}

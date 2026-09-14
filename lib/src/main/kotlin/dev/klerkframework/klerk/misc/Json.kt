package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.lang.reflect.InvocationTargetException
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.microseconds

/**
 * The JSON that model props and event parameters are stored as.
 *
 * Driven by each class's [ObjectSchema]: an object has exactly one key per constructor parameter, a [DataContainer] is
 * written as its value (regardless of authorization) and a [ModelID] as a number. Decoding is strict: an unknown key, a missing
 * key (also for a nullable property or one with a default value) or a value of the wrong type throws
 * [JsonMismatchException].
 */
internal object KlerkJson {

    fun encode(value: Any?): String = if (value == null) JsonNull.toString() else encodeToElement(value).toString()

    fun encodeToElement(value: Any): JsonObject = encodeObject(ObjectSchema.of(value::class), value)

    /** @throws JsonMismatchException if [json] does not match [kClass] exactly */
    fun <T : Any> decode(kClass: KClass<T>, json: String): T {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw JsonMismatchException("the stored value is not valid JSON")
        }
        @Suppress("UNCHECKED_CAST")
        return decodeObject(ObjectSchema.of(kClass), element, "") as T
    }

    /** @throws IllegalConfigurationException if [kClass] has a property whose type cannot be stored */
    fun requireStorable(kClass: KClass<*>) {
        ObjectSchema.of(kClass)
    }
}

/** Stored JSON that does not match its class. [reason] names the offending property but never includes its value. */
internal class JsonMismatchException(val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)

private fun mismatch(path: String, problem: String, cause: Throwable? = null): Nothing =
    throw JsonMismatchException(if (path.isEmpty()) "the stored value $problem" else "'$path' $problem", cause)

private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

private fun encodeObject(schema: ObjectSchema<*>, value: Any): JsonObject =
    JsonObject(schema.fields.associate { it.name to encodeValue(it.schemaType, it.get(value)) })

private fun encodeValue(type: SchemaType, value: Any?): JsonElement {
    if (value == null) {
        return JsonNull
    }
    return when (val shape = type.shape) {
        is Shape.Container -> encodeContainer(shape.kind, value as DataContainer<*>)
        Shape.Reference -> JsonPrimitive((value as ModelID<*>).value)
        Shape.BlobId -> JsonPrimitive((value as AttachedBlobID).value)
        Shape.StringId -> JsonPrimitive((value as AttachedStringID).value)
        is Shape.Many -> JsonArray((value as Collection<*>).map { encodeValue(shape.element, it) })
        is Shape.Nested -> encodeObject(shape.schema, value)
    }
}

private fun encodeContainer(kind: ContainerKind, container: DataContainer<*>): JsonElement {
    val value = container.rawValue
    return when (kind) {
        ContainerKind.AttachedBlob, ContainerKind.AttachedString -> JsonPrimitive((container as AttachedDataContainer<*>).rawId)
        ContainerKind.String -> JsonPrimitive(value as String)
        ContainerKind.Enum -> JsonPrimitive((container as EnumContainer<*>).value.name)
        ContainerKind.Int -> JsonPrimitive(value as Int)
        ContainerKind.Date -> JsonPrimitive((container as DateContainer).value.toEpochDay().toInt())
        ContainerKind.Long -> JsonPrimitive(value as Long)
        ContainerKind.Instant -> JsonPrimitive((container as InstantContainer).value.to64bitMicroseconds())
        ContainerKind.Duration -> JsonPrimitive((container as DurationContainer).value.inWholeMicroseconds)
        ContainerKind.Short -> JsonPrimitive(value as Short)
        ContainerKind.Byte -> JsonPrimitive(value as Byte)
        ContainerKind.ULong -> JsonPrimitive((value as ULong).toString())
        ContainerKind.UInt -> JsonPrimitive((value as UInt).toLong())
        ContainerKind.UShort -> JsonPrimitive((value as UShort).toInt())
        ContainerKind.UByte -> JsonPrimitive((value as UByte).toInt())
        ContainerKind.Float -> JsonPrimitive(value as Float)
        ContainerKind.Double -> JsonPrimitive(value as Double)
        ContainerKind.Boolean -> JsonPrimitive(value as Boolean)
        ContainerKind.Geo -> JsonPrimitive((container as GeoPositionContainer).value.uLongEncoded.toLong())
    }
}

private fun decodeObject(schema: ObjectSchema<*>, json: JsonElement, path: String): Any {
    val obj = json as? JsonObject ?: mismatch(path, "is ${describe(json)}, expected an object")
    val names = schema.fields.map { it.name }
    val unknown = obj.keys.filterNot { it in names }
    val missing = names.filterNot { it in obj }
    if (unknown.isNotEmpty() || missing.isNotEmpty()) {
        val problems = unknown.map { "'${join(path, it)}' is not a property of ${schema.kClass.simpleName}" } +
                missing.map { "'${join(path, it)}' is missing" }
        throw JsonMismatchException(problems.joinToString(", "))
    }
    val arguments = schema.fields.map { decodeValue(it.schemaType, obj.getValue(it.name), join(path, it.name)) }
    return construct(schema.kClass.simpleName, path) { schema.create(arguments) }
}

private fun decodeValue(type: SchemaType, json: JsonElement, path: String): Any? {
    if (json is JsonNull) {
        if (type.nullable) {
            return null
        }
        mismatch(path, "is null, but the property is not nullable")
    }
    return when (val shape = type.shape) {
        is Shape.Container ->
            construct(shape.kClass.simpleName, path) { shape.create(decodeContainerArgument(shape, json, path)) }

        Shape.Reference -> ModelID<Any>(json.int(path))
        Shape.BlobId -> AttachedBlobID(json.int(path))
        Shape.StringId -> AttachedStringID(json.int(path))
        is Shape.Many -> {
            val array = json as? JsonArray ?: mismatch(path, "is ${describe(json)}, expected an array")
            val items = array.mapIndexed { index, item -> decodeValue(shape.element, item, "$path[$index]") }
            if (shape.isSet) items.toSet() else items
        }

        is Shape.Nested -> decodeObject(shape.schema, json, path)
    }
}

/** What the container's constructor takes, decoded from the stored value. */
private fun decodeContainerArgument(shape: Shape.Container, json: JsonElement, path: String): Any =
    when (shape.kind) {
        ContainerKind.AttachedBlob -> AttachedBlobID(json.int(path))
        ContainerKind.AttachedString -> AttachedStringID(json.int(path))
        ContainerKind.String -> json.string(path)
        ContainerKind.Int -> json.int(path)
        ContainerKind.Long -> json.long(path)
        ContainerKind.Short -> json.short(path)
        ContainerKind.Byte -> json.byte(path)
        ContainerKind.ULong -> json.uLong(path)
        ContainerKind.UInt -> json.uInt(path)
        ContainerKind.UShort -> json.uShort(path)
        ContainerKind.UByte -> json.uByte(path)
        ContainerKind.Float -> json.float(path)
        ContainerKind.Double -> json.double(path)
        ContainerKind.Boolean -> json.boolean(path)
        ContainerKind.Enum -> {
            val name = json.string(path)
            shape.enumConstants.firstOrNull { it.name == name }
                ?: mismatch(path, "is '$name', which is not a constant of ${shape.enumClass?.simpleName}")
        }

        ContainerKind.Instant -> decode64bitMicroseconds(json.long(path))
        ContainerKind.Date -> LocalDate.ofEpochDay(json.int(path).toLong())
        ContainerKind.Duration -> json.long(path).microseconds
        ContainerKind.Geo -> GeoPosition(json.long(path).toULong())
    }

/**
 * Turns an exception thrown while creating a value (e.g. a failing `require` in `init`) into a mismatch. The
 * exception's message is left out of the reason, since it may contain the value; it is kept as the cause.
 */
private fun construct(className: String?, path: String, create: () -> Any?): Any =
    try {
        requireNotNull(create())
    } catch (e: JsonMismatchException) {
        throw e
    } catch (e: Exception) {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        mismatch(path, "could not be created as $className (${cause::class.simpleName})", cause)
    }

private fun describe(json: JsonElement): String = when (json) {
    is JsonNull -> "null"
    is JsonObject -> "an object"
    is JsonArray -> "an array"
    is JsonPrimitive -> when {
        json.isString -> "a string"
        json.booleanOrNull != null -> "a boolean"
        json.content.toLongOrNull() != null -> "an integer"
        else -> "a decimal number"
    }
}

private fun JsonElement.number(path: String, expected: String): JsonPrimitive {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || primitive.isString || primitive.booleanOrNull != null) {
        mismatch(path, "is ${describe(this)}, expected $expected")
    }
    return primitive
}

private fun JsonElement.int(path: String): Int {
    val content = number(path, "an integer").content
    return content.toIntOrNull()
        ?: mismatch(path, if (content.toLongOrNull() != null) "is too large for an Int" else "is ${describe(this)}, expected an integer")
}

private fun JsonElement.long(path: String): Long =
    number(path, "an integer").content.toLongOrNull() ?: mismatch(path, "is ${describe(this)}, expected an integer")

private fun JsonElement.short(path: String): Short {
    val content = number(path, "an integer").content
    return content.toShortOrNull()
        ?: mismatch(path, if (content.toLongOrNull() != null) "is too large for a Short" else "is ${describe(this)}, expected an integer")
}

private fun JsonElement.byte(path: String): Byte {
    val content = number(path, "an integer").content
    return content.toByteOrNull()
        ?: mismatch(path, if (content.toLongOrNull() != null) "is too large for a Byte" else "is ${describe(this)}, expected an integer")
}

private fun JsonElement.uLong(path: String): ULong {
    val primitive = this as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        mismatch(path, "is ${describe(this)}, expected a ULong encoded as a string")
    }
    return primitive.content.toULongOrNull() ?: mismatch(path, "is negative or too large for a ULong")
}

private fun JsonElement.uInt(path: String): UInt {
    val content = number(path, "an integer").content
    return content.toLongOrNull()?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }?.toUInt()
        ?: mismatch(path, "is negative or too large for a UInt")
}

private fun JsonElement.uShort(path: String): UShort {
    val content = number(path, "an integer").content
    return content.toIntOrNull()?.takeIf { it in 0..UShort.MAX_VALUE.toInt() }?.toUShort()
        ?: mismatch(path, "is negative or too large for a UShort")
}

private fun JsonElement.uByte(path: String): UByte {
    val content = number(path, "an integer").content
    return content.toIntOrNull()?.takeIf { it in 0..UByte.MAX_VALUE.toInt() }?.toUByte()
        ?: mismatch(path, "is negative or too large for a UByte")
}

private fun JsonElement.float(path: String): Float =
    number(path, "a number").content.toFloatOrNull() ?: mismatch(path, "is ${describe(this)}, expected a number")

private fun JsonElement.double(path: String): Double =
    number(path, "a number").content.toDoubleOrNull() ?: mismatch(path, "is ${describe(this)}, expected a number")

private fun JsonElement.boolean(path: String): Boolean =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: mismatch(path, "is ${describe(this)}, expected a boolean")

private fun JsonElement.string(path: String): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: mismatch(path, "is ${describe(this)}, expected a string")

package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.lang.reflect.InvocationTargetException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Duration.Companion.microseconds

/**
 * The JSON that model props and event parameters are stored as.
 *
 * Driven by each class's primary constructor: an object has exactly one key per constructor parameter, a
 * [DataContainer] is written as its `valueWithoutAuthorization` and a [ModelID] as a number. Decoding is strict: an
 * unknown key, a missing key (also for a nullable property or one with a default value) or a value of the wrong type
 * throws [JsonMismatchException].
 */
internal object KlerkJson {

    fun encode(value: Any?): String = if (value == null) JsonNull.toString() else encodeToElement(value).toString()

    fun encodeToElement(value: Any): JsonObject = classCodec(value::class).encode(value) as JsonObject

    /** @throws JsonMismatchException if [json] does not match [kClass] exactly */
    fun <T : Any> decode(kClass: KClass<T>, json: String): T {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw JsonMismatchException("the stored value is not valid JSON")
        }
        @Suppress("UNCHECKED_CAST")
        return classCodec(kClass).decode(element, "") as T
    }

    /** @throws IllegalConfigurationException if [kClass] has a property whose type cannot be stored */
    fun requireStorable(kClass: KClass<*>) {
        classCodec(kClass)
    }
}

/** Stored JSON that does not match its class. [reason] names the offending property but never includes its value. */
internal class JsonMismatchException(val reason: String) : RuntimeException(reason)

private val codecs = ConcurrentHashMap<KClass<*>, ClassCodec>()

private fun classCodec(
    kClass: KClass<*>,
    building: Set<KClass<*>> = emptySet(),
    where: String? = kClass.simpleName,
): ClassCodec {
    codecs[kClass]?.let { return it }
    if (kClass in building) {
        unsupported(where, "${kClass.simpleName} contains itself")
    }
    return ClassCodec(kClass, building + kClass, where).also { codecs.putIfAbsent(kClass, it) }
}

private fun isPlatformClass(kClass: KClass<*>): Boolean {
    val packageName = kClass.java.packageName
    return kClass.java.isPrimitive || packageName == "kotlin" ||
            listOf("kotlin.", "kotlinx.", "java.", "javax.").any { packageName.startsWith(it) }
}

private fun mismatch(path: String, problem: String): Nothing =
    throw JsonMismatchException(if (path.isEmpty()) "the stored value $problem" else "'$path' $problem")

private fun unsupported(where: String?, problem: String): Nothing =
    throw IllegalConfigurationException(
        KlerkErrorCode.PropertyMustBeDataContainer,
        "$where cannot be stored: $problem. Properties must be DataContainers, ModelIDs, List/Set thereof, or " +
                "classes made of these."
    )

private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

private interface Codec {
    fun encode(value: Any): JsonElement
    fun decode(json: JsonElement, path: String): Any
}

private class Typed(private val nullable: Boolean, private val codec: Codec) {
    fun encode(value: Any?): JsonElement = if (value == null) JsonNull else codec.encode(value)

    fun decode(json: JsonElement, path: String): Any? {
        if (json is JsonNull) {
            if (nullable) {
                return null
            }
            mismatch(path, "is null, but the property is not nullable")
        }
        return codec.decode(json, path)
    }
}

private fun typed(type: KType, where: String, building: Set<KClass<*>>): Typed {
    val kClass = type.classifier as? KClass<*> ?: unsupported(where, "its type is a type parameter")
    val codec: Codec = when {
        kClass == List::class || kClass == Set::class -> {
            if (type.toString().startsWith("kotlin.collections.Mutable")) {
                unsupported(where, "mutable collections are not allowed")
            }
            val elementType = type.arguments.single().type ?: unsupported(where, "star projections are not allowed")
            CollectionCodec(typed(elementType, where, building), isSet = kClass == Set::class)
        }

        kClass == ModelID::class -> IntCodec({ (it as ModelID<*>).value }, { ModelID<Any>(it) })
        kClass == AttachedBlobID::class -> IntCodec({ (it as AttachedBlobID).id }, { AttachedBlobID(it) })
        kClass == AttachedStringID::class -> IntCodec({ (it as AttachedStringID).id }, { AttachedStringID(it) })
        kClass.isSubclassOf(DataContainer::class) -> containerCodec(kClass, where)
        else -> classCodec(kClass, building, where)
    }
    return Typed(type.isMarkedNullable, codec)
}

private class ClassCodec(private val kClass: KClass<*>, building: Set<KClass<*>>, where: String?) : Codec {

    private class Field(val name: String, val property: KProperty1<Any, *>, val typed: Typed)

    private val constructor: KFunction<*>
    private val fields: List<Field>
    private val names: Set<String>

    init {
        val className = kClass.simpleName
        if (isPlatformClass(kClass)) {
            unsupported(where, "${kClass.qualifiedName} is neither a DataContainer nor a class made of DataContainers")
        }
        if (kClass.isAbstract || kClass.isSealed || kClass.java.isInterface) {
            unsupported(where, "$className is abstract")
        }
        constructor = kClass.primaryConstructor ?: unsupported(where, "$className has no primary constructor")
        constructor.isAccessible = true
        val isNested = building.size > 1
        if (isNested && constructor.parameters.isEmpty()) {
            unsupported(where, "$className has no constructor parameters")
        }
        val properties = kClass.memberProperties.associateBy { it.name }
        fields = constructor.parameters.map { parameter ->
            val name = requireNotNull(parameter.name)
            val where = "$className.$name"
            @Suppress("UNCHECKED_CAST")
            val property = properties[name] as KProperty1<Any, *>?
                ?: unsupported(where, "the constructor parameter is not a property")
            property.isAccessible = true
            Field(name, property, typed(parameter.type, where, building))
        }
        names = fields.map { it.name }.toSet()
    }

    override fun encode(value: Any): JsonElement =
        JsonObject(fields.associate { it.name to it.typed.encode(it.property.get(value)) })

    override fun decode(json: JsonElement, path: String): Any {
        val obj = json as? JsonObject ?: mismatch(path, "is ${describe(json)}, expected an object")
        val unknown = obj.keys.filterNot { it in names }
        val missing = fields.map { it.name }.filterNot { it in obj }
        if (unknown.isNotEmpty() || missing.isNotEmpty()) {
            val problems = unknown.map { "'${join(path, it)}' is not a property of ${kClass.simpleName}" } +
                    missing.map { "'${join(path, it)}' is missing" }
            throw JsonMismatchException(problems.joinToString(", "))
        }
        val arguments = fields.map { it.typed.decode(obj.getValue(it.name), join(path, it.name)) }
        return construct(kClass.simpleName, path) { constructor.call(*arguments.toTypedArray()) }
    }
}

private class CollectionCodec(private val element: Typed, private val isSet: Boolean) : Codec {
    override fun encode(value: Any): JsonElement = JsonArray((value as Collection<*>).map { element.encode(it) })

    override fun decode(json: JsonElement, path: String): Any {
        val array = json as? JsonArray ?: mismatch(path, "is ${describe(json)}, expected an array")
        val items = array.mapIndexed { index, item -> element.decode(item, "$path[$index]") }
        return if (isSet) items.toSet() else items
    }
}

private class IntCodec(private val toInt: (Any) -> Int, private val fromInt: (Int) -> Any) : Codec {
    override fun encode(value: Any): JsonElement = JsonPrimitive(toInt(value))
    override fun decode(json: JsonElement, path: String): Any = fromInt(json.int(path))
}

private class ContainerCodec(
    private val kClass: KClass<*>,
    private val constructor: KFunction<*>,
    private val encodeValue: (DataContainer<*>) -> JsonElement,
    private val decodeArgument: (JsonElement, String) -> Any,
) : Codec {
    override fun encode(value: Any): JsonElement = encodeValue(value as DataContainer<*>)

    override fun decode(json: JsonElement, path: String): Any =
        construct(kClass.simpleName, path) { constructor.call(decodeArgument(json, path)) }
}

private fun containerCodec(kClass: KClass<*>, where: String): Codec {
    if (kClass.isAbstract) {
        unsupported(where, "${kClass.simpleName} is abstract")
    }
    val constructor = kClass.primaryConstructor?.takeIf { it.parameters.size == 1 }
        ?: kClass.constructors.singleOrNull { it.parameters.size == 1 }
        ?: unsupported(where, "${kClass.simpleName} needs a constructor with a single parameter")
    constructor.isAccessible = true

    fun codec(encode: (DataContainer<*>) -> JsonElement, decode: (JsonElement, String) -> Any) =
        ContainerCodec(kClass, constructor, encode, decode)

    return when {
        kClass.isSubclassOf(AttachedBlobContainer::class) ->
            codec({ JsonPrimitive((it as AttachedDataContainer<*>).rawId) }) { json, path -> AttachedBlobID(json.int(path)) }

        kClass.isSubclassOf(AttachedStringContainer::class) ->
            codec({ JsonPrimitive((it as AttachedDataContainer<*>).rawId) }) { json, path -> AttachedStringID(json.int(path)) }

        kClass.isSubclassOf(StringContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as String) }) { json, path -> json.string(path) }

        kClass.isSubclassOf(IntContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Int) }) { json, path -> json.int(path) }

        kClass.isSubclassOf(LongContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Long) }) { json, path -> json.long(path) }

        kClass.isSubclassOf(ShortContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Short) }) { json, path -> json.short(path) }

        kClass.isSubclassOf(ByteContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Byte) }) { json, path -> json.byte(path) }

        kClass.isSubclassOf(ULongContainer::class) ->
            codec({ JsonPrimitive((it.valueWithoutAuthorization as ULong).toString()) }) { json, path -> json.uLong(path) }

        kClass.isSubclassOf(UIntContainer::class) ->
            codec({ JsonPrimitive((it.valueWithoutAuthorization as UInt).toLong()) }) { json, path -> json.uInt(path) }

        kClass.isSubclassOf(UShortContainer::class) ->
            codec({ JsonPrimitive((it.valueWithoutAuthorization as UShort).toInt()) }) { json, path -> json.uShort(path) }

        kClass.isSubclassOf(UByteContainer::class) ->
            codec({ JsonPrimitive((it.valueWithoutAuthorization as UByte).toInt()) }) { json, path -> json.uByte(path) }

        kClass.isSubclassOf(FloatContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Float) }) { json, path -> json.float(path) }

        kClass.isSubclassOf(DoubleContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Double) }) { json, path -> json.double(path) }

        kClass.isSubclassOf(BooleanContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Boolean) }) { json, path -> json.boolean(path) }

        kClass.isSubclassOf(EnumContainer::class) -> {
            val enumClass = constructor.parameters.single().type.classifier as KClass<*>
            val constants = enumClass.java.enumConstants.map { it as Enum<*> }
            codec({ JsonPrimitive(it.valueWithoutAuthorization as String) }) { json, path ->
                val name = json.string(path)
                constants.firstOrNull { it.name == name }
                    ?: mismatch(path, "is '$name', which is not a constant of ${enumClass.simpleName}")
            }
        }

        kClass.isSubclassOf(InstantContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Long) }) { json, path ->
                decode64bitMicroseconds(json.long(path))
            }

        kClass.isSubclassOf(DateContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Int) }) { json, path ->
                LocalDate.ofEpochDay(json.int(path).toLong())
            }

        kClass.isSubclassOf(DurationContainer::class) ->
            codec({ JsonPrimitive(it.valueWithoutAuthorization as Long) }) { json, path -> json.long(path).microseconds }

        kClass.isSubclassOf(GeoPositionContainer::class) ->
            codec({ JsonPrimitive((it.valueWithoutAuthorization as ULong).toLong()) }) { json, path ->
                GeoPosition(json.long(path).toULong())
            }

        else -> unsupported(where, "${kClass.simpleName} is a kind of DataContainer that cannot be stored")
    }
}

/** Turns an exception thrown while creating a value (e.g. a failing `require` in `init`) into a mismatch. */
private fun construct(className: String?, path: String, create: () -> Any?): Any =
    try {
        requireNotNull(create())
    } catch (e: JsonMismatchException) {
        throw e
    } catch (e: Exception) {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        mismatch(path, "could not be created as $className: ${cause.message}")
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

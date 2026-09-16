package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import java.lang.reflect.InvocationTargetException
import kotlinx.datetime.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

internal class SchemaType(
    val kClass: KClass<*>,
    val nullable: Boolean,
    val shape: Shape,
    val referencedModel: KClass<*>?,
) {

    /**
     * Checks what type erasure lets through, e.g. a `List<String>` for a `List<Title>`. The model a [ModelID] refers
     * to cannot be checked.
     *
     * @throws IllegalArgumentException if a field of this type cannot hold [value]
     */
    fun requireAccepts(value: Any?, path: String) {
        if (value == null) {
            require(nullable) { "'$path' is null, but it is not nullable" }
            return
        }
        require(kClass.isInstance(value)) { "'$path' must be a ${kClass.simpleName}, not a ${value::class.simpleName}" }
        if (shape is Shape.Many) {
            for ((index, element) in (value as Collection<*>).withIndex()) {
                shape.element.requireAccepts(element, "$path[$index]")
            }
        }
    }

    /**
     * A placeholder value of this type, e.g. to create an instance when only the declaration matters. Never use it as
     * data.
     */
    fun dummy(): Any? = if (nullable) null else when (shape) {
        is Shape.Container -> shape.dummy()
        Shape.Reference -> ModelID<Any>(0)
        Shape.BlobID -> AttachedBlobID(0)
        Shape.StringID -> AttachedStringID(0)
        is Shape.Many -> if (shape.isSet) emptySet<Any>() else emptyList<Any>()
        is Shape.Nested -> shape.schema.create(shape.schema.fields.map { it.schemaType.dummy() })
    }
}

internal sealed class Shape {

    class Container private constructor(
        val kClass: KClass<*>,
        val kind: ContainerKind,
        private val constructor: KFunction<*>,
    ) : Shape() {

        /** The enum class an [EnumContainer] holds; null for other kinds. */
        val enumClass: KClass<*>? =
            if (kind == ContainerKind.Enum) constructor.parameters.single().type.classifier as KClass<*> else null

        /** The constants of [enumClass]; empty for other kinds. */
        val enumConstants: List<Enum<*>> = enumClass?.java?.enumConstants?.map { it as Enum<*> } ?: emptyList()

        /**
         * Creates a container holding [argument], which is what the container's constructor takes, e.g. an
         * [kotlin.time.Instant] for an [InstantContainer].
         *
         * @throws IllegalArgumentException if [argument] has the wrong type
         */
        fun create(argument: Any): DataContainer<*> = try {
            constructor.call(argument) as DataContainer<*>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        fun dummy(): DataContainer<*> = create(
            kind.dummy ?: enumConstants.firstOrNull()
            ?: throw IllegalArgumentException("${kClass.simpleName} holds an enum without constants"),
        )

        /** Checks the validators of a placeholder instance; a class that cannot make one is checked when validating. */
        private fun requireNamedValidators() {
            val placeholder = try {
                dummy()
            } catch (e: Exception) {
                return
            }
            for (validator in placeholder.validators) {
                placeholder.nameOf(validator)
            }
        }

        companion object {
            private val containers = ConcurrentHashMap<KClass<*>, Container>()

            /** @throws IllegalConfigurationException if [kClass] is not a DataContainer that Klerk can create */
            fun of(kClass: KClass<*>, where: String? = kClass.simpleName): Container {
                containers[kClass]?.let { return it }
                if (kClass.isAbstract) {
                    unsupported(where, "${kClass.simpleName} is abstract")
                }
                if (!isPublic(kClass)) {
                    unsupported(where, "${kClass.simpleName} is not public")
                }
                val kind = ContainerKind.entries.firstOrNull { kClass.isSubclassOf(it.base) }
                    ?: unsupported(where, "${kClass.simpleName} is a kind of DataContainer that cannot be stored")
                val constructor = kClass.primaryConstructor?.takeIf { it.parameters.size == 1 }
                    ?: kClass.constructors.singleOrNull { it.parameters.size == 1 }
                    ?: unsupported(where, "${kClass.simpleName} needs a constructor with a single parameter")
                if (constructor.visibility != KVisibility.PUBLIC) {
                    unsupported(where, "the constructor of ${kClass.simpleName} is not public")
                }
                val container = Container(kClass, kind, constructor)
                container.requireNamedValidators()
                return container.also { containers.putIfAbsent(kClass, it) }
            }
        }
    }

    data object Reference : Shape()
    data object BlobID : Shape()
    data object StringID : Shape()
    class Many(val element: SchemaType, val isSet: Boolean) : Shape()
    class Nested(val schema: ObjectSchema<*>) : Shape()

    fun propertyType(): PropertyType? = when (this) {
        Reference -> PropertyType.Ref
        BlobID, StringID -> PropertyType.AttachedDataRef
        is Many, is Nested -> null
        is Container -> when (kind) {
            ContainerKind.AttachedBlob, ContainerKind.AttachedString -> PropertyType.AttachedDataRef
            ContainerKind.String -> PropertyType.String
            ContainerKind.Int -> PropertyType.Int
            ContainerKind.Long -> PropertyType.Long
            ContainerKind.Float -> PropertyType.Float
            ContainerKind.Boolean -> PropertyType.Boolean
            ContainerKind.Enum -> PropertyType.Enum
            ContainerKind.Instant -> PropertyType.Instant
            ContainerKind.Date -> PropertyType.Date
            ContainerKind.Duration -> PropertyType.Duration
            ContainerKind.Geo -> PropertyType.Geo
            ContainerKind.Short -> PropertyType.Short
            ContainerKind.Byte -> PropertyType.Byte
            ContainerKind.UInt -> PropertyType.UInt
            ContainerKind.ULong -> PropertyType.ULong
            ContainerKind.UShort -> PropertyType.UShort
            ContainerKind.UByte -> PropertyType.UByte
            ContainerKind.Double -> PropertyType.Double
        }
    }
}

/** The kinds of [DataContainer] Klerk can store, with a placeholder for what each one's constructor takes. */
internal enum class ContainerKind(val base: KClass<out DataContainer<*>>, val dummy: Any?) {
    AttachedBlob(AttachedBlobContainer::class, AttachedBlobID(0)),
    AttachedString(AttachedStringContainer::class, AttachedStringID(0)),
    String(StringContainer::class, ""),
    Int(IntContainer::class, 0),
    Long(LongContainer::class, 0L),
    Short(ShortContainer::class, 0.toShort()),
    Byte(ByteContainer::class, 0.toByte()),
    ULong(ULongContainer::class, 0uL),
    UInt(UIntContainer::class, 0u),
    UShort(UShortContainer::class, 0.toUShort()),
    UByte(UByteContainer::class, 0.toUByte()),
    Float(FloatContainer::class, 0f),
    Double(DoubleContainer::class, 0.0),
    Boolean(BooleanContainer::class, false),
    Enum(EnumContainer::class, null),
    Instant(InstantContainer::class, kotlin.time.Instant.fromEpochSeconds(0)),
    Date(DateContainer::class, LocalDate.fromEpochDays(0)),
    Duration(DurationContainer::class, kotlin.time.Duration.ZERO),
    Geo(GeoPositionContainer::class, GeoPosition(0.0, 0.0)),
}

package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * The structure of a model props or event parameters class (or a class nested in one): its primary constructor and,
 * for each constructor parameter, the property holding it and the kind of value it is.
 *
 * This is the only place where Klerk reflects over such classes. A schema is built once per class and used by
 * everything that looks inside the objects: storage, validation, property authorization, relations and attached data.
 * The specification builds the schema of every model and parameters class, so a class that Klerk cannot handle is
 * rejected at startup instead of being partly ignored at runtime.
 */
internal class ObjectSchema private constructor(val kClass: KClass<*>, building: Set<KClass<*>>, where: String?) {

    private val constructor: KFunction<Any>
    val fields: List<SchemaField>

    init {
        val className = kClass.simpleName
        if (isPlatformClass(kClass)) {
            unsupported(where, "${kClass.qualifiedName} is neither a DataContainer nor a class made of DataContainers")
        }
        if (kClass.isAbstract || kClass.isSealed || kClass.java.isInterface) {
            unsupported(where, "$className is abstract")
        }
        @Suppress("UNCHECKED_CAST")
        constructor = kClass.primaryConstructor as KFunction<Any>? ?: unsupported(where, "$className has no primary constructor")
        if (!constructor.visibility.isReadable()) {
            unsupported(where, "the primary constructor of $className is not public")
        }
        constructor.isAccessible = true
        if (building.size > 1 && constructor.parameters.isEmpty()) {
            unsupported(where, "$className has no constructor parameters")
        }
        val properties = kClass.memberProperties.associateBy { it.name }
        fields = constructor.parameters.map { parameter ->
            val name = requireNotNull(parameter.name)
            val fieldWhere = "$className.$name"
            @Suppress("UNCHECKED_CAST")
            val property = properties[name] as KProperty1<Any, *>?
                ?: unsupported(fieldWhere, "the constructor parameter is not a property")
            if (property is KMutableProperty<*>) {
                unsupported(fieldWhere, "it is a var, but properties must be immutable (val)")
            }
            if (!property.visibility.isReadable()) {
                unsupported(fieldWhere, "it is not public")
            }
            property.isAccessible = true
            SchemaField(kClass, name, property, schemaType(parameter.type, fieldWhere, building + kClass))
        }
    }

    /** Creates an instance from one argument per field, in the order of [fields]. */
    fun create(arguments: List<Any?>): Any = constructor.call(*arguments.toTypedArray())

    /**
     * Calls [action] for every non-null [DataContainer], [ModelID], [AttachedBlobID] and [AttachedStringID] in
     * [value], including those in collections and nested objects.
     */
    fun forEachLeaf(value: Any, action: (Leaf) -> Unit) {
        forEachLeaf(value, "", action)
    }

    private fun forEachLeaf(value: Any, prefix: String, action: (Leaf) -> Unit) {
        fields.forEach { field ->
            val fieldValue = field.get(value) ?: return@forEach
            visit(field, field.type.shape, fieldValue, join(prefix, field.name), action)
        }
    }

    private fun visit(field: SchemaField, shape: Shape, value: Any, path: String, action: (Leaf) -> Unit) {
        when (shape) {
            is Shape.Many -> (value as Collection<*>).forEachIndexed { index, element ->
                element?.let { visit(field, shape.element.shape, it, "$path[$index]", action) }
            }

            is Shape.Nested -> shape.schema.forEachLeaf(value, path, action)
            else -> action(Leaf(path, field, value))
        }
    }

    /** Every place a leaf (see [forEachLeaf]) can be, with collections and nested objects unwrapped. */
    fun leafFields(): List<LeafField> = leafFields("")

    private fun leafFields(prefix: String): List<LeafField> = fields.flatMap { field ->
        fun unwrap(shape: Shape, path: String): List<LeafField> = when (shape) {
            is Shape.Many -> unwrap(shape.element.shape, path)
            is Shape.Nested -> shape.schema.leafFields(path)
            else -> listOf(LeafField(path, field, shape))
        }
        unwrap(field.type.shape, join(prefix, field.name))
    }

    /**
     * Returns [value] with every leaf (see [forEachLeaf]) replaced by what [transform] returns for it. Objects and
     * collections are only rebuilt if something in them changed, so [value] itself is returned if nothing did.
     */
    fun transformLeaves(value: Any, transform: (Any) -> Any): Any {
        var changed = false
        val arguments = fields.map { field ->
            val current = field.get(value)
            val transformed = current?.let { transformValue(field.type.shape, it, transform) }
            if (transformed !== current) {
                changed = true
            }
            transformed
        }
        return if (changed) create(arguments) else value
    }

    private fun transformValue(shape: Shape, value: Any, transform: (Any) -> Any): Any = when (shape) {
        is Shape.Many -> {
            var changed = false
            val items = (value as Collection<*>).map { element ->
                element?.let { transformValue(shape.element.shape, it, transform) }
                    .also { if (it !== element) changed = true }
            }
            if (!changed) value else if (shape.isSet) items.toSet() else items
        }

        is Shape.Nested -> shape.schema.transformLeaves(value, transform)
        else -> transform(value)
    }

    companion object {
        private val schemas = ConcurrentHashMap<KClass<*>, ObjectSchema>()

        /** @throws IllegalConfigurationException if [kClass], or a class it is made of, cannot be handled by Klerk */
        fun of(kClass: KClass<*>): ObjectSchema = of(kClass, emptySet(), kClass.simpleName)

        private fun of(kClass: KClass<*>, building: Set<KClass<*>>, where: String?): ObjectSchema {
            schemas[kClass]?.let { return it }
            if (kClass in building) {
                unsupported(where, "${kClass.simpleName} contains itself")
            }
            return ObjectSchema(kClass, building + kClass, where).also { schemas.putIfAbsent(kClass, it) }
        }

        private fun schemaType(type: KType, where: String, building: Set<KClass<*>>): SchemaType {
            val kClass = type.classifier as? KClass<*> ?: unsupported(where, "its type is a type parameter")
            val shape = when {
                kClass == List::class || kClass == Set::class -> {
                    if (type.toString().startsWith("kotlin.collections.Mutable")) {
                        unsupported(where, "mutable collections are not allowed")
                    }
                    val elementType =
                        type.arguments.single().type ?: unsupported(where, "star projections are not allowed")
                    Shape.Many(schemaType(elementType, where, building), isSet = kClass == Set::class)
                }

                kClass == ModelID::class -> Shape.Reference
                kClass == AttachedBlobID::class -> Shape.BlobId
                kClass == AttachedStringID::class -> Shape.StringId
                kClass.isSubclassOf(DataContainer::class) -> Shape.Container.of(kClass, where)
                else -> Shape.Nested(of(kClass, building, where))
            }
            return SchemaType(type.isMarkedNullable, shape)
        }
    }
}

/** A constructor parameter of [owner] and the property that holds it. */
internal class SchemaField(
    val owner: KClass<*>,
    val name: String,
    private val property: KProperty1<Any, *>,
    val type: SchemaType,
) {
    val key: PropertyKey = PropertyKey(owner, name)

    fun get(value: Any): Any? = property.get(value)
}

internal class SchemaType(val nullable: Boolean, val shape: Shape) {

    /**
     * A placeholder value of this type, e.g. to create an instance when only the declaration matters. Never use it as
     * data.
     */
    fun dummy(): Any? = if (nullable) null else when (shape) {
        is Shape.Container -> shape.dummy()
        Shape.Reference -> ModelID<Any>(0)
        Shape.BlobId -> AttachedBlobID(0)
        Shape.StringId -> AttachedStringID(0)
        is Shape.Many -> if (shape.isSet) emptySet<Any>() else emptyList<Any>()
        is Shape.Nested -> shape.schema.create(shape.schema.fields.map { it.type.dummy() })
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
         * @param argument what the container's constructor takes, e.g. an [kotlin.time.Instant] for an
         * [InstantContainer]
         */
        fun create(argument: Any): DataContainer<*> = constructor.call(argument) as DataContainer<*>

        fun dummy(): DataContainer<*> = create(
            kind.dummy ?: enumConstants.firstOrNull()
            ?: throw IllegalArgumentException("${kClass.simpleName} holds an enum without constants")
        )

        companion object {
            private val containers = ConcurrentHashMap<KClass<*>, Container>()

            /** @throws IllegalConfigurationException if [kClass] is not a DataContainer that Klerk can create */
            fun of(kClass: KClass<*>, where: String? = kClass.simpleName): Container {
                containers[kClass]?.let { return it }
                if (kClass.isAbstract) {
                    unsupported(where, "${kClass.simpleName} is abstract")
                }
                val kind = ContainerKind.entries.firstOrNull { kClass.isSubclassOf(it.base) }
                    ?: unsupported(where, "${kClass.simpleName} is a kind of DataContainer that cannot be stored")
                val constructor = kClass.primaryConstructor?.takeIf { it.parameters.size == 1 }
                    ?: kClass.constructors.singleOrNull { it.parameters.size == 1 }
                    ?: unsupported(where, "${kClass.simpleName} needs a constructor with a single parameter")
                if (!constructor.visibility.isReadable()) {
                    unsupported(where, "the constructor of ${kClass.simpleName} is not public")
                }
                constructor.isAccessible = true
                return Container(kClass, kind, constructor).also { containers.putIfAbsent(kClass, it) }
            }
        }
    }

    data object Reference : Shape()
    data object BlobId : Shape()
    data object StringId : Shape()
    class Many(val element: SchemaType, val isSet: Boolean) : Shape()
    class Nested(val schema: ObjectSchema) : Shape()
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
    Date(DateContainer::class, LocalDate.ofEpochDay(0)),
    Duration(DurationContainer::class, kotlin.time.Duration.ZERO),
    Geo(GeoPositionContainer::class, GeoPosition(0.0, 0.0)),
}

/** A value found by [ObjectSchema.forEachLeaf]. [path] is e.g. `address.street` or `tags[1]`. */
internal class Leaf(val path: String, val field: SchemaField, val value: Any)

/** A place found by [ObjectSchema.leafFields]. [path] is e.g. `address.street` or `tags`. */
internal class LeafField(val path: String, val field: SchemaField, val shape: Shape)

/** Identifies a property of a class, whether it is a property reference in the DSL or a [SchemaField]. */
internal data class PropertyKey(val owner: KClass<*>, val name: String) {
    override fun toString(): String = "${owner.simpleName}::$name"

    companion object {
        /** @throws IllegalArgumentException if [property] is not an unbound reference such as `MyParams::name` */
        fun of(property: KProperty1<*, *>): PropertyKey {
            val owner = property.instanceParameter?.type?.classifier as? KClass<*>
                ?: throw IllegalArgumentException("Refer to '${property.name}' as MyClass::${property.name}")
            return PropertyKey(owner, property.name)
        }
    }
}

private fun KVisibility?.isReadable(): Boolean = this == KVisibility.PUBLIC || this == KVisibility.INTERNAL

private fun isPlatformClass(kClass: KClass<*>): Boolean {
    val packageName = kClass.java.packageName
    return kClass.java.isPrimitive || packageName == "kotlin" ||
            listOf("kotlin.", "kotlinx.", "java.", "javax.").any { packageName.startsWith(it) }
}

private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

private fun unsupported(where: String?, problem: String): Nothing =
    throw IllegalConfigurationException(
        KlerkErrorCode.PropertyMustBeDataContainer,
        "$where cannot be used by Klerk: $problem. Properties must be public vals of DataContainers, ModelIDs, " +
                "List/Set thereof, or classes made of these, and every class must have a public primary constructor."
    )

package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import java.lang.reflect.InvocationTargetException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * The structure of a model props or event parameters class (or a class nested in one), as Klerk sees it: one
 * [SchemaField] per primary constructor parameter, in declaration order.
 *
 * Use it instead of reflection when you need to handle such classes generically, e.g. to render a form or serialize a
 * model. Klerk builds the schema of every model and parameters class at startup, so a class that Klerk cannot handle
 * is rejected there.
 *
 * ```kotlin
 * val schema = ObjectSchema.of(CreateBookParams::class)
 * schema.fields.forEach { println("${it.name}: ${it.type}") }
 * val params = schema.create(mapOf("title" to BookTitle("Dune")))
 * ```
 */
public class ObjectSchema<T : Any> private constructor(
    public val kClass: KClass<T>,
    building: Set<KClass<*>>,
    where: String?,
) {

    private val constructor: KFunction<T>

    /** One per primary constructor parameter, in declaration order. */
    public val fields: List<SchemaField>

    init {
        val className = kClass.simpleName
        if (isPlatformClass(kClass)) {
            unsupported(where, "${kClass.qualifiedName} is neither a DataContainer nor a class made of DataContainers")
        }
        if (kClass.isAbstract || kClass.isSealed || kClass.java.isInterface) {
            unsupported(where, "$className is abstract")
        }
        constructor = kClass.primaryConstructor ?: unsupported(where, "$className has no primary constructor")
        if (!constructor.visibility.isReadable()) {
            unsupported(where, "the primary constructor of $className is not public")
        }
        constructor.isAccessible = true
        if (building.size > 1 && constructor.parameters.isEmpty()) {
            unsupported(where, "$className has no constructor parameters")
        }
        val properties = kClass.memberProperties
        properties.firstOrNull { it is KMutableProperty<*> }?.let {
            unsupported("$className.${it.name}", "it is a var, but properties must be immutable (val)")
        }
        val propertiesByName = properties.associateBy { it.name }
        fields = constructor.parameters.map { parameter ->
            val name = requireNotNull(parameter.name)
            val fieldWhere = "$className.$name"
            @Suppress("UNCHECKED_CAST")
            val property = propertiesByName[name] as KProperty1<Any, *>?
                ?: unsupported(fieldWhere, "the constructor parameter is not a property")
            if (!property.visibility.isReadable()) {
                unsupported(fieldWhere, "it is not public")
            }
            property.isAccessible = true
            SchemaField(this, parameter, property, schemaType(parameter.type, fieldWhere, building + kClass))
        }
    }

    /** @return the field called [name], or null if there is none */
    public fun field(name: String): SchemaField? = fields.firstOrNull { it.name == name }

    /**
     * Creates an instance from values keyed by field name. A field that is left out gets the default value of its
     * constructor parameter.
     *
     * @throws IllegalArgumentException if a name is unknown, a field without a default value is left out, or a value
     * has the wrong type
     */
    public fun create(values: Map<String, Any?>): T {
        val unknown = values.keys.filter { field(it) == null }
        require(unknown.isEmpty()) { "${kClass.simpleName} has no ${unknown.joinToString(", ")}" }
        val missing = fields.filter { it.isRequired && it.name !in values }
        require(missing.isEmpty()) { "${missing.joinToString(", ") { it.name }} is missing" }
        return callConstructor(fields.filter { it.name in values }.associate { it.parameter to values[it.name] })
    }

    /**
     * Decodes an instance from the JSON Klerk stores it as: an object with exactly one key per field, a
     * [DataContainer] as its `valueWithoutAuthorization` and a [ModelID] as a number.
     *
     * @throws IllegalArgumentException if a key is unknown or missing (also for a nullable field or one with a default
     * value), or a value has the wrong type
     */
    public fun fromJson(json: String): T = try {
        KlerkJson.decode(kClass, json)
    } catch (e: JsonMismatchException) {
        throw IllegalArgumentException("Invalid JSON for ${kClass.simpleName}: ${e.reason}")
    }

    /** Creates an instance from one argument per field, in the order of [fields]. */
    internal fun create(arguments: List<Any?>): T = constructor.call(*arguments.toTypedArray())

    /** What the constructor's own default expression for [field] evaluates to, with dummies for the other fields. */
    internal fun kotlinDefaultOf(field: SchemaField): Any? {
        val arguments = fields.filter { it !== field && it.isRequired }.associate { it.parameter to it.schemaType.dummy() }
        return field.get(callConstructor(arguments))
    }

    private fun callConstructor(arguments: Map<KParameter, Any?>): T = try {
        constructor.callBy(arguments)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    /**
     * Calls [action] for every non-null [DataContainer], [ModelID], [AttachedBlobID] and [AttachedStringID] in
     * [value], including those in collections and nested objects.
     */
    internal fun forEachLeaf(value: Any, action: (Leaf) -> Unit) {
        forEachLeaf(value, "", action)
    }

    private fun forEachLeaf(value: Any, prefix: String, action: (Leaf) -> Unit) {
        fields.forEach { field ->
            val fieldValue = field.get(value) ?: return@forEach
            visit(field, field.schemaType.shape, fieldValue, join(prefix, field.name), action)
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
    internal fun leafFields(): List<LeafField> = leafFields("")

    private fun leafFields(prefix: String): List<LeafField> = fields.flatMap { field ->
        fun unwrap(shape: Shape, path: String): List<LeafField> = when (shape) {
            is Shape.Many -> unwrap(shape.element.shape, path)
            is Shape.Nested -> shape.schema.leafFields(path)
            else -> listOf(LeafField(path, field, shape))
        }
        unwrap(field.schemaType.shape, join(prefix, field.name))
    }

    /**
     * Returns [value] with every leaf (see [forEachLeaf]) replaced by what [transform] returns for it. Objects and
     * collections are only rebuilt if something in them changed, so [value] itself is returned if nothing did.
     */
    internal fun transformLeaves(value: Any, transform: (Any) -> Any): Any {
        var changed = false
        val arguments = fields.map { field ->
            val current = field.get(value)
            val transformed = current?.let { transformValue(field.schemaType.shape, it, transform) }
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

    public companion object {
        private val schemas = ConcurrentHashMap<KClass<*>, ObjectSchema<*>>()

        /** @throws IllegalConfigurationException if [kClass], or a class it is made of, cannot be handled by Klerk */
        public fun <T : Any> of(kClass: KClass<T>): ObjectSchema<T> = of(kClass, emptySet(), kClass.simpleName)

        private fun <T : Any> of(kClass: KClass<T>, building: Set<KClass<*>>, where: String?): ObjectSchema<T> {
            @Suppress("UNCHECKED_CAST")
            schemas[kClass]?.let { return it as ObjectSchema<T> }
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
            val referencedModel =
                if (kClass == ModelID::class) type.arguments.singleOrNull()?.type?.classifier as? KClass<*> else null
            return SchemaType(kClass, type.isMarkedNullable, shape, referencedModel)
        }
    }
}

/**
 * A field of an [ObjectSchema]: a primary constructor parameter and the property that holds it.
 */
public class SchemaField internal constructor(
    private val schema: ObjectSchema<*>,
    internal val parameter: KParameter,
    private val kProperty: KProperty1<Any, *>,
    internal val schemaType: SchemaType,
) {
    public val name: String = kProperty.name

    /** The name un-camel-cased, e.g. `First name` for `firstName`. */
    public val prettyName: String = camelCaseToPretty(name)

    /** The property, e.g. to compare it with a property reference such as `MyParams::title`. */
    public val property: KProperty1<*, *> get() = kProperty

    public val isNullable: Boolean = schemaType.nullable

    /** False if the constructor parameter has a default value. */
    public val isRequired: Boolean = !parameter.isOptional

    /** True if the field is a `List` or a `Set`. [valueClass], [type] etc. then describe its elements. */
    public val isCollection: Boolean = schemaType.shape is Shape.Many

    private val elementType: SchemaType = (schemaType.shape as? Shape.Many)?.element ?: schemaType

    /** The class of the value, e.g. a [DataContainer] subclass or [ModelID]. For a collection, the class of its elements. */
    public val valueClass: KClass<*> = elementType.kClass

    /** The kind of value, or null for a collection, a nested object or a container kind that has none. */
    public val type: PropertyType? = if (isCollection) null else elementType.shape.propertyType()

    /** The model class a [ModelID] (or a collection of them) refers to, or null if it is not a reference. */
    public val referencedModel: KClass<*>? = elementType.referencedModel

    /** The constants of the enum an [EnumContainer] (or a collection of them) holds; empty for other fields. */
    public val enumConstants: List<Enum<*>> = (elementType.shape as? Shape.Container)?.enumConstants ?: emptyList()

    internal val owner: KClass<*> get() = schema.kClass

    internal val key: PropertyKey = PropertyKey(schema.kClass, name)

    /** The value of this field in [instance], which must be an instance of the schema's class. */
    public fun get(instance: Any): Any? = kProperty.get(instance)

    /**
     * Creates the field's [DataContainer] around [value], which is what the container's constructor takes, e.g. an
     * `Instant` for an [InstantContainer] or an enum constant for an [EnumContainer].
     *
     * @throws IllegalArgumentException if the field is not a [DataContainer] or [value] has the wrong type
     */
    public fun createContainer(value: Any): DataContainer<*> = containerShape().create(value)

    /**
     * A placeholder instance of the field's [DataContainer], e.g. to read its validation rules. Never use it as data.
     *
     * @throws IllegalArgumentException if the field is not a [DataContainer]
     */
    public fun dummyContainer(): DataContainer<*> = containerShape().dummy()

    /** The container's validation rules, described for humans, e.g. `min length` to `1`. Empty if not a container. */
    public val validationRulesDescriptions: Map<String, String> by lazy {
        (elementType.shape as? Shape.Container)?.dummy()?.let { describeRules(it) } ?: emptyMap()
    }

    /** The container's [DataContainer.recommendedDefault], or null if it has none or is not a container. */
    public val recommendedDefaultValue: Any? by lazy { (elementType.shape as? Shape.Container)?.dummy()?.recommendedDefault }

    /**
     * What the Kotlin default expression of the constructor parameter evaluates to (e.g. `= Score(0)`), or null if it
     * has none, is not a [DataContainer] or could not be evaluated.
     *
     * It is evaluated by calling the constructor with placeholders for the other fields, so the class's `init` blocks
     * and the default expression run. Takes precedence over [recommendedDefaultValue], since it is specific to this
     * field.
     */
    public val kotlinDefaultInstance: DataContainer<*>? by lazy {
        if (isRequired) {
            return@lazy null
        }
        try {
            schema.kotlinDefaultOf(this) as? DataContainer<*>
        } catch (e: Exception) {
            logger.warn(e) { "Could not evaluate the Kotlin default value of '$name'" }
            null
        }
    }

    private fun containerShape(): Shape.Container =
        schemaType.shape as? Shape.Container ?: throw IllegalArgumentException("'$name' is not a DataContainer")

    override fun toString(): String = key.toString()
}

/** The kind of a field, as used by generic tooling such as forms. */
public enum class PropertyType {
    String,
    Int,
    Long,
    Float,
    Boolean,
    Ref,
    AttachedDataRef,
    Enum,
    Instant,
    Date,
    Duration,
    Geo,
}

internal class SchemaType(
    val kClass: KClass<*>,
    val nullable: Boolean,
    val shape: Shape,
    val referencedModel: KClass<*>?,
) {

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
         * @param argument what the container's constructor takes, e.g. an [kotlin.time.Instant] for an
         * [InstantContainer]
         * @throws IllegalArgumentException if [argument] has the wrong type
         */
        fun create(argument: Any): DataContainer<*> = try {
            constructor.call(argument) as DataContainer<*>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

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
    class Nested(val schema: ObjectSchema<*>) : Shape()

    fun propertyType(): PropertyType? = when (this) {
        Reference -> PropertyType.Ref
        BlobId, StringId -> PropertyType.AttachedDataRef
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
            ContainerKind.Short, ContainerKind.Byte, ContainerKind.ULong, ContainerKind.UInt, ContainerKind.UShort,
            ContainerKind.UByte, ContainerKind.Double -> null
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

private fun describeRules(container: DataContainer<*>): Map<String, String> {
    val rules = mutableMapOf<String, String>()
    when (container) {
        is StringContainer -> {
            rules["min length"] = container.minLength.toString()
            rules["max length"] = container.maxLength.toString()
            container.regexPattern?.let { rules["pattern"] = it }
        }

        is IntContainer -> {
            rules["min"] = container.min.toString()
            rules["max"] = container.max.toString()
        }

        is LongContainer -> {
            rules["min"] = container.min.toString()
            rules["max"] = container.max.toString()
        }

        is FloatContainer -> {
            rules["min"] = container.min.toString()
            rules["max"] = container.max.toString()
        }

        else -> Unit
    }
    rules["validator"] = container.validators.joinToString(", ") { validatorName(it) }
    return rules
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

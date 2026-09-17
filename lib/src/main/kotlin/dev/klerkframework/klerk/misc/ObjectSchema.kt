package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

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
    /** The described class. */
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
        if (!isPublic(kClass)) {
            unsupported(where, "$className is not public")
        }
        constructor = kClass.primaryConstructor ?: unsupported(where, "$className has no primary constructor")
        if (constructor.visibility != KVisibility.PUBLIC) {
            unsupported(where, "the primary constructor of $className is not public")
        }
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
            if (property.visibility != KVisibility.PUBLIC) {
                unsupported(fieldWhere, "it is not public")
            }
            SchemaField(this, parameter, property, schemaType(parameter.type, fieldWhere, building + kClass))
        }
        if (kClass.isSubclassOf(Validatable::class)) {
            requireNamedValidators()
        }
    }

    /** Checks the validators of a placeholder instance; a class that cannot make one is checked when validating. */
    private fun requireNamedValidators() {
        val placeholder = try {
            create(fields.map { it.schemaType.dummy() }) as Validatable
        } catch (e: Exception) {
            return
        }
        for (validator in placeholder.validators()) {
            requireNamedRule(validator, "A validator of ${kClass.simpleName}")
        }
    }

    /** The field called [name], or null if there is none. */
    public fun field(name: String): SchemaField? = fields.firstOrNull { it.name == name }

    override fun toString(): String = "ObjectSchema(${kClass.simpleName}: ${fields.joinToString(", ") { it.name }})"

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
        for ((name, value) in values) {
            requireNotNull(field(name)).schemaType.requireAccepts(value, name)
        }
        return callConstructor(fields.filter { it.name in values }.associate { it.parameter to values[it.name] })
    }

    /**
     * Decodes an instance from the JSON Klerk stores it as: an object with exactly one key per field, a
     * [DataContainer] as its value and a [ModelID] as a number.
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
        val arguments = fields
            .filter { it !== field && it.isRequired }
            .associate { it.parameter to it.schemaType.dummy() }
        return field.get(callConstructor(arguments))
    }

    private fun callConstructor(arguments: Map<KParameter, Any?>): T = try {
        constructor.callBy(arguments)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    /**
     * Every non-null [DataContainer], [ModelID], [AttachedBlobID] and [AttachedStringID] in [value], including those in
     * collections and nested objects, in declaration order.
     */
    public fun leaves(value: Any): Sequence<Leaf> = sequence { yieldLeaves(value, "") }

    private suspend fun SequenceScope<Leaf>.yieldLeaves(value: Any, prefix: String) {
        for (field in fields) {
            val fieldValue = field.get(value) ?: continue
            yieldLeaf(field, field.schemaType.shape, fieldValue, join(prefix, field.name))
        }
    }

    private suspend fun SequenceScope<Leaf>.yieldLeaf(field: SchemaField, shape: Shape, value: Any, path: String) {
        when (shape) {
            is Shape.Many -> for ((index, element) in (value as Collection<*>).withIndex()) {
                if (element != null) {
                    yieldLeaf(field, shape.element.shape, element, "$path[$index]")
                }
            }

            is Shape.Nested -> with(shape.schema) { yieldLeaves(value, path) }
            else -> yield(Leaf(path, field, value))
        }
    }

    /** Every place a leaf (see [leaves]) can be, with collections and nested objects unwrapped. */
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
     * Returns [value] with every leaf (see [leaves]) replaced by what [transform] returns for it. Objects and
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
                kClass == AttachedBlobID::class -> Shape.BlobID
                kClass == AttachedStringID::class -> Shape.StringID
                kClass.isSubclassOf(DataContainer::class) -> Shape.Container.of(kClass, where)
                else -> Shape.Nested(of(kClass, building, where))
            }
            val referencedModel =
                if (kClass == ModelID::class) type.arguments.singleOrNull()?.type?.classifier as? KClass<*> else null
            return SchemaType(kClass, type.isMarkedNullable, shape, referencedModel)
        }
    }
}

/** True if [kClass] and every class it is nested in are public. */
internal fun isPublic(kClass: KClass<*>): Boolean =
    generateSequence(kClass) { it.java.enclosingClass?.kotlin }.all { it.visibility == KVisibility.PUBLIC }

private fun isPlatformClass(kClass: KClass<*>): Boolean {
    val packageName = kClass.java.packageName
    return kClass.java.isPrimitive || packageName == "kotlin" ||
            listOf("kotlin.", "kotlinx.", "java.", "javax.").any { packageName.startsWith(it) }
}

private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

internal fun unsupported(where: String?, problem: String): Nothing =
    throw IllegalConfigurationException(
        KlerkErrorCode.PropertyMustBeDataContainer,
        "$where cannot be used by Klerk: $problem. Properties must be vals of DataContainers, ModelIDs, List/Set " +
                "thereof, or classes made of these, and every class, primary constructor and property must be public.",
    )

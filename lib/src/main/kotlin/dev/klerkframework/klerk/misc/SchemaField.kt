package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.instanceParameter

/**
 * A field of an [ObjectSchema]: a primary constructor parameter and the property that holds it.
 */
public class SchemaField internal constructor(
    private val schema: ObjectSchema<*>,
    internal val parameter: KParameter,
    private val kProperty: KProperty1<Any, *>,
    internal val schemaType: SchemaType,
) {
    /** The property name. */
    public val name: String = kProperty.name

    /** The name un-camel-cased, e.g. `First name` for `firstName`. */
    public val prettyName: String = camelCaseToPretty(name)

    /** The property, e.g. to compare it with a property reference such as `MyParams::title`. */
    public val property: KProperty1<*, *> get() = kProperty

    /** True if the property accepts null. */
    public val isNullable: Boolean = schemaType.nullable

    /** False if the constructor parameter has a default value. */
    public val isRequired: Boolean = !parameter.isOptional

    /** True if the field is a `List` or a `Set`. [valueClass], [type] etc. then describe its elements. */
    public val isCollection: Boolean = schemaType.shape is Shape.Many

    private val elementType: SchemaType = (schemaType.shape as? Shape.Many)?.element ?: schemaType

    /**
     * The class of the value, e.g. a [DataContainer] subclass or [ModelID]. For a collection, the class of its
     * elements.
     */
    public val valueClass: KClass<*> = elementType.kClass

    /** The kind of value, or null for a collection, a nested object or a container kind that has none. */
    public val type: PropertyType? = if (isCollection) null else elementType.shape.propertyType()

    /** The model class a [ModelID] (or a collection of them) refers to, or null if it is not a reference. */
    public val referencedModel: KClass<*>? = elementType.referencedModel

    /** The constants of the enum an [EnumContainer] (or a collection of them) holds; empty for other fields. */
    public val enumConstants: List<Enum<*>> = (elementType.shape as? Shape.Container)?.enumConstants ?: emptyList()

    /** The [AttachedBlobContainer] class of the field (or of its elements), or null if it is not one. */
    @Suppress("UNCHECKED_CAST")
    public val blobDeclaration: KClass<out AttachedBlobContainer>? =
        (elementType.shape as? Shape.Container)?.takeIf { it.kind == ContainerKind.AttachedBlob }?.kClass
                as KClass<out AttachedBlobContainer>?

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

    /**
     * The value to prefill the field with, e.g. in a form, or null if there is none.
     *
     * It is the Kotlin default expression of the constructor parameter (e.g. `= Score(0)`) when there is one, since
     * that is specific to this field, and otherwise the container's [DataContainer.recommendedDefault].
     *
     * The Kotlin default is evaluated by calling the constructor with placeholders for the other fields, so the
     * class's `init` blocks and the default expression run.
     */
    public val defaultContainer: DataContainer<*>? by lazy { kotlinDefault() ?: recommendedDefault() }

    private fun kotlinDefault(): DataContainer<*>? {
        if (isRequired) {
            return null
        }
        return try {
            schema.kotlinDefaultOf(this) as? DataContainer<*>
        } catch (e: Exception) {
            logger.warn(e) { "Could not evaluate the Kotlin default value of '$name'" }
            null
        }
    }

    private fun recommendedDefault(): DataContainer<*>? {
        val container = elementType.shape as? Shape.Container ?: return null
        return container.dummy().recommendedDefault?.let { container.create(it) }
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
    Short,
    Byte,
    UInt,
    ULong,
    UShort,
    UByte,
    Float,
    Double,
    Boolean,
    Ref,
    AttachedDataRef,
    Enum,
    Instant,
    Date,
    Duration,
    Geo,
}

/** A value found by [ObjectSchema.leaves]. [path] is e.g. `address.street` or `tags[1]`. */
public class Leaf internal constructor(public val path: String, public val field: SchemaField, public val value: Any)

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

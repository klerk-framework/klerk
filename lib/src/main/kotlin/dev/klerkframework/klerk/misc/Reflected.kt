package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.read.Reader
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.Instant

internal val dateFormatter = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    day()
}

internal val dateTimeFormatter = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    day()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
}

/**
 * This is a collection of convenience functions that can be used e.g. when you want to visualize an unknown model.
 * It uses reflection and "un-camel-case" in order to extract properties.
 * If populateRelations() is called within a ModelReader, some functions will be able to return more detailed information.
 */
public class ReflectedModel<T : Any>(public val original: Model<T>) {

    override fun toString(): String = original.toString()

    public val id: ModelID<*>
        get() = original.id

    private val relatedModels: MutableMap<ModelID<*>, Model<Any>> = mutableMapOf()

    private var idsOfModelsWithReferencesToThis: List<ReflectedModel<Any>>? = null

    /**
     * If this is called, some functions will be able to return more detailed information.
     */
    public fun <V, C : KlerkContext> populateRelations(): Reader<C, V>.() -> Unit = {
        ObjectSchema.of(original.props::class).forEachLeaf(original.props) { leaf ->
            val reference = leaf.value as? ModelID<*> ?: return@forEachLeaf
            @Suppress("UNCHECKED_CAST")
            relatedModels[reference] = get(reference as ModelID<Any>)
        }
        idsOfModelsWithReferencesToThis =
            @Suppress("UNCHECKED_CAST")
            getAllRelatedIds(id).map { ReflectedModel(get(it as ModelID<Any>)) }
    }

    /** Reflects over [Model]'s own fields (id, timestamps, state — everything except [Model.props]). */
    public fun getMeta(): List<ReflectedProperty> {
        val result = mutableListOf<ReflectedProperty>()
        original::class.memberProperties.forEach { property ->
            if (property.name != Model<Any>::props.name) {
                result.add(
                    @Suppress("UNCHECKED_CAST")
                    ReflectedProperty(
                        property as KProperty1<out Model<Any>, *>,
                        property.getter.call(original),
                        relatedModels
                    )
                )
            }
        }
        return result
    }

    /** Reflects over the properties of [Model.props]. */
    public fun getProperties(): List<ReflectedProperty> {
        val result = mutableListOf<ReflectedProperty>()
        original.props::class.memberProperties.forEach { property ->
            result.add(
                @Suppress("UNCHECKED_CAST")
                ReflectedProperty(
                    property as KProperty1<out Model<Any>, *>,
                    property.getter.call(original.props),
                    relatedModels
                )
            )
        }
        return result
    }

    /**
     * The models that each `List` or `Set` of [ModelID]s in the props refers to, keyed by the property's pretty name.
     * Requires [populateRelations] to have been called first, otherwise the lists are empty.
     */
    public fun referencesPretty(): Map<String, List<Model<Any>>> =
        ObjectSchema.of(original.props::class).fields
            .filter { (it.type.shape as? Shape.Many)?.element?.shape == Shape.Reference }
            .associate { field ->
                val ids = field.get(original.props) as Collection<*>? ?: emptyList<Any>()
                camelCaseToPretty(field.name) to ids.mapNotNull { relatedModels[it as ModelID<*>] }
            }

    /**
     * Returns a list of ReflectedModels that has a reference to this model.
     * This is null until it has been populated.
     */
    public fun referencesToThis(): List<ReflectedModel<Any>>? = idsOfModelsWithReferencesToThis
}

/** A single reflected property of a [Model] or its props, paired with its value for display purposes. */
public class ReflectedProperty(
    private val original: KProperty1<out Model<Any>, *>,
    public val value: Any?,
    private val relatedModels: MutableMap<ModelID<*>, Model<Any>>
) {

    override fun toString(): String {
        return if (value is ModelID<*>) {
            (relatedModels[value] ?: stringify(value)).toString()
        } else {
            stringify(value)
        }
    }

    /** @return a human-readable rendering of the value if it's a [ModelID] or [Instant], else `null`. */
    public fun description(): String? {
        if (value is ModelID<*>) {
            val referencedModelName = (relatedModels[value] ?: "").toString()
            return "$referencedModelName (id: ${value.value})"
        }
        if (value is Instant) {
            return dateTimeFormatter.format(value.toLocalDateTime(TimeZone.currentSystemDefault()))
        }

        return null
    }

    public fun name(): String {
        return camelCaseToPretty(original.name)
    }

    /** @return the props class of the related model if [value] is a [ModelID] that has been resolved (see [ReflectedModel.populateRelations]), else `null`. */
    public fun getRelatedModelPropsClass(): KClass<*>? {
        val model = relatedModels[value] ?: return null
        return model.props::class
    }

    public fun describe(translation: KlerkTranslation): String? =
        translation.propertyDescription(original.name)

}

/**
 * Reflects over an event's parameters class [raw] (the `P` in e.g. [VoidEventWithParameters]) to describe each
 * constructor parameter as an [EventParameter]. Used by generic tooling (e.g. auto-generated forms/UI) that needs
 * to render or validate an event's parameters without knowing the concrete type at compile time.
 *
 * @throws IllegalConfigurationException if a parameter is not a [DataContainer], a [ModelID], a List/Set thereof, or a
 * class made of these
 */
public data class EventParameters<T : Any>(val raw: KClass<out T>) {

    init {
        ObjectSchema.of(raw)
    }

    val all: List<EventParameter>
        get() {
            val constructor = raw.primaryConstructor!!
            return constructor.parameters.map { EventParameter(it, constructor) }
        }

    val requiredParameters: List<EventParameter>
        get() = all.filter { it.isRequired }

    val optionalParameters: List<EventParameter>
        get() = all.filter { !it.isRequired }

    /**
     * Decodes an instance of [raw] from the JSON Klerk stores parameters as: an object with exactly one key per
     * constructor parameter, a [DataContainer] as its `valueWithoutAuthorization` and a [ModelID] as a number.
     *
     * @throws IllegalArgumentException if a key is unknown or missing (also for a nullable parameter or one with a
     * default value), or a value has the wrong type
     */
    public fun fromJson(json: String): T = try {
        KlerkJson.decode(raw, json)
    } catch (e: JsonMismatchException) {
        throw IllegalArgumentException("Invalid parameters for ${raw.simpleName}: ${e.reason}")
    }

}

/**
 * Reflects a single constructor parameter of an event's parameters class. The parameter's type must be a
 * [DataContainer] subtype (or a List/Set thereof) or a [ModelID]; validated when [EventParameters] is created.
 *
 * @param owner the parameters class's primary constructor [raw] is one of the parameters of, if known. Needed to
 * compute [kotlinDefaultInstance].
 */
public data class EventParameter(public val raw: KParameter, internal val owner: KFunction<*>? = null) {
    val name: String =
        requireNotNull(raw.name) { "No qualified name. Model and parameter classes must be concrete classes" }
    val qualifiedName: String =
        requireNotNull(findValueClass().qualifiedName) { "No qualified name for $name. Model and parameter classes must be concrete classes" }
    val prettyName: String = camelCaseToPretty(name)
    val isRequired: Boolean = !raw.isOptional
    val type: PropertyType? = propertyTypeOf(raw.type)
    val isNullable: Boolean = raw.type.isMarkedNullable
    val modelIDType: String? = findModelIDType()
    val validationRulesDescriptions: Map<String, String>
    val recommendedDefaultValue: Any?

    init {
        val dummy = containerShape()?.dummy()
        validationRulesDescriptions = dummy?.let { describeRules(it) } ?: emptyMap()
        recommendedDefaultValue = dummy?.recommendedDefault
    }

    public val valueClass: KClass<*> = findValueClass()      // TODO: internal?

    /**
     * The actual value this parameter's own Kotlin default expression evaluates to (e.g. `= Score(0)` or
     * `= AvtalStartDatum(today())`), or null if it has none, [owner] is unknown, or evaluating it failed.
     *
     * Works by calling [owner] with every other required parameter filled by a dummy value and this one left out,
     * so Kotlin substitutes its own default for it — the same mechanism the compiler uses. Takes precedence over
     * [DataContainer.recommendedDefault] (a per-container-type default) when both exist, since this one is specific
     * to this parameter of this event.
     */
    public val kotlinDefaultInstance: DataContainer<*>? by lazy {
        if (!raw.isOptional || owner == null) {
            return@lazy null
        }
        try {
            val schema = ObjectSchema.of(owner.returnType.jvmErasure)
            val args = owner.parameters
                .filter { it != raw && !it.isOptional }
                .associateWith { parameter -> schema.fields.single { it.name == parameter.name }.type.dummy() }
            val instance = owner.callBy(args) ?: return@lazy null
            schema.fields.single { it.name == name }.get(instance) as? DataContainer<*>
        } catch (e: Exception) {
            logger.warn(e) { "Could not evaluate the Kotlin default value of '$name'" }
            null
        }
    }

    private fun containerShape(): Shape.Container? {
        val kClass = raw.type.jvmErasure
        return if (kClass.isSubclassOf(DataContainer::class)) Shape.Container.of(kClass) else null
    }

    private fun findValueClass(): KClass<*> {
        if (raw.type.isSubtypeOf(Set::class.starProjectedType)) {
            return raw.type.arguments.single().type!!.classifier!! as KClass<*>
        }
        if (raw.type.isSubtypeOf(List::class.starProjectedType)) {
            return raw.type.arguments.single().type!!.classifier!! as KClass<*>
        }
        return raw.type.classifier!! as KClass<*>
    }

    private fun findModelIDType(): String? {
        val ktype = raw.type.withNullability(false)
        if (!ktype.isSubtypeOf(ModelID::class.starProjectedType)) {
            return null
        }
        return raw.type.withNullability(false).arguments.single().type.toString()
    }

    /**
     * Turns the provided value into a DataContainer.
     * @param value must be of the correct type.
     */
    public fun getInstance(value: Any): DataContainer<*> =
        (containerShape() ?: throw IllegalArgumentException("'$name' is not a DataContainer")).create(value)

    /**
     * Returns a dummy instance of the property type.
     * Use this if you want to access validation rules etc, but the value should not be used.
     */
    public fun getDummyInstance(): DataContainer<*> =
        (containerShape() ?: throw IllegalArgumentException("'$name' is not a DataContainer")).dummy()

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

/**
 * A Field is a property with a value (can be null)
 */
public data class Field(private val kProperty1: KProperty1<*, *>, private val valueObj: Any?) {

    val name: String
        get() = kProperty1.name

    val prettyName: String
        get() = camelCaseToPretty(name)

    val type: PropertyType?
        get() = propertyTypeOf(kProperty1.returnType)

    val value: Any?
        get() {
            if (valueObj is DataContainer<*>) {
                return valueObj.value
            }
            return valueObj
        }

    val prettyValue: String
        get() = stringify(value)

    override fun toString(): String {
        return valueObj.toString()
    }
}

private fun stringify(value: Any?): String {
    return when (value) {
        null -> "(null)"
        is Instant -> dateFormatter.format(value.toLocalDateTime(TimeZone.currentSystemDefault()))
        is ModelID<*> -> value.toString()
        is Collection<*> -> value.size.toString()
        else -> value.toString()
    }
}

/** The "kind" of a property, as used by generic tooling such as forms. */
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

/** @return the [PropertyType] of [type], or null if it has none (e.g. a collection or a `ShortContainer`) */
private fun propertyTypeOf(type: KType): PropertyType? {
    val kClass = type.jvmErasure
    if (kClass == ModelID::class) {
        return PropertyType.Ref
    }
    if (kClass == AttachedBlobID::class || kClass == AttachedStringID::class) {
        return PropertyType.AttachedDataRef
    }
    return when (ContainerKind.entries.firstOrNull { kClass.isSubclassOf(it.base) }) {
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
        ContainerKind.UByte, ContainerKind.Double, null -> null
    }
}

package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.read.Reader
import java.time.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaConstructor
import kotlin.time.Duration
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

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

    private val nonReferenceCollectionsPretty: Map<String, Collection<*>>

    private val relatedModels: MutableMap<ModelID<*>, Model<Any>> = mutableMapOf()

    private var idsOfModelsWithReferencesToThis: List<ReflectedModel<Any>>? = null

    init {
        val nonReferenceCollectionsPrettyInit: MutableMap<String, Collection<*>> = mutableMapOf()
        original.props::class.memberProperties
            .filter { it.returnType.isSubtypeOf(Collection::class.createType(listOf(KTypeProjection.STAR))) }
            .forEach { property ->
                nonReferenceCollectionsPrettyInit[camelCaseToPretty(property.name)] =
                    property.getter.call(original.props) as Collection<*>
            }
        nonReferenceCollectionsPretty = nonReferenceCollectionsPrettyInit
    }

    /**
     * If this is called, some functions will be able to return more detailed information.
     */
    public fun <V, C : KlerkContext> populateRelations(): Reader<C, V>.() -> Unit = {
        original.props::class.memberProperties.forEach { property ->
            val value = property.getter.call(original.props)
            if (value is ModelID<*>) {
                @Suppress("UNCHECKED_CAST")
                relatedModels[value] = get(value as ModelID<Any>)
            }
            if (isCollectionOfModelId(property.returnType)) {
                val collection = property.getter.call(original.props) ?: return@forEach

                @Suppress("UNCHECKED_CAST")
                val relatedIds = (collection as Collection<ModelID<Any>>).toList()
                relatedIds.forEach {
                    relatedModels[it] = get(it)
                }
            }
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

    private fun propertiesPretty(): Map<String, String> {
        val result: MutableMap<String, String> = mutableMapOf()
        original.props::class.memberProperties.forEach { property ->
            val value = property.getter.call(original.props)
            if (value is ModelID<*>) {
                val referencedModelName = (relatedModels[value] ?: "").toString()
                result[property.name] = "$referencedModelName (id: $value)"
            } else {
                val valueAsString = stringify(value)
                result[property.name] = valueAsString
            }
        }
        return result
    }

    /** Requires [populateRelations] to have been called first, otherwise related models are unresolved. */
    public fun referencesPretty(): Map<String, List<Model<Any>>> {
        val result = mutableMapOf<String, List<Model<Any>>>()
        original.props::class.memberProperties.forEach { property ->
            if (isCollectionOfModelId(property.returnType)) {
                val collection = property.getter.call(original.props) ?: return@forEach

                @Suppress("UNCHECKED_CAST")
                val relatedIds = (collection as Collection<ModelID<Any>>).toList()
                relatedIds.map {
                    relatedModels[it]?.toString() ?: "(id: ${it})"
                }
            }
        }
        return result
    }

    /**
     * Returns a list of ReflectedModels that has a reference to this model.
     * This is null until it has been populated.
     */
    public fun referencesToThis(): List<ReflectedModel<Any>>? = idsOfModelsWithReferencesToThis

    private fun isCollectionOfModelId(type: KType): Boolean {
        val AnyType = KTypeProjection(KVariance.OUT, Any::class.createType())
        val modelIDType =
            KTypeProjection(KVariance.INVARIANT, ModelID::class.createType(arguments = listOf(AnyType)))
        return type.isSubtypeOf(Collection::class.createType(arguments = listOf(modelIDType)))
    }
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

    private fun getRelatedModel(): Model<Any>? {
        return relatedModels[value]
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
        KlerkJson.requireStorable(raw)
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
    val type: PropertyType? = basicTypeEnumFromKType(raw.type.withNullability(false))
    val isNullable: Boolean = raw.type.isMarkedNullable
    val modelIDType: String? = findModelIDType()
    val validationRulesDescriptions: Map<String, String>
    val recommendedDefaultValue: Any?

    init {
        val validationRulesDescriptionsTemp = mutableMapOf<String, String>()
        if ((raw.type.classifier as KClass<*>).visibility == KVisibility.PRIVATE) {
            logger.warn { "Property $name is private. This means that some info will be missing" } // or should we just force the modifier?
            validationRulesDescriptionsTemp["required"] = "false"
            validationRulesDescriptionsTemp["validator"] = "none"
            recommendedDefaultValue = null
            validationRulesDescriptions = emptyMap()
        } else {
            when (type) {
                PropertyType.String -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .call("") as StringContainer)
                    validationRulesDescriptionsTemp["min length"] = s.minLength.toString()
                    validationRulesDescriptionsTemp["max length"] = s.maxLength.toString()
                    s.regexPattern?.let { validationRulesDescriptionsTemp["pattern"] = it }
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Int -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .apply { javaConstructor?.isAccessible = true }
                        .call(0) as IntContainer)
                    s.min.let { validationRulesDescriptionsTemp["min"] = it.toString() }
                    s.max.let { validationRulesDescriptionsTemp["max"] = it.toString() }
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Long -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .call(0L) as LongContainer)
                    s.min.let { validationRulesDescriptionsTemp["min"] = it.toString() }
                    s.max.let { validationRulesDescriptionsTemp["max"] = it.toString() }
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Float -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .call(0f) as FloatContainer)
                    s.min.let { validationRulesDescriptionsTemp["min"] = it.toString() }
                    s.max.let { validationRulesDescriptionsTemp["max"] = it.toString() }
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Boolean -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .call(false) as BooleanContainer)
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Instant -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .call(Instant.fromEpochSeconds(0)) as InstantContainer)
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Date -> {
                    val s = ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                        .call(LocalDate.ofEpochDay(0)) as DateContainer)
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Duration -> {
                    val s =
                        ((raw.type.classifier as KClass<*>).constructors.single { it.parameters.size == 1 }
                            .call(Duration.ZERO) as DurationContainer)
                    validationRulesDescriptionsTemp["validator"] =
                        s.validators.joinToString(", ") { extractNameFromFunctionString(it.toString()) }
                    recommendedDefaultValue = s.recommendedDefault
                }

                PropertyType.Ref -> {
                    recommendedDefaultValue = null
                }   // TODO

                /*            else -> {
                            logger.warn { "validationRulesDescription not implemented for type $type" }

                        }


             */

                PropertyType.AttachedDataRef -> {
                    recommendedDefaultValue = null
                    logger.warn { "validationRulesDescription not implemented for type $type" }
                } // TODO

                PropertyType.Enum -> {
                    recommendedDefaultValue = null
                    logger.warn { "validationRulesDescription not implemented for type $type" }
                } // TODO

                PropertyType.Geo -> {
                    recommendedDefaultValue = null
                    logger.warn { "validationRulesDescription not implemented for type $type" }
                } // TODO

                null -> {
                    recommendedDefaultValue = null
                    logger.warn { "PropertyType is null for $name" }
                }
            }
            validationRulesDescriptions = validationRulesDescriptionsTemp
        }
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
        if (raw.isOptional && owner != null) {
            try {
                val args = owner.parameters
                    .filter { it != raw && !it.isOptional }
                    .associateWith { EventParameter(it).getDummyInstance() }
                val instance = owner.callBy(args) ?: return@lazy null
                instance::class.memberProperties
                    .single { it.name == name }
                    .getter.call(instance) as? DataContainer<*>
            } catch (e: Exception) {
                logger.warn(e) { "Could not evaluate the Kotlin default value of '$name'" }
                null
            }
        } else {
            null
        }
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
    public fun getInstance(value: Any): DataContainer<*> = getAnInstance(value)

    /**
     * Returns a dummy instance of the property type.
     * Use this if you want to access validation rules etc, but the value should not be used.
     */
    public fun getDummyInstance(): DataContainer<*> = getAnInstance(null)

    /**
     * Returns an instance of the given property type.
     * @param value the value to be used for the instance. If null, a dummy instance is returned.
     */
    private fun getAnInstance(value: Any?): DataContainer<*> {
        val clazz = raw.type.withNullability(false).classifier as KClass<*>
        try {
            if (clazz.isSubclassOf(StringContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }.call(value ?: "") as DataContainer<*>
            }
            if (clazz.isSubclassOf(IntContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }.call(value ?: 0) as DataContainer<*>
            }
            if (clazz.isSubclassOf(LongContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }.call(value ?: 0L) as DataContainer<*>
            }
            if (clazz.isSubclassOf(FloatContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }.call(value ?: 0f) as DataContainer<*>
            }
            if (clazz.isSubclassOf(BooleanContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }.call(value ?: false) as DataContainer<*>
            }
            if (clazz.isSubclassOf(EnumContainer::class)) {
                val ctor = clazz.constructors.single { it.parameters.size == 1 }
                val enumClass = ctor.parameters.single().type.classifier as KClass<*>
                val dummyValue = value ?: enumClass.java.enumConstants.first()
                return ctor.call(dummyValue) as DataContainer<*>
            }
            if (clazz.isSubclassOf(ModelID::class)) {
                val idValue = (value as? Int) ?: 0
                return clazz.constructors.single { it.parameters.size == 1 }
                    .call(ModelID<Any>(idValue)) as DataContainer<*>
            }
            if (clazz.isSubclassOf(InstantContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }
                    .call(value ?: Instant.fromEpochMilliseconds(0)) as DataContainer<*>
            }
            if (clazz.isSubclassOf(DateContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }
                    .call(value ?: LocalDate.ofEpochDay(0)) as DataContainer<*>
            }
            if (clazz.isSubclassOf(DurationContainer::class)) {
                return clazz.constructors.single { it.parameters.size == 1 }
                    .call(value ?: Duration.ZERO) as DataContainer<*>
            }
            TODO("cannot handle $clazz")
        } catch (e: InstantiationException) {
            log.error(
                "Double check that your parameter class only consists of Datatypes and ModelIds (or set, list thereof). Note that it cannot be abstract!",
                e
            )
            throw e
        }
    }


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
        get() = basicTypeEnumFromKType(kProperty1.returnType.withNullability(false))

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

/** The reflected "kind" of a [DataContainer] property, as determined by [basicTypeEnumFromKType]. */
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

/**
 * @return a PropertyType if this is a "basic" type, otherwise null
 */
private fun basicTypeEnumFromKType(ktypeMaybeNullable: KType): PropertyType? {
    val ktype = ktypeMaybeNullable.withNullability(false)
    if (ktype.isSubtypeOf(ModelID::class.starProjectedType)) {
        return PropertyType.Ref
    }

    if (ktype.isSubtypeOf(AttachedBlobID::class.starProjectedType) ||
        ktype.isSubtypeOf(AttachedStringID::class.starProjectedType) ||
        // An AttachedDataContainer is a DataContainer, so it has to be recognised before the generic container handling below.
        ktype.isSubtypeOf(AttachedDataContainer::class.starProjectedType)
    ) {
        return PropertyType.AttachedDataRef
    }

    if (ktype.isSubtypeOf(StringContainer::class.starProjectedType)) {
        return PropertyType.String
    }
    if (ktype.isSubtypeOf(BooleanContainer::class.starProjectedType)) {
        return PropertyType.Boolean
    }
    if (ktype.isSubtypeOf(IntContainer::class.starProjectedType)) {
        return PropertyType.Int
    }
    if (ktype.isSubtypeOf(LongContainer::class.starProjectedType)) {
        return PropertyType.Long
    }
    if (ktype.isSubtypeOf(FloatContainer::class.starProjectedType)) {
        return PropertyType.Float
    }
    if (ktype.isSubtypeOf(InstantContainer::class.starProjectedType)) {
        return PropertyType.Instant
    }
    if (ktype.isSubtypeOf(DateContainer::class.starProjectedType)) {
        return PropertyType.Date
    }
    if (ktype.isSubtypeOf(DurationContainer::class.starProjectedType)) {
        return PropertyType.Duration
    }

    if (ktype.isSubtypeOf(EnumContainer::class.starProjectedType)) {
        return PropertyType.Enum
    }
    if (ktype.isSubtypeOf(GeoPositionContainer::class.starProjectedType)) {
        return PropertyType.Geo
    }

    if (ktype.isSubtypeOf(DataContainer::class.starProjectedType)) {
        throw NotImplementedError(ktype.toString())
    }

    return null
}

internal fun getEnumValue(enumClassName: String, enumValue: String) =
    Class.forName(enumClassName).enumConstants.filterIsInstance(Enum::class.java).first { it.name == enumValue }


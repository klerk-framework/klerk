package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.read.Reader
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

    private fun getMetaAndProperties(): List<ReflectedProperty> {
        val result = getMeta().toMutableList()
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

}

public data class EventParameters<T : Any>(val raw: KClass<out T>) {

    init {
        all.forEach { it.validate() }
    }

    val all: List<EventParameter>
        get() = raw.primaryConstructor!!.parameters.map { EventParameter(it) }

    val requiredParameters: List<EventParameter>
        get() = all.filter { it.isRequired }

    val optionalParameters: List<EventParameter>
        get() = all.filter { !it.isRequired }

}

public data class EventParameter(public val raw: KParameter) {
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

                PropertyType.KeyValueRef -> {
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

    public fun validate() {
        val ktype = raw.type.withNullability(false)
        validate(ktype)
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

    private fun validate(ktype: KType) {
        if (basicTypeEnumFromKType(ktype) != null) {
            return
        }

        if (ktype.isSubtypeOf(Collection::class.starProjectedType)) {
            if (ktype.toString().contains("MutableSet")) {
                throwPropertyException(ktype, "MutableSet is not allowed.")
            }
            if (ktype.toString().contains("MutableList")) {
                throwPropertyException(ktype, "MutableList is not allowed.")
            }
            if (!(ktype.toString().contains("List") || ktype.toString().contains("Set"))) {
                throwPropertyException(ktype, "Only List and Set collections are allowed.")
            }
            validate(ktype.arguments.single().type!!)
            return
        }
        val constructors = (ktype.classifier!! as KClass<*>).constructors
        if (constructors.size != 1) {
            throwPropertyException(ktype, "Found ${constructors.size} constructors, expected only one.")
        }
        val constructor = constructors.single()
        if (constructor.parameters.isEmpty()) {
            throwPropertyException(ktype, "Found constructor with no parameters.")
        }
        constructor.parameters.forEach { kParameter: KParameter ->
            validate(kParameter.type)
        }
    }

    private fun throwPropertyException(type: KType, message: String): Nothing {
        val first = "Property '$name' has invalid type '$type'."
        val propertyDocumentation = "Properties must be subtypes of DataContainer or List/Set thereof."
        throw IllegalConfigurationException(
            KlerkErrorCode.PropertyMustBeDataContainer,
            "$first $message $propertyDocumentation"
        )
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

public enum class PropertyType {
    String,
    Int,
    Long,
    Float,
    Boolean,
    Ref,
    KeyValueRef,

    Enum,
    Instant,
    Duration,
    Geo,
}

/**
 * @return a PropertyType if this is a "basic" type, otherwise null
 */
private fun basicTypeEnumFromKType(ktype: KType): PropertyType? {
    if (ktype.isSubtypeOf(ModelID::class.starProjectedType)) {
        return PropertyType.Ref
    }

    if (ktype.isSubtypeOf(KeyValueID::class.starProjectedType)) {
        return PropertyType.KeyValueRef
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

/*fun extractValueClasses(from: Any): Set<KClass<String>> {
    val result = mutableSetOf<KClass<String>>()
    from::class.primaryConstructor!!.parameters.forEach {

        if (it.type.isSubtypeOf(DataContainer::class.starProjectedType)) {
            println(it)
            //result.add(it)
        }
    }
    return result
}

 */

internal fun getEnumValue(enumClassName: String, enumValue: String) =
    Class.forName(enumClassName).enumConstants.filterIsInstance(Enum::class.java).first { it.name == enumValue }

internal fun extractValueClasses(kClass: KClass<*>): Set<KClass<*>> {
    if (kClass.starProjectedType.isSubtypeOf(DataContainer::class.starProjectedType)) {
        return setOf(kClass)
    }
    val result = mutableSetOf<KClass<*>>()
    kClass.declaredMemberProperties.map { it.returnType }.forEach {
        if (it.isSubtypeOf(ModelID::class.starProjectedType)) {
            return@forEach
        }
        val clazz = it.classifier as KClass<*>
        if (it.isSubtypeOf(DataContainer::class.starProjectedType)) {
            result.add(clazz)
        } else {
            if (clazz.isData && clazz != kClass) {
                result.addAll(extractValueClasses(it.classifier as KClass<*>))
            }
            if (clazz.isSubclassOf(Collection::class)) {
                result.addAll(extractValueClasses(it.arguments.first().type!!.classifier as KClass<*>))
            }
        }
    }
    return result
}

internal fun checkDataContainerProperties(kClass: KClass<*>) {
    EventParameters(kClass)
    // it didn't throw, so it's fine
}

/**
 * Returns an instance of the given property type.
 * @param value the value to be used for the instance. If null, a dummy instance is returned.
 */
public fun getDataContainerInstance(eventParameter: EventParameter, value: Any?): DataContainer<*> {
    val clazz = eventParameter.raw.type.withNullability(false).classifier as KClass<*>
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
        if (clazz.isSubclassOf(Enum::class)) {
            return clazz.constructors.single { it.parameters.size == 1 }.call(value ?: false) as DataContainer<*>
        }
        if (clazz.isSubclassOf(ModelID::class)) {
            val idValue = (value as? Int) ?: 0
            return clazz.constructors.single { it.parameters.size == 1 }.call(ModelID<Any>(idValue)) as DataContainer<*>
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

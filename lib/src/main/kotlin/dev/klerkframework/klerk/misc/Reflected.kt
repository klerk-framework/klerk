package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.logger
import dev.klerkframework.klerk.read.Reader
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.*
import kotlin.reflect.full.*

internal val dateFormatter = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
}

internal val dateTimeFormatter = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
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
    public fun <V, C:KlerkContext> populateRelations(): Reader<C, V>.() -> Unit = {
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
            return "$referencedModelName (id: ${value.toInt()})"
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

public data class EventParameters<T:Any>(val raw: KClass<out T>) {

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

public data class EventParameter(val raw: KParameter) {

    init {
        requireNotNull(raw.name)
    }

    val valueClass: KClass<*>
        get() {
            if (raw.type.isSubtypeOf(Set::class.starProjectedType)) {
                return raw.type.arguments.single().type!!.classifier!! as KClass<*>
            }
            if (raw.type.isSubtypeOf(List::class.starProjectedType)) {
                return raw.type.arguments.single().type!!.classifier!! as KClass<*>
            }
            return raw.type.classifier!! as KClass<*>
        }

    val name: String
        get() = raw.name!!

    val prettyName: String
        get() = camelCaseToPretty(name)

    val isRequired: Boolean
        get() = !raw.isOptional

    val isNullable: Boolean
        get() = raw.type.isMarkedNullable

    val type: PropertyType?
        get() {
            val ktype = raw.type.withNullability(false)
            return basicTypeEnumFromKType(ktype)
        }

    val modelIDType: String?
        get() {
            val ktype = raw.type.withNullability(false)
            if (!ktype.isSubtypeOf(ModelID::class.starProjectedType)) {
                return null
            }
            return raw.type.withNullability(false).arguments.single().type.toString()
        }

    public fun validate() {
        val ktype = raw.type.withNullability(false)
        validate(ktype)
    }

    private fun validate(ktype: KType) {
        if (basicTypeEnumFromKType(ktype) != null) {
            return
        }

        val errorMessagePartOne = "Invalid type '$ktype' for '$name'"
        if (ktype.isSubtypeOf(Collection::class.starProjectedType)) {
            require(!ktype.toString().contains("MutableSet")) { "MutableSet is not allowed" }
            require(!ktype.toString().contains("MutableList")) { "MutableList is not allowed" }
            require(
                !(!ktype.toString().contains("List") && !ktype.toString().contains("Set"))
            ) { "$errorMessagePartOne. Only List and Set collections are allowed." }
            validate(ktype.arguments.single().type!!)
            return
        }
        val constructors = (ktype.classifier!! as KClass<*>).constructors
        require(constructors.size == 1) { "It seems you are trying to use a non-DataContainer (found ${constructors.size} constructors)" }
        val constructor = constructors.single()
        require(constructor.parameters.isNotEmpty()) { "$errorMessagePartOne. The leaves must be DataContainer." }
        constructor.parameters.forEach { kParameter: KParameter ->
            validate(kParameter.type)
        }
    }



    public fun validationRulesDescription(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        when (type) {
            PropertyType.String -> {
                val s = ((raw.type.classifier as KClass<*>).constructors.single().call("") as StringContainer)
                result["min length"] = s.minLength.toString()
                result["max length"] = s.maxLength.toString()
                s.regexPattern?.let { result["pattern"] = it.toString() }
                result["validator"] = s.validators.map { extractNameFromFunctionString(it.toString()) }.joinToString(", ")
            }
            PropertyType.Int -> {
                val s = ((raw.type.classifier as KClass<*>).constructors.single().call(0) as IntContainer)
                s.min?.let { result["min"] = it.toString() }
                s.max?.let { result["max"] = it.toString() }
                result["validator"] = s.validators.map { extractNameFromFunctionString(it.toString()) }.joinToString(", ")
            }
            else -> {
                logger.warn { "validationRulesDescription not implemented for type $type" }
            }
        }
        return result
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
    Enum,
}

/**
 * @return a PropertyType if this is a "basic" type, otherwise null
 */
private fun basicTypeEnumFromKType(ktype: KType): PropertyType? {
    if (ktype.isSubtypeOf(ModelID::class.starProjectedType)) {
        return PropertyType.Ref
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
        return PropertyType.Long
    }
    if (ktype.isSubtypeOf(DurationContainer::class.starProjectedType)) {
        return PropertyType.Long
    }

/*    if (ktype.isSubtypeOf(EnumContainer::class.starProjectedType)) {
        return PropertyType.Enum
    }
 */

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

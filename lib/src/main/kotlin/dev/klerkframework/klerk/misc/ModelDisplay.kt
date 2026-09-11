package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.read.Reader
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
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
 * Convenience functions for visualizing a model whose type is not known at compile time. The properties are those of
 * the props class's [ObjectSchema], with names un-camel-cased.
 * If populateRelations() is called within a ModelReader, some functions will be able to return more detailed information.
 */
public class ModelDisplay<T : Any>(public val original: Model<T>) {

    override fun toString(): String = original.toString()

    public val id: ModelID<*>
        get() = original.id

    private val schema = ObjectSchema.of(original.props::class)

    private val relatedModels: MutableMap<ModelID<*>, Model<Any>> = mutableMapOf()

    private var idsOfModelsWithReferencesToThis: List<ModelDisplay<Any>>? = null

    /**
     * If this is called, some functions will be able to return more detailed information.
     */
    public fun <V, C : KlerkContext> populateRelations(): Reader<C, V>.() -> Unit = {
        schema.forEachLeaf(original.props) { leaf ->
            val reference = leaf.value as? ModelID<*> ?: return@forEachLeaf
            @Suppress("UNCHECKED_CAST")
            relatedModels[reference] = get(reference as ModelID<Any>)
        }
        idsOfModelsWithReferencesToThis =
            @Suppress("UNCHECKED_CAST")
            getAllRelatedIds(id).map { ModelDisplay(get(it as ModelID<Any>)) }
    }

    /** [Model]'s own fields (id, state, timestamps — everything except [Model.props]). */
    public fun getMeta(): List<DisplayProperty> = listOf(
        Model<T>::id,
        Model<T>::state,
        Model<T>::createdAt,
        Model<T>::lastModifiedAt,
        Model<T>::lastPropsUpdateAt,
        Model<T>::lastStateTransitionAt,
        Model<T>::timeTrigger,
    ).map { DisplayProperty(it, it.get(original), relatedModels) }

    /** The fields of [Model.props], in declaration order. */
    public fun getProperties(): List<DisplayProperty> =
        schema.fields.map { DisplayProperty(it.property, it.get(original.props), relatedModels) }

    /**
     * The models that each `List` or `Set` of [ModelID]s in the props refers to, keyed by the property's pretty name.
     * Requires [populateRelations] to have been called first, otherwise the lists are empty.
     */
    public fun referencesPretty(): Map<String, List<Model<Any>>> =
        schema.fields
            .filter { it.isCollection && it.valueClass == ModelID::class }
            .associate { field ->
                val ids = field.get(original.props) as Collection<*>? ?: emptyList<Any>()
                field.prettyName to ids.mapNotNull { relatedModels[it as ModelID<*>] }
            }

    /**
     * Returns the models that have a reference to this model.
     * This is null until it has been populated.
     */
    public fun referencesToThis(): List<ModelDisplay<Any>>? = idsOfModelsWithReferencesToThis
}

/** A single property of a [Model] or its props, paired with its value for display purposes. */
public class DisplayProperty internal constructor(
    public val property: KProperty1<*, *>,
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
        return camelCaseToPretty(property.name)
    }

    /** @return the props class of the related model if [value] is a [ModelID] that has been resolved (see [ModelDisplay.populateRelations]), else `null`. */
    public fun getRelatedModelPropsClass(): KClass<*>? {
        val model = relatedModels[value] ?: return null
        return model.props::class
    }

    public fun describe(translation: KlerkTranslation): String? =
        translation.propertyDescription(property)

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

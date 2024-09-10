package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse
import dev.klerkframework.klerk.datatypes.DataContainer
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

public interface Reader<C:KlerkContext, D> {

    public val data: D

    /**
     * Get a model.
     *
     * @throws AuthorizationException if the model is not found or the actor is not allowed to read it.
     */
    public fun <T : Any> get(id: ModelID<T>): Model<T>

    public fun <T : Any> getOrNull(id: ModelID<T>): Model<T>?

    /**
     * Gets the first model from the Collection and passes the provided filter.
     * @throws NoSuchElementException if no model was found
     */
    public fun <T : Any> getFirstWhere(
        collection: ModelCollection<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T>

    /**
     * Finds the first model in the Collection and passes the provided filter.
     */
    public fun <T : Any> firstOrNull(
        collection: ModelCollection<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T>?

    public fun <T : Any> getIfAuthorizedOrNull(id: ModelID<T>): Model<T>?

    /**
     * List all models that are specified in a Collection. Unauthorized models are removed from the result.
     *
     * @throws
     */
    public fun <T : Any> listIfAuthorized(collection: ModelCollection<T, C>): List<Model<T>>

    /**
     * Get all models in the Collection that passes the provided filter.
     *
     * @throws AuthorizationException if there is any model in the collection that the user is not allowed to read.
     */
    public fun <T : Any> list(
        modelCollection: ModelCollection<T, C>,
        filter: ((Model<T>) -> Boolean)? = null,
    ): List<Model<T>>

    public fun <T : Any> query(collection: ModelCollection<T, C>, options: QueryOptions? = null, filter: ((Model<T>) -> Boolean)? = null): QueryResponse<T>

    /**
     * Finds all models that have a relation to the specified model.
     */
    public fun getAllRelatedIds(id: ModelID<*>): Set<ModelID<*>>

    /**
     * Finds all models of a specified type that has a relation to the specified model.
     */
    public fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>>

    public fun <T : Any, U : Any> getRelated(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>,

    ): Set<Model<T>>

    public fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,

    ): Set<Model<T>>

    /**
     * Returns a set of external void events for the statemachine that are possible given the provided context
     * @see isVoidEventPossible
     */
    public fun <T : Any> getPossibleVoidEvents(clazz: KClass<T>): Set<EventReference>


    /**
     * Returns a set of external events for the model that are possible given the current state and provided context
     * @see isInstanceEventPossible
     */
    public fun <T : Any> getPossibleEvents(id: ModelID<T>): Set<EventReference>

}

internal sealed class ReadResult<T : Any> {
    data class Fail<T : Any>(val problem: Problem) : ReadResult<T>()
    data class Ok<T : Any>(val model: Model<T>) : ReadResult<T>()

    fun getOrNull(): Model<T>? {
        return when (this) {
            is Fail -> null
            is Ok -> this.model
        }
    }

    fun getOrThrow(): Model<T> {
        when (this) {
            is Fail -> throw this.problem.asException()
            is Ok -> return this.model
        }
    }
}

internal sealed class ReadListResult<T : Any> {
    data class Fail<T : Any>(val problem: Problem) : ReadListResult<T>()
    data class Ok<T : Any>(val models: List<Model<T>>) : ReadListResult<T>()

    fun getOrEmpty(): List<Model<T>> {
        return when (this) {
            is Fail -> emptyList()
            is Ok -> this.models
        }
    }

    fun getOrThrow(): List<Model<T>> {
        when (this) {
            is Fail -> throw this.problem.asException()
            is Ok -> return this.models
        }
    }
}


internal fun <T : Any, C:KlerkContext, V> initPropertyAuthorization(
    model: Model<T>,
    ctx: C,
    reader: ReaderWithoutAuth<C, V>,
    config: Config<C, V>,
) {
    model.props::class.memberProperties.forEach { kprop ->
        val prop = kprop.getter.call(model.props)
        initAuth(prop, model, ctx, reader, config)
    }
}

private fun <C:KlerkContext, V, T:Any> initAuth(
    prop: Any?,
    model: Model<T>,
    ctx: C,
    reader: ReaderWithoutAuth<C, V>,
    config: Config<C, V>
) {
    when (prop) {
        null -> return
        is DataContainer<*> -> prop.initAuthorization(isReadPropertyAuthorized(ArgsForPropertyAuth(prop, model, ctx, reader), config))
        is Set<*> -> prop.forEach { initAuth(it, model, ctx, reader, config) }
        is List<*> -> prop.forEach { initAuth(it, model, ctx, reader, config) }
        is ModelID<*> -> {}
        else -> {
            prop::class.memberProperties.forEach { initAuth(it.getter.call(prop), model, ctx, reader, config) }
        }
    }
}

private fun <C:KlerkContext, V> isReadPropertyAuthorized(args: ArgsForPropertyAuth<C, V>, config: Config<C, V>): Boolean {
    if (config.authorization.readPropertyPositiveRules.none { it.invoke(args) == dev.klerkframework.klerk.PositiveAuthorization.Allow }) {
        return false
    }
    if (config.authorization.readPropertyNegativeRules.any { it.invoke(args) == dev.klerkframework.klerk.NegativeAuthorization.Deny }) {
        return false
    }
    return true
}

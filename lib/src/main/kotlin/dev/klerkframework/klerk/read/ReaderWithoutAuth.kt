package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.datetime.Instant
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Used internally, e.g. when executing the functions provided in a statemachine.
 * Note that no logging to KlerkLog is triggered here.
 */
internal class ReaderWithoutAuth<C: KlerkContext, V>(val klerk: Klerk<C, V>) : Reader<C, V> {

    override val views = klerk.config.collections

    override fun getAllRelatedIds(id: ModelID<*>): Set<ModelID<*>> = ModelCache.getAllRelated(id)

    override fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = ModelCache.getRelatedInCollection(property, id)

    override fun <T : Any, U : Any> getRelated(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = ModelCache.getRelated(property, id)

    override fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> {
        return ModelCache.getRelated(clazz, id)
    }

    override fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> {
        var cursor = options?.cursor
        if (cursor?.after == Instant.DISTANT_PAST) {
            cursor = null   // a tiny optimization
        }

        // We will request one or two extra items so we know if there are previous/next. To do this, we will treat the instant as including.
        cursor?.including = true
        val desiredNumberOfItems = options?.maxItems ?: QueryListCursor.DEFAULT_ITEMS_PER_PAGE
        val itemsToTake = desiredNumberOfItems + if (cursor == null) 1 else 2

        var items = collection.withReader(this, cursor)
            .filter { filter?.invoke(it) ?: true }
            .take(itemsToTake)
            .toList()

        if (items.isEmpty()) {
            return QueryResponse(
                items,
                hasPreviousPage = false,
                hasNextPage = false,
                cursorFirst = null,
                cursorPrevious = null,
                cursorNext = null,
                cursorLast = null,
                options = options
            )
        }

        if (cursor?.before != null) {       // the items are in reversed order
            items = items.asReversed()
        }

        val firstIsInvalid = !(cursor?.after?.let { it < items.first().createdAt } ?: true)
        val lastIsInvalid = !(cursor?.before?.let { it > items.last().createdAt } ?: true)
        var hasNextPage = false
        var hasPreviousPage = false

        if (firstIsInvalid) {
            items = items.drop(1)
            hasPreviousPage = true
        }
        if (lastIsInvalid) {
            items = items.dropLast(1)
            hasNextPage = true
        }

        val extraItemsToRemove = items.size - desiredNumberOfItems
        val descending = cursor?.before != null
        if (descending && extraItemsToRemove > 0) {
            items = items.drop(extraItemsToRemove)
            hasPreviousPage = true
        }
        if (!descending && extraItemsToRemove > 0) {
            items = items.dropLast(extraItemsToRemove)
            hasNextPage = true
        }

        val previousCursor = if (hasPreviousPage) QueryListCursor(before = items.first().createdAt) else null
        val nextCursor = if (hasNextPage) QueryListCursor(after = items.last().createdAt) else null

        return QueryResponse(
            items,
            hasPreviousPage = hasPreviousPage,
            hasNextPage = hasNextPage,
            cursorFirst = QueryListCursor.first,
            cursorPrevious = previousCursor,
            cursorNext = nextCursor,
            cursorLast = QueryListCursor.last,
            options = options
        )
    }

    override fun <T : Any> list(
        modelView: ModelView<T, C>,
        filter: ((Model<T>) -> Boolean)?
    ): List<Model<T>> =
        modelView.filter(filter = filter).withReader(this, null).toList()

    override fun <T : Any> listIfAuthorized(collection: ModelView<T, C>): List<Model<T>> {
        throw RuntimeException("Reader.listIfAuthorized was called but the reader doesn't enforce authorization")
    }

    override fun <T : Any> getIfAuthorizedOrNull(id: ModelID<T>): Model<T>? {
        throw RuntimeException("Reader.findIfAuthorized was called but the reader doesn't enforce authorization")
    }

    override fun <T : Any> firstOrNull(
        collection: ModelView<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T>? {
        if (collection.isEmpty(this)) {
            return null
        }
        val list = list(collection, filter = filter)
        if (list.isEmpty()) {
            return null
        }
        return list[0]
    }

    override fun <T : Any> getFirstWhere(
        collection: ModelView<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T> =
        list(collection, filter).first()

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        return ModelCache.getOrNull(id)
    }

    override fun <T : Any> get(id: ModelID<T>): Model<T> {
        return getOrNull(id) ?: throw NoSuchElementException("Could not find model with id=$id")
    }

    override fun <T : Any> getPossibleVoidEvents(clazz: KClass<T>): Set<EventReference> =
        throw RuntimeException("Reader.getPossibleVoidEvents was called but the reader doesn't support this")

    override fun <T : Any> getPossibleEvents(id: ModelID<T>): Set<EventReference> =
        throw RuntimeException("Reader.getPossibleEvents was called but the reader doesn't support this")

}

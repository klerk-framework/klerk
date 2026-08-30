package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse

/**
 * How a view is actually read. Not part of [Reader]'s public surface: reading a view is done on the view
 * (`view.count()`, `view.asList()`, `view.query(...)`, see `collection/ViewOperations.kt`), and those extensions
 * dispatch through here. An extension cannot be overridden, and each of these means something different for an
 * authorizing reader than for the internal one, so the polymorphism has to live somewhere.
 */
internal interface ViewReader<C : KlerkContext, V> {

    fun <T : Any> getFirstWhere(collection: ModelView<T, C>, filter: (Model<T>) -> Boolean): Model<T>

    fun <T : Any> firstOrNull(collection: ModelView<T, C>, filter: (Model<T>) -> Boolean): Model<T>?

    fun <T : Any> listIfAuthorized(collection: ModelView<T, C>): List<Model<T>>

    fun <T : Any> list(modelView: ModelView<T, C>, filter: ((Model<T>) -> Boolean)? = null): List<Model<T>>

    fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions? = null,
        filter: ((Model<T>) -> Boolean)? = null,
    ): QueryResponse<T>

    fun <T : Any> queryIfAuthorized(
        collection: ModelView<T, C>,
        options: QueryOptions? = null,
        filter: ((Model<T>) -> Boolean)? = null,
    ): QueryResponse<T>
}

/**
 * @throws IllegalStateException if [this] is a [Reader] Klerk did not create, which cannot read views.
 */
@Suppress("UNCHECKED_CAST")
internal fun <C : KlerkContext, V> Reader<C, V>.viewReader(): ViewReader<C, V> =
    this as? ViewReader<C, V>
        ?: error("${this::class.simpleName} cannot read views; use the Reader from a klerk.read block")

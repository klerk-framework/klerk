package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse

/**
 * How a view is actually read. Not part of [Reader]'s public surface: reading a view is done on the view
 * (`view.count()`, `view.asSequence().toList()`, `view.query(...)`, see `collection/ViewOperations.kt`), and those
 * extensions dispatch through here. An extension cannot be overridden, and each of these means something different for
 * an authorizing reader than for the internal one, so the polymorphism has to live somewhere.
 */
internal interface ViewReader<C : KlerkContext, V> {

    /** Lazily, skipping the models the actor may not read rather than throwing. */
    fun <T : Any> sequence(collection: ModelView<T, C>): Sequence<Model<T>>

    /** Skips the models the actor may not read, before the page is cut. */
    fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions? = null,
        filter: ((Model<T>) -> Boolean)? = null,
    ): QueryResponse<T>

    /** Throws [dev.klerkframework.klerk.AuthorizationException] if the actor may not read a matching model. */
    fun <T : Any> queryOrThrow(
        collection: ModelView<T, C>,
        options: QueryOptions? = null,
        filter: ((Model<T>) -> Boolean)? = null,
    ): QueryResponse<T>
}

/**
 * @throws IllegalStateException if [this] is a [Reader] Klerk did not create, which cannot read views.
 */
@Suppress("UNCHECKED_CAST")
internal fun <C : KlerkContext, V> ModelReader<C, V>.viewReader(): ViewReader<C, V> = this as? ViewReader<C, V>
    ?: error("${this::class.simpleName} cannot read views; use the Reader from a klerk.read block")

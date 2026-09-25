package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.read.viewReader

/*
 * Reading a view. Every operation needs a Reader -- it is what proves the read lock is held -- but the reader is
 * taken as a context parameter, so inside a `klerk.read { }` block (where it is the receiver) or a
 * `with(args.reader) { }` block it does not have to be written out.
 *
 * They are ordered by cost.
 *
 * The `OrThrow` suffix is deliberately the opposite of the standard library's: the plain `asSequence`/`query` skip
 * the models the actor may not read, and the `OrThrow` variants throw. See docs/reading.md.
 */

/**
 * How many models in the view the actor may read.
 *
 * For the system, an indexed view answers from ids without reading a single model. For any other actor every model in
 * the view is read to check the `readModels` rules.
 */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.count(): Int = reader.viewReader().count(this)

/** True if the view holds nothing the actor may read. Stops at the first readable model. */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.isEmpty(): Boolean = reader.viewReader().isEmpty(this)

/** True if the view holds anything the actor may read. */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.isNotEmpty(): Boolean = !reader.viewReader().isEmpty(this)

/** `id in view` -- true if the view holds [id] and the actor may read it. */
context(reader: ModelReader<C, V>)
public operator fun <T : Any, C : KlerkContext, V> ModelView<T, C>.contains(id: ModelID<T>): Boolean =
    reader.viewReader().contains(this, id)

/**
 * The ids of the models in the view that the actor may read, in the view's own order, lazily. For the system no model
 * is read; for any other actor each model is read to check the `readModels` rules.
 *
 * Do not use the sequence after the read block has ended — the view may have changed underneath it.
 */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.ids(): Sequence<ModelID<T>> = reader.viewReader().ids(this)

/**
 * The models in the view, in its own order, lazily: only the ones actually consumed are read. Take what you need —
 * `asSequence().take(10)`, `asSequence().any { … }` — and `asSequence().toList()` when you genuinely want all of
 * them, which reads and holds every model in the view.
 *
 * Models the actor may not read are silently skipped. Use [asSequenceOrThrow] when you expect every match to be
 * readable and want a loud failure otherwise.
 *
 * Do not use the sequence after the read block has ended — the view may have changed underneath it.
 */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asSequence(): Sequence<Model<T>> =
    reader.viewReader().sequence(this)

/**
 * Like [asSequence], but throws instead of skipping a model the actor may not read.
 *
 * Do not use the sequence after the read block has ended — the view may have changed underneath it.
 *
 * @throws dev.klerkframework.klerk.AuthorizationException if the actor may not read a matching model
 */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asSequenceOrThrow(): Sequence<Model<T>> = withReader(reader)

/**
 * One page of the view. See [QueryOptions] for the page size and starting point, and [QueryResponse] for the page
 * and the cursors around it. [filter] is applied before the page is cut, so a page is full whenever enough models
 * match.
 *
 * Models the actor may not read are skipped before the page is cut, so pages stay full and cursors stay correct.
 * Use [queryOrThrow] when you expect every match to be readable and want a loud failure otherwise.
 */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.query(
    options: QueryOptions? = null,
    filter: ((Model<T>) -> Boolean)? = null,
): QueryResponse<T> = reader.viewReader().query(this, options, filter)

/**
 * Like [query], but throws instead of skipping a model the actor may not read. Like [query], [filter] only sees
 * models the actor may read.
 *
 * @throws dev.klerkframework.klerk.AuthorizationException if the actor may not read a model the query comes across,
 * whether or not [filter] would have matched it
 */
context(reader: ModelReader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.queryOrThrow(
    options: QueryOptions? = null,
    filter: ((Model<T>) -> Boolean)? = null,
): QueryResponse<T> = reader.viewReader().queryOrThrow(this, options, filter)

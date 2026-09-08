package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.read.viewReader

/*
 * Reading a view. Every operation needs a Reader -- it is what proves the read lock is held -- but the reader is
 * taken as a context parameter, so inside a `klerk.read { }` block (where it is the receiver) or a
 * `with(args.reader) { }` block it does not have to be written out.
 *
 * They are ordered by cost. See docs/reading.md.
 */

/**
 * How many models are in the view. Answered from ids for an indexed view, without reading a single model.
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.count(): Int = count(reader)

/** True if the view holds nothing. Stops at the first id rather than counting them all. */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.isEmpty(): Boolean = isEmpty(reader)

/** True if the view holds anything. */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.isNotEmpty(): Boolean = !isEmpty(reader)

/** `id in view` -- an index lookup for an indexed view. */
context(reader: Reader<C, V>)
public operator fun <T : Any, C : KlerkContext, V> ModelView<T, C>.contains(id: ModelID<T>): Boolean =
    contains(id, reader)

/**
 * The ids in the view, in its own order, lazily. Reads no models at all.
 *
 * Do not use the sequence after the read block has ended — the view may have changed underneath it.
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.ids(): Sequence<ModelID<T>> = memberIds(reader)

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
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asSequence(): Sequence<Model<T>> =
    reader.viewReader().sequence(this)

/**
 * Like [asSequence], but throws instead of skipping a model the actor may not read.
 *
 * Do not use the sequence after the read block has ended — the view may have changed underneath it.
 *
 * @throws dev.klerkframework.klerk.AuthorizationException if the actor may not read a matching model
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asSequenceOrThrow(): Sequence<Model<T>> = withReader(reader)

/**
 * One page of the view. See [QueryOptions] for the page size and starting point, and [QueryResponse] for the page
 * and the cursors around it. [filter] is applied before the page is cut, so a page is full whenever enough models
 * match.
 *
 * Models the actor may not read are skipped before the page is cut, so pages stay full and cursors stay correct.
 * Use [queryOrThrow] when you expect every match to be readable and want a loud failure otherwise.
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.query(
    options: QueryOptions? = null,
    filter: ((Model<T>) -> Boolean)? = null,
): QueryResponse<T> = reader.viewReader().query(this, options, filter)

/**
 * Like [query], but throws instead of skipping a model the actor may not read.
 *
 * @throws dev.klerkframework.klerk.AuthorizationException if the actor may not read a matching model
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.queryOrThrow(
    options: QueryOptions? = null,
    filter: ((Model<T>) -> Boolean)? = null,
): QueryResponse<T> = reader.viewReader().queryOrThrow(this, options, filter)

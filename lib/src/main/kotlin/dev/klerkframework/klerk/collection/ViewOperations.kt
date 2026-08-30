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
 * The models in the view, in its own order, lazily: only the ones actually consumed are read. Prefer this to
 * [asList] whenever you do not need every model — `asSequence().take(10)`, `asSequence().any { … }`.
 *
 * Do not use the sequence after the read block has ended — the view may have changed underneath it.
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asSequence(): Sequence<Model<T>> = withReader(reader)

/** The first model matching [filter], or null. Stops as soon as one matches. */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.firstOrNull(
    filter: (Model<T>) -> Boolean = { true },
): Model<T>? = reader.viewReader().firstOrNull(this, filter)

/**
 * The first model matching [filter].
 * @throws NoSuchElementException if nothing matches
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.first(
    filter: (Model<T>) -> Boolean = { true },
): Model<T> = reader.viewReader().getFirstWhere(this, filter)

/**
 * One page of the view. See [QueryOptions] for the page size and starting point, and [QueryResponse] for the page
 * and the cursors around it. [filter] is applied before the page is cut, so a page is full whenever enough models
 * match.
 *
 * @throws dev.klerkframework.klerk.AuthorizationException if the actor may not read a matching model
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.query(
    options: QueryOptions? = null,
    filter: ((Model<T>) -> Boolean)? = null,
): QueryResponse<T> = reader.viewReader().query(this, options, filter)

/**
 * Like [query], but silently drops models the actor may not read instead of throwing. Only usable where
 * authorization is enforced, i.e. inside a `klerk.read` block.
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.queryIfAuthorized(
    options: QueryOptions? = null,
    filter: ((Model<T>) -> Boolean)? = null,
): QueryResponse<T> = reader.viewReader().queryIfAuthorized(this, options, filter)

/**
 * Every model in the view, as a list. Unbounded: it reads and holds all of them, and a model that is not resident
 * is fetched from storage. Reach for [count], [isEmpty], [firstOrNull], [asSequence] or [query] when they answer
 * the question, and use this when you genuinely want the whole thing.
 *
 * @throws dev.klerkframework.klerk.AuthorizationException if the actor may not read one of them
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asList(
    filter: ((Model<T>) -> Boolean)? = null,
): List<Model<T>> = reader.viewReader().list(this, filter)

/**
 * Like [asList], but silently drops models the actor may not read instead of throwing. Only usable where
 * authorization is enforced, i.e. inside a `klerk.read` block.
 */
context(reader: Reader<C, V>)
public fun <T : Any, C : KlerkContext, V> ModelView<T, C>.asListIfAuthorized(): List<Model<T>> =
    reader.viewReader().listIfAuthorized(this)

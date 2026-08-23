package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.decodeBase64String
import dev.klerkframework.klerk.misc.encodeBase64
import dev.klerkframework.klerk.read.Reader
import kotlin.time.Instant

/**
 * A named, typed, live-updating list of [Model] instances of type `T`. Every [ModelViews] gets an `all` view for
 * free; everything else is built by composing [filter], [filterStates] and [sorted] on top of it (or on another
 * view), then exposing it via [register]. See docs/views.md.
 *
 * Views are read through [Reader] (`withReader`, or the higher-level `Reader.list`/`get`/etc.), never queried
 * directly — that's what makes their content authorization-checked and lock-consistent with the rest of a read.
 */
public abstract class ModelView<T : Any, C : KlerkContext>(private val parent: ModelView<T, C>?) {

    private var idBase: String? = null
    protected var _id: String? = null

    // These are only accessed while holding a ReadWriteLock. Readers run concurrently but only read; view content is
    // replaced under the write lock during a commit, which excludes every reader.

    /*
    - mutex så att inte alla gör get samtidigt ?
    - måste T vara Any?
    - gör suspending så vi kan förbereda för att ha datan i rooks? (ny möjlighet har visat sig)
    - fun hasChanged(reader.lastEventId): Boolean så att man kan hålla data
    - fun combine(other: Collection<U>): Collection
    - hålla koll på hur ofta som filtret används. Då kan en manager bättre besluta om vilka listsources som ska cachea.

     */

    /**
     * Returns a new view containing only models matching [filter]. Passing `null` returns `this` unchanged (useful
     * for optional filters composed conditionally).
     */
    public open fun filter(filter: ((Model<T>) -> Boolean)?): ModelView<T, C> {
        if (filter == null) {
            return this
        }
        val new = FilteredModelView(this, filter)
        return new
    }

    /**
     * Returns a new view restricted by [Model.state] name: keep only [included] (if given), and drop any in
     * [excluded] (if given). Both may be provided together.
     */
    public fun filterStates(included: Set<String>? = null, excluded: Set<String>? = null): ModelView<T, C> {
        val new = IncludeStatesModelView(this, included, excluded)
        return new
    }

    /** Returns a new view with the same models, ordered by [selector]. */
    public fun <R : Comparable<R>> sorted(selector: (Model<T>) -> R, ascending: Boolean = true): ModelView<T, C> {
        val new = SortedModelView(this, selector, ascending)
        return new
    }

    /**
     * Creates a Sequence of the Collection content.
     * It is usually better to use the methods in Reader (query, list etc.) but if you must use this, be careful
     * to not use it after you have released the Reader since that may lead to ConcurrentModificationException.
     */
    public abstract fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor? = null): Sequence<Model<T>>

    public fun <V> isEmpty(reader: Reader<C, V>): Boolean = withReader(reader, null).toList().isEmpty()

    /**
     * @return the id this view was [register]ed under, combined with the owning model class's name (e.g.
     * `c.Author.establishedAuthors`)
     * @throws IllegalStateException if this view was never registered
     */
    public open fun getFullId(): CollectionId {
        check(idBase != null && _id != null)
        return CollectionId(idBase!!, _id!!)
    }

    /**
     * @return the id this view was [register]ed under
     * @throws IllegalStateException if this view was never registered
     */
    public open fun getId(): String = _id ?: error("Collection is missing ID")

    /** The [ModelViews] this view (or, for a derived view, its ultimate ancestor) belongs to. */
    public open fun getView(): ModelViews<T, C> = parent?.getView() ?: throw IllegalStateException()
    public fun <V> count(reader: Reader<C, V>): Int = withReader(reader, null).count()

    /** @return true if a model with this id is currently in the view */
    public open fun <V> contains(value: ModelID<*>, reader: Reader<C, V>): Boolean {
        return withReader(reader, null).any { it.id == value }
    }

    /**
     * Makes Klerk aware of this view: gives it a stable id and adds it to `Specification.getCollections()`, so it can be
     * looked up by [CollectionId] (e.g. by `validReferences` error messages) and shows up in generated docs. A view
     * that is never registered still works if you hold a reference to it, but can't be looked up by id.
     *
     * @param id must not contain `.` or spaces
     * @throws IllegalArgumentException if [id] contains `.` or a space
     */
    public fun register(id: String): ModelView<T, C> {
        require(!id.contains(".") && !id.contains(" ")) { "Illegal collection ID: $id" }
        this._id = id
        getView().register(this)
        return this
    }

    internal fun setIdBase(idBase: String?) {
        this.idBase = idBase
    }

    /** Eagerly collects this view's content into a [List]. Prefer [Reader]'s `list`/`query` unless you specifically need a `List`. */
    public fun <D> readWith(reader: Reader<C, D>): List<Model<T>> = withReader(reader).toList()

}

/** The result of [ModelView.sorted]. */
public class SortedModelView<T : Any, R : Comparable<R>, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val selector: (Model<T>) -> R,
    private val ascending: Boolean
) : ModelView<T, C>(previous) {

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> {
        return if (ascending) previous.withReader(reader, cursor)
            .sortedBy(selector) else previous.withReader(reader, cursor).sortedByDescending(selector)
    }

}

/** The result of [ModelView.filterStates]. */
public class IncludeStatesModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val included: Set<String>?,
    private val excluded: Set<String>?
) : ModelView<T, C>(previous) {

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> {
        var s = previous.withReader(reader, cursor)
        if (included != null) {
            s = s.filter { included.contains(it.state) }
        }
        if (excluded != null) {
            s = s.filter { !excluded.contains(it.state) }
        }
        return s
    }

}

/** The result of [ModelView.filter]. */
public class FilteredModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val predicate: (Model<T>) -> Boolean,
) : ModelView<T, C>(previous) {

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> =
        previous.withReader(reader, cursor).filter(predicate)

}

/**
 * The `all` view every [ModelViews] provides for free: every instance of `T`, ordered by [Model.createdAt].
 * The root of every other view for that model type.
 */
public class AllModelView<T : Any, C : KlerkContext>(
    private val view: ModelViews<T, C>,
    private val all: List<Int>  // sorted by createdAt
) : ModelView<T, C>(null) {

    init {
        _id = "all"
    }

    override fun getView(): ModelViews<T, C> = view

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> {
        if (cursor == null) {
            return all.asSequence().map { reader.get(ModelID(it)) }
        }
        require(cursor.field == QueryCursorField.CREATED_AT)
        if (cursor.after != null) {
            return ascending(cursor.after, cursor.including, reader)
        }
        if (cursor.before != null) {
            return descending(cursor.before, cursor.including, reader)
        }
        throw IllegalStateException()
    }

    private fun <V> ascending(after: Instant, include: Boolean, reader: Reader<C, V>): Sequence<Model<T>> {
        // find the model created at or the first model created after the cursor
        // we know that the list is sorted by createdAt and all models have unique createdAt
        val startIndex = all.binarySearch { ref ->
            val model = reader.get(ModelID(ref))
            if (model.createdAt < after) {
                return@binarySearch -1
            }
            if (model.createdAt == after) {
                return@binarySearch 0
            }
            val index = all.indexOf(ref)
            if (index == 0) {
                return@binarySearch -1
            }
            val previous = reader.get(ModelID(all[index - 1]))
            return@binarySearch if (previous.createdAt < after) 0 else 1
        }
        if (include) {
            return all.subList(startIndex, all.lastIndex + 1).asSequence().map { reader.get(ModelID(it)) }
        }
        if (startIndex + 1 > all.lastIndex) {
            return emptySequence()
        }
        return all.subList(startIndex + 1, all.lastIndex + 1).asSequence().map { reader.get(ModelID(it)) }
    }

    private fun <V> descending(before: Instant, include: Boolean, reader: Reader<C, V>): Sequence<Model<T>> {
        // find the model created at or the last model created before the cursor
        // we know that the list is sorted by createdAt and all models have unique createdAt
        val startIndex = all.binarySearch { ref ->
            val model = reader.get(ModelID(ref))
            if (model.createdAt > before) {
                return@binarySearch 1
            }
            if (model.createdAt == before) {
                return@binarySearch 0
            }
            val index = all.indexOf(ref)
            if (index == 0) {
                return@binarySearch 0
            }
            if (index == all.lastIndex) {
                return@binarySearch 1
            }
            val next = reader.get(ModelID(all[index + 1]))
            return@binarySearch if (next.createdAt > before) 0 else -1
        }
        if (startIndex < 0) {
            return all.asReversed().asSequence().map { reader.get(ModelID(it)) }
        }
        if (include && startIndex < all.lastIndex) {
            return all.subList(0, startIndex + 1).asReversed().asSequence().map { reader.get(ModelID(it)) }
        }
        return all.subList(0, startIndex).asReversed().asSequence().map { reader.get(ModelID(it)) }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<C, V>): Boolean = all.contains(value.value)
}

/**
 * Page-size and starting point for a paginated [ModelView] read (`Reader.query`).
 *
 * @throws IllegalArgumentException if [maxItems] is not positive
 */
public data class QueryOptions(
    val maxItems: Int = 50,
    val cursor: QueryListCursor? = null
) {

    init {
        require(maxItems > 0)
    }

}

/** One page of a paginated [ModelView] read, with cursors for the adjacent pages. */
public data class QueryResponse<T : Any>(
    val items: List<Model<T>>,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
    val cursorFirst: QueryListCursor?,
    val cursorPrevious: QueryListCursor?,
    val cursorNext: QueryListCursor?,
    val cursorLast: QueryListCursor?,
    val options: QueryOptions?
)

/** Which [Model] field a [QueryListCursor] is positioned against. Only [CREATED_AT] is currently supported. */
public enum class QueryCursorField {
    CREATED_AT
}

/**
 * An opaque, serializable pagination cursor for [ModelView] reads, positioned either [after] or [before] a point in
 * [field]. Serializes to/from a compact string via [toString]/[fromString].
 *
 * @throws IllegalArgumentException unless exactly one of [after]/[before] is set
 */
public data class QueryListCursor(
    val after: Instant? = null,
    val before: Instant? = null,
    val field: QueryCursorField = QueryCursorField.CREATED_AT
) {

    init {
        require((after != null || before != null) && (after == null || before == null)) { "One of after or before must be set" }
    }

    internal var including: Boolean = false

    public companion object {
        /** A cursor positioned before the earliest possible item. */
        public val first: QueryListCursor = QueryListCursor(after = Instant.DISTANT_PAST)

        /** A cursor positioned after the latest possible item. */
        public val last: QueryListCursor = QueryListCursor(before = decode64bitMicroseconds(Long.MAX_VALUE))
        public const val DEFAULT_ITEMS_PER_PAGE: Int = 100

        /**
         * Parses a cursor previously serialized with [QueryListCursor.toString].
         * @throws IllegalArgumentException if [s] is not a validly encoded cursor
         */
        public fun fromString(s: String): QueryListCursor {
            val map = mutableMapOf<String, String>()
            val keyValues = s.decodeBase64String().split(",")
            keyValues.forEach {
                val (key, value) = it.split(":")
                map[key] = value
            }
            return QueryListCursor(
                after = map["a"]?.toLong()?.let { decode64bitMicroseconds(it) },
                before = map["b"]?.toLong()?.let { decode64bitMicroseconds(it) },
                field = QueryCursorField.valueOf(map["f"] ?: throw IllegalArgumentException())
            )
        }
    }

    override fun toString(): String {
        if (after != null) {
            return "a:${after.to64bitMicroseconds()},f:$field".encodeBase64()
        }
        if (before != null) {
            return "b:${before.to64bitMicroseconds()},f:$field".encodeBase64()
        }
        throw IllegalStateException()
    }
}

package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.decodeBase64String
import dev.klerkframework.klerk.misc.encodeBase64
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import kotlin.time.Instant

/**
 * A named, typed, live-updating list of [Model] instances of type `T`. Every [ModelViews] gets an `all` view for
 * free; everything else is built by composing [filter], [filterStates] and [sorted] on top of it (or on another
 * view), then exposing it via [register]. See docs/views.md.
 *
 * Views are read through [Reader] (`withReader`, or the higher-level `Reader.list`/`get`/etc.), never queried
 * directly — that's what makes their content authorization-checked and lock-consistent with the rest of a read.
 */
public abstract class ModelView<T : Any, C : KlerkContext>(internal val parent: ModelView<T, C>?) {

    private var idBase: String? = null
    protected var _id: String? = null

    /*
    - måste T vara Any?
    - gör suspending så vi kan förbereda för att ha datan i rooks? (ny möjlighet har visat sig)
    - fun hasChanged(reader.lastEventId): Boolean så att man kan hålla data
    - fun combine(other: Collection<U>): Collection
     */

    /** Views derived from this one, so that a change can be propagated to them. See [index]. */
    private val children = mutableListOf<ModelView<T, C>>()

    /**
     * The ids currently in this view, or null while it has not been built yet.
     *
     * Built on first query and then kept current one model at a time as commands are processed, which is what makes a
     * query a set lookup rather than a scan. Building creates a new set and publishes it; maintaining mutates it, and
     * only ever under the write lock, which excludes every reader.
     */
    @Volatile
    private var index: MutableSet<Int>? = null

    init {
        parent?.attach(this)
    }

    private fun attach(child: ModelView<T, C>) {
        // Views are part of the application's shape and are built before Klerk starts. One built later -- typically a
        // `filter` inside a read block -- is left detached: it would otherwise be maintained, and retained, forever.
        if (getView().isFrozen) {
            return
        }
        children.add(child)
    }

    /**
     * True if membership can be decided by [matches] alone, for this view and every view it is derived from. False for
     * a custom [ModelView], whose `withReader` may depend on anything at all, and for anything derived from one.
     */
    internal open val isIndexable: Boolean
        get() = narrowsByPredicate && parent?.isIndexable == true

    /** Whether this view narrows its parent by a predicate on a single model. */
    internal open val narrowsByPredicate: Boolean
        get() = false

    /** Whether [model] belongs in this view, given that its parent contains it. Only meaningful if [narrowsByPredicate]. */
    internal open fun matches(model: Model<T>): Boolean = true

    /** Whether this view contains [id], answered from the index. Only valid once [ensureIndex] has run. */
    internal open fun containsId(id: Int): Boolean = index?.contains(id) ?: false

    /**
     * Builds this view's index if it has one and it has not been built, parents first. Returns null for a view that
     * cannot be indexed.
     *
     * Two readers may build the same view at once; the work is done under a lock so that only one of them publishes.
     * Writers cannot interleave with this at all, since a commit holds the write lock and a build holds a read lock.
     */
    internal open fun <V> ensureIndex(reader: Reader<C, V>): Set<Int>? {
        if (!isIndexable) {
            return null
        }
        index?.let { return it }
        // The index must reflect every model, not the ones this reader may see, so it is only ever built from the
        // unauthorized reader that Klerk's own read paths use. Authorization is applied to the result afterwards.
        if (reader !is ReaderWithoutAuth<*, *>) {
            return null
        }
        synchronized(this) {
            index?.let { return it }
            val parentIds = requireNotNull(parent).ensureIndex(reader) ?: return null
            val built = HashSet<Int>()
            parentIds.forEach { id ->
                if (matches(reader.get(ModelID(id)))) {
                    built.add(id)
                }
            }
            index = built
            return built
        }
    }

    /**
     * The ids of this view, taken from its index: the parent's ids in order, narrowed by a set lookup. No model is
     * read at all. Null when this view has no index, leaving the caller to evaluate its predicate the slow way.
     */
    protected fun <V> indexedMemberIds(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<ModelID<T>>? {
        val members = ensureIndex(reader) ?: return null
        return requireNotNull(parent).memberIds(reader, cursor).filter { members.contains(it.value) }
    }

    /** Drops this view's index, and every derived view's, so that they are rebuilt against freshly loaded models. */
    internal fun clearIndex() {
        index = null
        children.forEach { it.clearIndex() }
    }

    internal open fun onModelCreated(model: Model<T>) {
        index?.let { if (parent!!.containsId(model.id.value) && matches(model)) it.add(model.id.value) }
        children.forEach { it.onModelCreated(model) }
    }

    internal open fun onModelUpdated(before: Model<T>, after: Model<T>) {
        index?.let {
            val id = after.id.value
            if (parent!!.containsId(id) && matches(after)) it.add(id) else it.remove(id)
        }
        children.forEach { it.onModelUpdated(before, after) }
    }

    internal open fun onModelDeleted(model: Model<T>) {
        index?.remove(model.id.value)
        children.forEach { it.onModelDeleted(model) }
    }

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
     * The ids in this view, in order — the one thing a [ModelView] has to be able to answer.
     *
     * Return ids rather than models so that Klerk never reads a model the view does not contain: a narrow view over a
     * large parent then costs what the view holds, not what its parent holds. `count`, `contains` and `isEmpty` are
     * answered from this without reading anything at all, so a view that knows its ids directly — say from a `Map` it
     * maintains through [ModelViews.didCreate] and friends — answers them for free.
     *
     * @param cursor where to start and in which direction, for a paginated read. Views that don't handle it themselves
     * pass it on to the view they are derived from.
     */
    public abstract fun <V> memberIds(reader: Reader<C, V>, cursor: QueryListCursor? = null): Sequence<ModelID<T>>

    /**
     * The content of this view, as models. Reads each model of [memberIds] through [reader], so be careful not to use
     * the sequence after the reader has been released — that may lead to ConcurrentModificationException. It is
     * usually better to use the methods in Reader (query, list etc.).
     */
    public fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor? = null): Sequence<Model<T>> =
        memberIds(reader, cursor).map { reader.get(it) }

    public fun <V> isEmpty(reader: Reader<C, V>): Boolean = memberIds(reader, null).none()

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
    public fun <V> count(reader: Reader<C, V>): Int = memberIds(reader, null).count()

    /**
     * @return true if a model with this id is currently in the view
     *
     * Walks [memberIds] unless the view is indexed. Override it when the view can answer membership directly — it is
     * asked once per `validReferences` check, i.e. on the command path.
     */
    public open fun <V> contains(value: ModelID<*>, reader: Reader<C, V>): Boolean {
        ensureIndex(reader)?.let { return it.contains(value.value) }
        return memberIds(reader, null).any { it.value == value.value }
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

    // Sorting does not change what the view contains, so membership questions go straight to the parent and a view
    // derived from this one can still be indexed -- without this view keeping an index of its own.
    override val isIndexable: Boolean get() = previous.isIndexable
    override fun containsId(id: Int): Boolean = previous.containsId(id)
    override fun <V> ensureIndex(reader: Reader<C, V>): Set<Int>? = previous.ensureIndex(reader)

    // Unlike the other views this must read every model, since only the model itself answers where it sorts.
    override fun <V> memberIds(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<ModelID<T>> {
        val models = previous.withReader(reader, cursor)
        return (if (ascending) models.sortedBy(selector) else models.sortedByDescending(selector)).map { it.id }
    }

}

/** The result of [ModelView.filterStates]. */
public class IncludeStatesModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val included: Set<String>?,
    private val excluded: Set<String>?
) : ModelView<T, C>(previous) {

    override val narrowsByPredicate: Boolean get() = true

    override fun matches(model: Model<T>): Boolean =
        (included == null || included.contains(model.state)) && (excluded == null || !excluded.contains(model.state))

    override fun <V> memberIds(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<ModelID<T>> =
        indexedMemberIds(reader, cursor)
            ?: previous.memberIds(reader, cursor).filter { matches(reader.get(it)) }

}

/** The result of [ModelView.filter]. */
public class FilteredModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val predicate: (Model<T>) -> Boolean,
) : ModelView<T, C>(previous) {

    override val narrowsByPredicate: Boolean get() = true

    override fun matches(model: Model<T>): Boolean = predicate(model)

    override fun <V> memberIds(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<ModelID<T>> =
        indexedMemberIds(reader, cursor)
            ?: previous.memberIds(reader, cursor).filter { predicate(reader.get(it)) }

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

    // The set of every id of this type is maintained by ModelViews, so this view is always indexed and never builds
    // anything.
    override val isIndexable: Boolean get() = true
    override fun containsId(id: Int): Boolean = view.containsId(id)
    override fun <V> ensureIndex(reader: Reader<C, V>): Set<Int> = view.allIdSet()

    override fun <V> memberIds(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<ModelID<T>> {
        if (cursor == null) {
            return all.asSequence().map { ModelID(it) }
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

    private fun <V> ascending(after: Instant, include: Boolean, reader: Reader<C, V>): Sequence<ModelID<T>> {
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
            return all.subList(startIndex, all.lastIndex + 1).asSequence().map { ModelID(it) }
        }
        if (startIndex + 1 > all.lastIndex) {
            return emptySequence()
        }
        return all.subList(startIndex + 1, all.lastIndex + 1).asSequence().map { ModelID(it) }
    }

    private fun <V> descending(before: Instant, include: Boolean, reader: Reader<C, V>): Sequence<ModelID<T>> {
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
            return all.asReversed().asSequence().map { ModelID(it) }
        }
        if (include && startIndex < all.lastIndex) {
            return all.subList(0, startIndex + 1).asReversed().asSequence().map { ModelID(it) }
        }
        return all.subList(0, startIndex).asReversed().asSequence().map { ModelID(it) }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<C, V>): Boolean = view.containsId(value.value)
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

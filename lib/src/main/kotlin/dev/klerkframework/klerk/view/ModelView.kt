package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.decodeBase64UrlSafeString
import dev.klerkframework.klerk.misc.encodeBase64UrlSafe
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.read.unauthorized

/**
 * A named, typed, live-updating list of [Model] instances of type `T`. Every [ModelViews] gets an `all` view for
 * free; everything else is built by composing [filter], [filterStates] and [sorted] on top of it (or on another
 * view), then exposing it via [register]. See docs/views.md.
 *
 * Views are read through [ModelReader] (`withReader`, or the higher-level `count`/`asSequence`/`query` extensions), never queried
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
        if (modelViews.isFrozen) {
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
    internal open fun <V> ensureIndex(reader: ModelReader<C, V>): Set<Int>? {
        if (!isIndexable) {
            return null
        }
        index?.let { return it }
        // The index must reflect every model, not the ones this reader may see, so it is always built through the
        // unauthorized reader behind the caller's. Authorization is applied to the result afterwards. Unwrapping here
        // rather than demanding a ReaderWithoutAuth is what lets a plain `view.count()` inside a read block be cheap:
        // the receiver there is a ReaderWithAuth, and it used to fall through to reading every model.
        val unauthorized = reader.unauthorized() ?: return null
        synchronized(this) {
            index?.let { return it }
            val parentIds = requireNotNull(parent).ensureIndex(unauthorized) ?: return null
            val built = HashSet<Int>()
            parentIds.forEach { id ->
                if (matches(unauthorized.get(ModelID(id)))) {
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
    protected fun <V> indexedMemberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>>? {
        val members = ensureIndex(reader) ?: return null
        return requireNotNull(parent).memberIds(reader).filter { members.contains(it.value) }
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
     * Return the whole view: pagination is applied by `Reader.query` to whatever this returns, so a view never has to
     * deal with cursors.
     */
    public abstract fun <V> memberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>>

    /**
     * The content of this view, as models. Reads each model of [memberIds] through [reader], so be careful not to use
     * the sequence after the reader has been released — that may lead to ConcurrentModificationException. It is
     * usually better to use the methods in Reader (query, list etc.).
     */
    public fun <V> withReader(reader: ModelReader<C, V>): Sequence<Model<T>> =
        memberIds(reader).map { reader.get(it) }

    /** Answered from the index when there is one, so no model is read. */
    public open fun <V> isEmpty(reader: ModelReader<C, V>): Boolean =
        ensureIndex(reader)?.isEmpty() ?: memberIds(reader).none()

    /**
     * The id this view was [register]ed under, combined with the owning model class's name (e.g.
     * `v.Author.establishedAuthors`). Use [ViewId.shortId] for the id on its own.
     *
     * @throws IllegalStateException if this view was never registered
     */
    public open val id: ViewId
        get() {
            check(idBase != null && _id != null) { "This view was never registered" }
            return ViewId(idBase!!, _id!!)
        }

    /** The [ModelViews] this view (or, for a derived view, its ultimate ancestor) belongs to. */
    public open val modelViews: ModelViews<T, C>
        get() = parent?.modelViews ?: throw IllegalStateException("This view has no ModelViews")
    /** Answered from the index when there is one, so no model is read. */
    public open fun <V> count(reader: ModelReader<C, V>): Int =
        ensureIndex(reader)?.size ?: memberIds(reader).count()

    /**
     * @return true if a model with this id is currently in the view
     *
     * Walks [memberIds] unless the view is indexed. Override it when the view can answer membership directly — it is
     * asked once per `validReferences` check, i.e. on the command path.
     */
    public open fun <V> contains(value: ModelID<*>, reader: ModelReader<C, V>): Boolean {
        ensureIndex(reader)?.let { return it.contains(value.value) }
        return memberIds(reader).any { it.value == value.value }
    }

    /**
     * Makes Klerk aware of this view: gives it a stable id and adds it to `Specification.getViews()`, so it can be
     * looked up by [ViewId] (e.g. by `validReferences` error messages) and shows up in generated docs. A view
     * that is never registered still works if you hold a reference to it, but can't be looked up by id.
     *
     * @param id must not contain `.` or spaces
     * @throws IllegalArgumentException if [id] contains `.` or a space
     */
    public fun register(id: String): ModelView<T, C> {
        require(!id.contains(".") && !id.contains(" ")) { "Illegal view id: $id" }
        this._id = id
        modelViews.register(this)
        return this
    }

    internal fun setIdBase(idBase: String?) {
        this.idBase = idBase
    }

    /** Eagerly collects this view's content into a [List]. Prefer `asSequence()`/`query()` unless you specifically need a `List`. */
    public fun <D> readWith(reader: ModelReader<C, D>): List<Model<T>> = withReader(reader).toList()

}

/** The result of [ModelView.sorted]. */
internal class SortedModelView<T : Any, R : Comparable<R>, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val selector: (Model<T>) -> R,
    private val ascending: Boolean
) : ModelView<T, C>(previous) {

    // Sorting does not change what the view contains, so membership questions go straight to the parent and a view
    // derived from this one can still be indexed -- without this view keeping an index of its own.
    override val isIndexable: Boolean get() = previous.isIndexable
    override fun containsId(id: Int): Boolean = previous.containsId(id)
    override fun <V> ensureIndex(reader: ModelReader<C, V>): Set<Int>? = previous.ensureIndex(reader)

    // Cardinality is a question about membership, so it goes to the parent rather than through memberIds, which would
    // read and sort every model just to count it.
    override fun <V> count(reader: ModelReader<C, V>): Int = previous.count(reader)
    override fun <V> isEmpty(reader: ModelReader<C, V>): Boolean = previous.isEmpty(reader)

    // Unlike the other views this must read every model, since only the model itself answers where it sorts.
    override fun <V> memberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>> {
        val models = previous.withReader(reader)
        return (if (ascending) models.sortedBy(selector) else models.sortedByDescending(selector)).map { it.id }
    }

}

/** The result of [ModelView.filterStates]. */
internal class IncludeStatesModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val included: Set<String>?,
    private val excluded: Set<String>?
) : ModelView<T, C>(previous) {

    override val narrowsByPredicate: Boolean get() = true

    override fun matches(model: Model<T>): Boolean =
        (included == null || included.contains(model.state)) && (excluded == null || !excluded.contains(model.state))

    override fun <V> memberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>> =
        indexedMemberIds(reader)
            ?: previous.memberIds(reader).filter { matches(reader.get(it)) }

}

/** The result of [ModelView.filter]. */
internal class FilteredModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val predicate: (Model<T>) -> Boolean,
) : ModelView<T, C>(previous) {

    override val narrowsByPredicate: Boolean get() = true

    override fun matches(model: Model<T>): Boolean = predicate(model)

    override fun <V> memberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>> =
        indexedMemberIds(reader)
            ?: previous.memberIds(reader).filter { predicate(reader.get(it)) }

}

/**
 * The `all` view every [ModelViews] provides for free: every instance of `T`, ordered by [Model.createdAt].
 * The root of every other view for that model type.
 */
internal class AllModelView<T : Any, C : KlerkContext>(
    private val view: ModelViews<T, C>,
    private val all: List<Int>  // sorted by createdAt
) : ModelView<T, C>(null) {

    init {
        _id = "all"
    }

    override val modelViews: ModelViews<T, C> get() = view

    // The set of every id of this type is maintained by ModelViews, so this view is always indexed and never builds
    // anything.
    override val isIndexable: Boolean get() = true
    override fun containsId(id: Int): Boolean = view.containsId(id)
    override fun <V> ensureIndex(reader: ModelReader<C, V>): Set<Int> = view.allIdSet()

    override fun <V> memberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>> = all.asSequence().map { ModelID(it) }

    override fun <V> contains(value: ModelID<*>, reader: ModelReader<C, V>): Boolean = view.containsId(value.value)
}

/** Where [QueryOptions.cursor] sits relative to the page. */
public enum class PageDirection {
    /** The page starts at the cursor. This is what the cursors in a [QueryResponse] are meant for. */
    FROM,

    /** The page starts immediately after the cursor. */
    AFTER,

    /** The page ends immediately before the cursor. */
    BEFORE,
}

/**
 * Page size and starting point for a paginated [ModelView] read (`Reader.query`).
 *
 * @param maxItems the most items the page may hold.
 * @param cursor where the page sits; null means the start of the view.
 * @param direction where [cursor] sits relative to the page.
 * @param countTotal read the whole view to fill in [QueryResponse.totalCount] and
 * [QueryResponse.cursorLastPage]. Off by default, since it costs a full pass.
 * @throws IllegalArgumentException if [maxItems] is not positive
 */
public data class QueryOptions(
    val maxItems: Int = 50,
    val cursor: QueryListCursor? = null,
    val direction: PageDirection = PageDirection.FROM,
    val countTotal: Boolean = false,
) {

    init {
        require(maxItems > 0)
    }

}

/**
 * One page of a paginated [ModelView] read, with cursors for the adjacent pages. Every cursor is null when there is
 * no such page, so a pagination control can render a link for exactly the cursors it was given.
 *
 * @param totalCount the size of the whole view, or null unless [QueryOptions.countTotal] was set.
 * @param cursorLastPage null unless [QueryOptions.countTotal] was set.
 */
public data class QueryResponse<T : Any>(
    val items: List<Model<T>>,
    val cursorFirstPage: QueryListCursor?,
    val cursorPreviousPage: QueryListCursor?,
    val cursorNextPage: QueryListCursor?,
    val cursorLastPage: QueryListCursor?,
    val totalCount: Int?,
    /** Where [items] start in the view, needed by [cursorAt]. */
    internal val offset: Int = 0,
) {

    public val hasPreviousPage: Boolean get() = cursorPreviousPage != null
    public val hasNextPage: Boolean get() = cursorNextPage != null

    /**
     * A cursor pointing at the item at [index] of [items]. Use it when every row needs its own position rather than
     * the page as a whole — a GraphQL edge cursor, for instance.
     *
     * @throws IndexOutOfBoundsException if [index] is not an index of [items]
     */
    public fun cursorAt(index: Int): QueryListCursor {
        if (index !in items.indices) {
            throw IndexOutOfBoundsException("No item at index $index, the page holds ${items.size} items")
        }
        return QueryListCursor(offset + index, items[index].id.value)
    }

}

/**
 * An opaque position in a [ModelView]. Serializes to and from a URL-safe string via [toString]/[parse]; treat
 * that string as meaningless and don't build one yourself.
 *
 * A cursor is a position, not a snapshot: it resolves to the item it was cut at whenever that item is still in the
 * view, so models created or deleted meanwhile neither skip nor repeat a row. If that item is gone, the raw position
 * is used and a row may shift.
 */
public class QueryListCursor internal constructor(
    internal val offset: Int,
    internal val anchor: Int?,
) {

    init {
        require(offset >= 0)
    }

    public companion object {
        /** The start of the view. */
        public val first: QueryListCursor = QueryListCursor(0, null)

        /**
         * Parses a cursor previously serialized with [QueryListCursor.toString].
         * @throws IllegalArgumentException if [s] is not a validly encoded cursor
         */
        public fun parse(s: String): QueryListCursor {
            val fields = try {
                s.decodeBase64UrlSafeString().split(",").associate { field ->
                    val separator = field.indexOf(':')
                    require(separator > 0)
                    field.substring(0, separator) to field.substring(separator + 1)
                }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Not a cursor: '$s'", e)
            }
            val offset = fields["o"]?.toIntOrNull()
            require(offset != null && offset >= 0) { "Not a cursor: '$s'" }
            val anchorField = fields["a"]
            val anchor = if (anchorField == null) null else {
                requireNotNull(anchorField.toIntOrNull()) { "Not a cursor: '$s'" }
            }
            return QueryListCursor(offset, anchor)
        }

        /** The cursor in [s], or null if it is not one. */
        public fun parseOrNull(s: String): QueryListCursor? = runCatching { parse(s) }.getOrNull()
    }

    override fun toString(): String =
        (if (anchor == null) "o:$offset" else "o:$offset,a:$anchor").encodeBase64UrlSafe()

    override fun equals(other: Any?): Boolean =
        other is QueryListCursor && other.offset == offset && other.anchor == anchor

    override fun hashCode(): Int = offset * 31 + (anchor ?: 0)
}

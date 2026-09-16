package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.read.unauthorized

/**
 * A named, typed, live-updating list of [Model] instances of type `T`. Every [ModelViews] gets an `all` view for
 * free; everything else is built by composing [filter], [filterStates] and [sorted] on top of it (or on another
 * view), then exposing it via [register]. See docs/views.md.
 *
 * Views are read through [ModelReader] (the `count`/`asSequence`/`query` extensions), never queried
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
     * a custom [ModelView], whose `memberIds` may depend on anything at all, and for anything derived from one.
     */
    internal open val isIndexable: Boolean
        get() = narrowsByPredicate && parent?.isIndexable == true

    /** The states this view filters on, if any. See [filterStates]. */
    internal open val filteredStates: Set<Enum<*>> get() = emptySet()

    /** The states this view and every view it is derived from filter on. Checked at startup. */
    internal fun allFilteredStates(): Set<Enum<*>> = filteredStates + (parent?.allFilteredStates() ?: emptySet())

    /** Whether this view narrows its parent by a predicate on a single model. */
    internal open val narrowsByPredicate: Boolean
        get() = false

    /**
     * Whether [model] belongs in this view, given that its parent contains it. Only meaningful if [narrowsByPredicate].
     */
    internal open fun matches(model: Model<T>): Boolean = true

    /** Whether this view contains [id], answered from the index. Only valid once [ensureIndex] has run. */
    internal open fun containsId(id: Int): Boolean = index?.contains(id) == true

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
            for (id in parentIds) {
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
        return requireNotNull(parent).memberIds(reader).filter { it.value in members }
    }

    /** Drops this view's index, and every derived view's, so that they are rebuilt against freshly loaded models. */
    internal fun clearIndex() {
        index = null
        for (child in children) {
            child.clearIndex()
        }
    }

    internal open fun onModelCreated(model: Model<T>) {
        index?.let { if (requireNotNull(parent).containsId(model.id.value) && matches(model)) it.add(model.id.value) }
        for (child in children) {
            child.onModelCreated(model)
        }
    }

    internal open fun onModelUpdated(before: Model<T>, after: Model<T>) {
        index?.let {
            val id = after.id.value
            if (requireNotNull(parent).containsId(id) && matches(after)) it.add(id) else it.remove(id)
        }
        for (child in children) {
            child.onModelUpdated(before, after)
        }
    }

    internal open fun onModelDeleted(model: Model<T>) {
        index?.remove(model.id.value)
        for (child in children) {
            child.onModelDeleted(model)
        }
    }

    /** Returns a new view containing only models matching [filter]. */
    public open fun filter(filter: (Model<T>) -> Boolean): ModelView<T, C> = FilteredModelView(this, filter)

    /**
     * Returns a new view restricted by [Model.state]: keep only [included] (if given), and drop any in [excluded] (if
     * given). Both may be provided together, and both take the state machine's own enum:
     *
     * ```
     * val published = all.filterStates(included = setOf(BookStates.Published))
     * ```
     *
     * Klerk fails at startup if a state does not belong to the state machine of this view's model.
     */
    public fun filterStates(included: Set<Enum<*>>? = null, excluded: Set<Enum<*>>? = null): ModelView<T, C> {
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

    /** The content of this view, as models. The caller API is `asSequence()` / `asSequenceOrThrow()`. */
    internal fun <V> withReader(reader: ModelReader<C, V>): Sequence<Model<T>> =
        memberIds(reader).map { reader.get(it) }

    /** Answered from the index when there is one, so no model is read. Override it when the view can do better. */
    protected open fun <V> isEmpty(reader: ModelReader<C, V>): Boolean =
        ensureIndex(reader)?.isEmpty() ?: memberIds(reader).none()

    /**
     * The id this view was [register]ed under, combined with the owning model class's name (e.g.
     * `v.Author.establishedAuthors`). Use [ViewID.shortId] for the id on its own.
     *
     * @throws IllegalStateException if this view was never registered
     */
    public open val id: ViewID
        get() {
            val base = checkNotNull(idBase) { "This view was never registered" }
            return ViewID(base, checkNotNull(_id) { "This view was never registered" })
        }

    /** The [ModelViews] this view (or, for a derived view, its ultimate ancestor) belongs to. */
    public open val modelViews: ModelViews<T, C>
        get() = parent?.modelViews ?: error("This view has no ModelViews")
    /** Answered from the index when there is one, so no model is read. Override it when the view can do better. */
    protected open fun <V> count(reader: ModelReader<C, V>): Int =
        ensureIndex(reader)?.size ?: memberIds(reader).count()

    /**
     * True if a model with the id [value] is currently in the view.
     *
     * Walks [memberIds] unless the view is indexed. Override it when the view can answer membership directly — it is
     * asked once per `validReferences` check, i.e. on the command path.
     */
    protected open fun <V> contains(value: ModelID<*>, reader: ModelReader<C, V>): Boolean {
        ensureIndex(reader)?.let { return value.value in it }
        return memberIds(reader).any { it.value == value.value }
    }

    /**
     * Makes Klerk aware of this view: gives it a stable id and adds it to `Specification.registeredViews`, so it can be
     * looked up by [ViewID] (e.g. by `validReferences` error messages) and shows up in generated docs. A view
     * that is never registered still works if you hold a reference to it, but can't be looked up by id.
     *
     * @throws IllegalArgumentException if [id] contains `.` or a space
     */
    public fun register(id: String): ModelView<T, C> {
        require("." !in id && " " !in id) { "Illegal view id: $id" }
        this._id = id
        modelViews.register(this)
        return this
    }

    /** The id given to [register], or null for a view that was never registered. Available before startup. */
    internal val registeredId: String? get() = _id

    internal fun setIdBase(idBase: String?) {
        this.idBase = idBase
    }

    internal fun <V> internalCount(reader: ModelReader<C, V>): Int = count(reader)

    internal fun <V> internalIsEmpty(reader: ModelReader<C, V>): Boolean = isEmpty(reader)

    internal fun <V> internalContains(value: ModelID<*>, reader: ModelReader<C, V>): Boolean =
        contains(value, reader)

}

/** The result of [ModelView.sorted]. */
internal class SortedModelView<T : Any, R : Comparable<R>, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val selector: (Model<T>) -> R,
    private val ascending: Boolean,
) : ModelView<T, C>(previous) {

    // Sorting does not change what the view contains, so membership questions go straight to the parent and a view
    // derived from this one can still be indexed -- without this view keeping an index of its own.
    override val isIndexable: Boolean get() = previous.isIndexable
    override fun containsId(id: Int): Boolean = previous.containsId(id)
    override fun <V> ensureIndex(reader: ModelReader<C, V>): Set<Int>? = previous.ensureIndex(reader)

    // Cardinality is a question about membership, so it goes to the parent rather than through memberIds, which would
    // read and sort every model just to count it.
    override fun <V> count(reader: ModelReader<C, V>): Int = previous.internalCount(reader)
    override fun <V> isEmpty(reader: ModelReader<C, V>): Boolean = previous.internalIsEmpty(reader)

    // Unlike the other views this must read every model, since only the model itself answers where it sorts.
    override fun <V> memberIds(reader: ModelReader<C, V>): Sequence<ModelID<T>> {
        val models = previous.withReader(reader)
        return (if (ascending) models.sortedBy(selector) else models.sortedByDescending(selector)).map { it.id }
    }

}

/** The result of [ModelView.filterStates]. */
internal class IncludeStatesModelView<T : Any, C : KlerkContext>(
    private val previous: ModelView<T, C>,
    private val included: Set<Enum<*>>?,
    private val excluded: Set<Enum<*>>?,
) : ModelView<T, C>(previous) {

    private val includedNames = included?.map { it.name }?.toSet()
    private val excludedNames = excluded?.map { it.name }?.toSet()

    override val narrowsByPredicate: Boolean get() = true

    override val filteredStates: Set<Enum<*>> get() = (included ?: emptySet()) + (excluded ?: emptySet())

    override fun matches(model: Model<T>): Boolean =
        (includedNames == null || model.state in includedNames) &&
                (excludedNames == null || model.state !in excludedNames)

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
    private val all: List<Int>,  // sorted by createdAt
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

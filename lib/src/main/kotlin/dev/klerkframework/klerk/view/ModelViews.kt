package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.logger

/**
 * Base class for the set of [ModelView]s belonging to one managed model type `T`. Subclass it, add
 * `val`s built from [all] via `filter`/`sorted`/`filterStates`/`register` (or, for views that don't fit a property
 * initializer, override [initialize]). See docs/views.md.
 */
public open class ModelViews<T : Any, C : KlerkContext> {
    /** Called after a model of type `T` was created. Override to react to it; default is a no-op. */
    public open fun didCreate(created: Model<T>) {}

    /** Called after a model of type `T` was updated (props and/or state). Override to react to it; default is a no-op. */
    public open fun didUpdate(before: Model<T>, after: Model<T>) {}

    /** Called after a model of type `T` was deleted. Override to react to it; default is a no-op. */
    public open fun didDelete(deleted: Model<T>) {}

    internal val _all: MutableList<Int> = mutableListOf()

    // Kept alongside _all purely so that "is this id one of mine" is a set lookup: it is asked once per derived view
    // per write, and _all is a list.
    private val allIds: MutableSet<Int> = HashSet()

    /**
     * Set once Klerk has started. Views derived after that point are not attached to the view tree, so they behave as
     * they always did: evaluated on every query, and not retained.
     */
    @Volatile
    internal var isFrozen: Boolean = false
        private set

    internal fun freeze() {
        isFrozen = true
    }

    internal fun containsId(id: Int): Boolean = allIds.contains(id)

    internal fun allIdSet(): Set<Int> = allIds

    /**
     * Called once all managed models' [ModelViews] instances exist. Override to build views that need a reference to
     * another model type's views and therefore can't be wired up in a property initializer (construction order across
     * model types is unspecified).
     */
    public open fun initialize(): Unit = Unit

    /**
     * A view of all models of this type (i.e. all models of type T).
     */
    public val all: ModelView<T, C> = AllModelView(this, _all)

    private val modelViews = mutableListOf<ModelView<T, C>>(all)

    internal fun internalDidCreate(created: Model<T>) {
        logger.debug { "internalDidCreate ${created.id}" }
        require(allIds.add(created.id.value)) { "${created.id} is already in the view" }
        _all.add(created.id.value)
        all.onModelCreated(created)
        didCreate(created)
    }

    internal fun internalDidUpdate(before: Model<T>, after: Model<T>) {
        all.onModelUpdated(before, after)
        didUpdate(before, after)
    }

    internal fun internalDidDelete(deleted: Model<T>) {
        allIds.remove(deleted.id.value)
        _all.remove(deleted.id.value)
        all.onModelDeleted(deleted)
        didDelete(deleted)
    }

    /**
     * Empties everything the previous run left behind. Klerk can be started more than once against the same views (a
     * test that restarts against the same storage, for instance), and the startup load appends to [_all].
     */
    internal fun prepareForLoad() {
        _all.clear()
        allIds.clear()
        all.clearIndex()
    }

    /**
     * Rebuilds the id set after the startup load, which fills [_all] directly.
     */
    internal fun indexLoadedModels() {
        allIds.clear()
        allIds.addAll(_all)
    }

    internal fun register(modelView: ModelView<T, C>) {
        modelViews.add(modelView)
    }

    /** All views of this model type that were [ModelView.register]ed, including [all]. */
    public val views: List<ModelView<T, C>> get() = modelViews

}

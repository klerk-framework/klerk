package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.logger

/**
 * Base class for the collection of [ModelView]s belonging to one managed model type `T`. Subclass it, add
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

    /**
     * Called once all managed models' [ModelViews] instances exist. Override to build views that need a reference to
     * another model type's views and therefore can't be wired up in a property initializer (construction order across
     * model types is unspecified).
     */
    public open fun initialize(): Unit = Unit

    /**
     * A collection of all models in this view (i.e. all models of type T).
     */
    public val all: AllModelView<T, C> = AllModelView(this, _all)

    private val modelViews = mutableListOf<ModelView<T, C>>(all)

    internal fun internalDidCreate(created: Model<T>) {
        logger.debug { "internalDidCreate ${created.id} ${all} " }
        require(!_all.contains(created.id.value))
        _all.add(created.id.value)
        didCreate(created)
    }

    internal fun internalDidUpdate(before: Model<T>, after: Model<T>) {
        didUpdate(before, after)
    }

    internal fun internalDidDelete(deleted: Model<T>) {
        _all.remove(deleted.id.value)
        didDelete(deleted)
    }

    internal fun register(modelView: ModelView<T, C>) {
        modelViews.add(modelView)
    }

    /** All views of this model type that were [ModelView.register]ed, including [all]. */
    public fun getCollections(): List<ModelView<T, C>> = modelViews

}

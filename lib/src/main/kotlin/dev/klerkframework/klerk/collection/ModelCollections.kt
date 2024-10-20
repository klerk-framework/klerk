package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.logger

public open class ModelCollections<T : Any, C : KlerkContext> {
    public open fun didCreate(created: Model<T>) {}
    public open fun didUpdate(before: Model<T>, after: Model<T>) {}
    public open fun didDelete(deleted: Model<T>) {}

    internal val _all: MutableList<Int> = mutableListOf()

    public open fun initialize(): Unit = Unit

    /**
     * A collection of all models in this view (i.e. all models of type T).
     */
    public val all: AllModelCollection<T, C> = AllModelCollection(this, _all)

    private val modelCollections = mutableListOf<ModelCollection<T, C>>(all)

    internal fun internalDidCreate(created: Model<T>) {
        logger.debug { "internalDidCreate ${created.id} ${all} " }
        require(!_all.contains(created.id.toInt()))
        _all.add(created.id.toInt())
        didCreate(created)
    }

    internal fun internalDidUpdate(before: Model<T>, after: Model<T>) {
        didUpdate(before, after)
    }

    internal fun internalDidDelete(deleted: Model<T>) {
        _all.remove(deleted.id.toInt())
        didDelete(deleted)
    }

    internal fun register(modelCollection: ModelCollection<T, C>) {
        modelCollections.add(modelCollection)
    }

    public fun getCollections(): List<ModelCollection<T, C>> = modelCollections

}

package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.ModelID
import kotlin.reflect.KClass

/** An event emitted by [dev.klerkframework.klerk.KlerkModelChanges.subscribe] describing how a model changed. */
public sealed class ModelModification {
    /** The model that changed. */
    public abstract val id: ModelID<out Any>

    /** The class of the model's props, so a subscriber can filter without reading the model. */
    public abstract val modelClass: KClass<out Any>

    public data class Created(override val id: ModelID<out Any>, override val modelClass: KClass<out Any>) :
        ModelModification()

    public data class PropsUpdated(override val id: ModelID<out Any>, override val modelClass: KClass<out Any>) :
        ModelModification()

    public data class Transitioned(override val id: ModelID<out Any>, override val modelClass: KClass<out Any>) :
        ModelModification()

    public data class Deleted(override val id: ModelID<out Any>, override val modelClass: KClass<out Any>) :
        ModelModification()
}

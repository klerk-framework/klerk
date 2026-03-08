package dev.klerkframework.klerk

public interface ActorIdentity {
    public companion object {
        public const val systemType: Int = 1
        public const val authentication: Int = 2
        public const val modelType: Int = 3
        public const val modelReferenceType: Int = 4
        public const val unauthenticatedType: Int = 5
        public const val customType: Int = 6
        public const val plugin: Int = 7
    }

    public val type: Int
    public val id: ModelID<*>?
    public val externalId: Long?
}

public object SystemIdentity : ActorIdentity {
    override val type: Int = ActorIdentity.Companion.systemType
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system]"
}

public object AuthenticationIdentity : ActorIdentity {
    override val type: Int = ActorIdentity.authentication
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system authentication]"
}

public class ModelIdentity<T : Any>(public val model: Model<T>) :
    ActorIdentity {
    override val type: Int = ActorIdentity.modelType
    override val id: ModelID<T> = model.id
    override val externalId: Long? = null
    override fun toString(): String = "modelId: ${model.id} (${model})"
}

public class ModelReferenceIdentity<T : Any>(private val modelId: ModelID<T>) :
    ActorIdentity {
    override val type: Int = ActorIdentity.modelReferenceType
    override val id: ModelID<T> = modelId
    override val externalId: Long? = null
    override fun toString(): String = "model id: $modelId"
}

public class CustomIdentity(
    override val type: Int = ActorIdentity.customType,
    override val id: ModelID<Any>?,
    override val externalId: Long?
) : ActorIdentity {
    override fun toString(): String = "[custom]"
}

public object Unauthenticated : ActorIdentity {
    override val type: Int = ActorIdentity.unauthenticatedType
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[unauthenticated]"
}

public class PluginIdentity(public val plugin: KlerkPlugin<*, *>) :
    ActorIdentity {
    override val type: Int = ActorIdentity.plugin
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "Plugin: ${plugin.name}"
}

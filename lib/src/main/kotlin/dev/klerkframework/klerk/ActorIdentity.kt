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
    public val id: dev.klerkframework.klerk.ModelID<*>?
    public val externalId: Long?
}

public object SystemIdentity : dev.klerkframework.klerk.ActorIdentity {
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.systemType
    override val id: dev.klerkframework.klerk.ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system]"
}

public object AuthenticationIdentity : dev.klerkframework.klerk.ActorIdentity {
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.authentication
    override val id: dev.klerkframework.klerk.ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system authentication]"
}

public class ModelIdentity<T : Any>(public val model: dev.klerkframework.klerk.Model<T>) :
    dev.klerkframework.klerk.ActorIdentity {
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.modelType
    override val id: dev.klerkframework.klerk.ModelID<T> = model.id
    override val externalId: Long? = null
    override fun toString(): String = "modelId: ${model.id} (${model})"
}

public class ModelReferenceIdentity<T : Any>(private val modelId: dev.klerkframework.klerk.ModelID<T>) :
    dev.klerkframework.klerk.ActorIdentity {
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.modelReferenceType
    override val id: dev.klerkframework.klerk.ModelID<T> = modelId
    override val externalId: Long? = null
    override fun toString(): String = "model id: $modelId"
}

public class CustomIdentity(
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.customType,
    override val id: dev.klerkframework.klerk.ModelID<Any>?,
    override val externalId: Long?
) : dev.klerkframework.klerk.ActorIdentity {
    override fun toString(): String = "[custom]"
}

public object Unauthenticated : dev.klerkframework.klerk.ActorIdentity {
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.unauthenticatedType
    override val id: dev.klerkframework.klerk.ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[unauthenticated]"
}

public class PluginIdentity(public val plugin: dev.klerkframework.klerk.KlerkPlugin<*, *>) :
    dev.klerkframework.klerk.ActorIdentity {
    override val type: Int = dev.klerkframework.klerk.ActorIdentity.Companion.plugin
    override val id: dev.klerkframework.klerk.ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "Plugin: ${plugin.name}"
}

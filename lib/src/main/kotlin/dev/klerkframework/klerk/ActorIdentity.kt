package dev.klerkframework.klerk

/**
 * Who is performing an operation (a read, a command, or a rule evaluation) — see [KlerkContext.actor]. Authorization
 * and business rules narrow on the concrete implementing type (e.g. `is Unauthenticated`, `is ModelIdentity`) rather
 * than on [type], which exists only for serialization/storage.
 *
 * The built-in identities: [SystemIdentity] (Klerk itself, e.g. a fired time-trigger), [AuthenticationIdentity]
 * (trusted identity for code that performs authentication itself), [ModelIdentity] (a specific actor model, already
 * loaded), [ModelReferenceIdentity] (same, by id only), [Unauthenticated] (no logged-in user), [CustomIdentity]
 * (escape hatch), [PluginIdentity] (a plugin acting on its own behalf).
 */
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

/** The framework acting on its own behalf, e.g. when a time-trigger fires or a job runs. See `systemContextProvider`. */
public object SystemIdentity : ActorIdentity {
    override val type: Int = ActorIdentity.Companion.systemType
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system]"
}

/** A trusted identity for code that performs authentication itself, e.g. checking a password before a session exists. */
public object AuthenticationIdentity : ActorIdentity {
    override val type: Int = ActorIdentity.authentication
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system authentication]"
}

/** The actor is a specific model instance (typically a user model) that has already been read into memory. */
public class ModelIdentity<T : Any>(public val model: Model<T>) :
    ActorIdentity {
    override val type: Int = ActorIdentity.modelType
    override val id: ModelID<T> = model.id
    override val externalId: Long? = null
    override fun toString(): String = "modelId: ${model.id} (${model})"
}

/** Like [ModelIdentity], but holds only the id — use when you know the actor's id without having read the model first. */
public class ModelReferenceIdentity<T : Any>(private val modelId: ModelID<T>) :
    ActorIdentity {
    override val type: Int = ActorIdentity.modelReferenceType
    override val id: ModelID<T> = modelId
    override val externalId: Long? = null
    override fun toString(): String = "model id: $modelId"
}

/** Escape hatch for actor identities that don't fit the other built-in cases. */
public class CustomIdentity(
    override val type: Int = ActorIdentity.customType,
    override val id: ModelID<Any>?,
    override val externalId: Long?
) : ActorIdentity {
    override fun toString(): String = "[custom]"
}

/** The request has no logged-in user. */
public object Unauthenticated : ActorIdentity {
    override val type: Int = ActorIdentity.unauthenticatedType
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[unauthenticated]"
}

/** Used by a [KlerkPlugin] acting on its own behalf, e.g. when its background work issues commands. */
public class PluginIdentity(public val plugin: KlerkPlugin<*, *>) :
    ActorIdentity {
    override val type: Int = ActorIdentity.plugin
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "Plugin: ${plugin.name}"
}

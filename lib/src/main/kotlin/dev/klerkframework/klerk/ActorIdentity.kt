package dev.klerkframework.klerk

/**
 * Which kind of [ActorIdentity] an actor is. [storedValue] is what goes into the event log and the job table; it never
 * changes for an existing constant.
 */
public enum class ActorType(public val storedValue: Int) {
    System(1),
    Authentication(2),
    Model(3),
    ModelReference(4),
    Unauthenticated(5),
    Custom(6),
    Plugin(7),
    ;

    public companion object {
        /** The type [storedValue] identifies, or [Custom] if no type has that value. */
        public fun fromStoredValue(value: Int): ActorType = entries.firstOrNull { it.storedValue == value } ?: Custom
    }
}

/**
 * Who is performing an operation (a read, a command, or a rule evaluation) — see [KlerkContext.actor]. Authorization
 * and business rules narrow on the concrete type (e.g. `is Unauthenticated`, `is ModelIdentity`) rather than on
 * [type], which exists for serialization and storage.
 *
 * The built-in identities: [SystemIdentity] (Klerk itself, e.g. a fired time-trigger), [AuthenticationIdentity]
 * (trusted identity for code that performs authentication itself), [ModelIdentity] (a specific actor model, already
 * loaded), [ModelReferenceIdentity] (same, by id only), [Unauthenticated] (no logged-in user), [CustomIdentity]
 * (escape hatch), [PluginIdentity] (a plugin acting on its own behalf).
 *
 * Identities are compared by value, and `==` is true only for the same kind of identity. [ModelIdentity] and
 * [ModelReferenceIdentity] are the same actor in two forms, so use [isSameAs] to decide whether two identities are the
 * same actor.
 */
public sealed interface ActorIdentity {
    /** The kind of identity, as stored. */
    public val type: ActorType

    /** The model that is the actor, if the identity refers to one. */
    public val id: ModelID<*>?

    /** An identifier of the actor outside Klerk, if the identity has one. */
    public val externalId: Long?

    /**
     * True if [other] is the same actor: the same [id] if either has one, otherwise the same [externalId] if either has
     * one. Of the identities with neither, a [PluginIdentity] is the same as one with the same plugin name, and
     * [SystemIdentity] and [AuthenticationIdentity] are the same as themselves. Any other actor without [id] or
     * [externalId], e.g. [Unauthenticated], is never the same as anyone.
     */
    public fun isSameAs(other: ActorIdentity): Boolean {
        if (id != null || other.id != null) {
            return id == other.id
        }
        if (externalId != null || other.externalId != null) {
            return externalId == other.externalId
        }
        if (this is PluginIdentity) {
            return other is PluginIdentity && pluginName == other.pluginName
        }
        return (this === SystemIdentity || this === AuthenticationIdentity) && this === other
    }
}

/**
 * The framework acting on its own behalf, e.g. when a time-trigger fires or a job runs. See `systemContextProvider`.
 */
public object SystemIdentity : ActorIdentity {
    override val type: ActorType = ActorType.System
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system]"
}

/**
 * A trusted identity for code that performs authentication itself, e.g. checking a password before a session exists.
 */
public object AuthenticationIdentity : ActorIdentity {
    override val type: ActorType = ActorType.Authentication
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[system authentication]"
}

/** The actor is a specific model instance (typically a user model) that has already been read into memory. */
public class ModelIdentity<T : Any>(public val model: Model<T>) : ActorIdentity {
    override val type: ActorType = ActorType.Model
    override val id: ModelID<T> = model.id
    override val externalId: Long? = null
    override fun toString(): String = "${model.props::class.simpleName} ${model.id}"
    override fun equals(other: Any?): Boolean = other is ModelIdentity<*> && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * Like [ModelIdentity], but holds only the id — use when you know the actor's id without having read the model first.
 */
public class ModelReferenceIdentity<T : Any>(private val modelId: ModelID<T>) : ActorIdentity {
    override val type: ActorType = ActorType.ModelReference
    override val id: ModelID<T> = modelId
    override val externalId: Long? = null
    override fun toString(): String = "modelId: $modelId"
    override fun equals(other: Any?): Boolean = other is ModelReferenceIdentity<*> && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** Escape hatch for actor identities that don't fit the other built-in cases. */
public class CustomIdentity(override val id: ModelID<Any>?, override val externalId: Long?) : ActorIdentity {
    override val type: ActorType = ActorType.Custom
    override fun toString(): String = "[custom] modelId: $id, externalId: $externalId"
    override fun equals(other: Any?): Boolean =
        other is CustomIdentity && other.id == id && other.externalId == externalId
    override fun hashCode(): Int = 31 * id.hashCode() + externalId.hashCode()
}

/** The request has no logged-in user. */
public object Unauthenticated : ActorIdentity {
    override val type: ActorType = ActorType.Unauthenticated
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "[unauthenticated]"
}

/**
 * Used by a [KlerkPlugin] acting on its own behalf, e.g. when its background work issues commands. Identified by
 * [pluginName], which is what is stored, e.g. as a job's owner.
 */
public class PluginIdentity internal constructor(public val pluginName: String) : ActorIdentity {
    public constructor(plugin: KlerkPlugin<*, *>) : this(plugin.name)

    override val type: ActorType = ActorType.Plugin
    override val id: ModelID<*>? = null
    override val externalId: Long? = null
    override fun toString(): String = "Plugin: $pluginName"
    override fun equals(other: Any?): Boolean = other is PluginIdentity && other.pluginName == pluginName
    override fun hashCode(): Int = pluginName.hashCode()
}

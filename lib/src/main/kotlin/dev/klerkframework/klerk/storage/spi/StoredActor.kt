package dev.klerkframework.klerk.storage.spi

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.ActorType
import dev.klerkframework.klerk.AuthenticationIdentity
import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.ModelReferenceIdentity
import dev.klerkframework.klerk.PluginIdentity
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Unauthenticated

/**
 * An [ActorIdentity] as storage keeps it: its [type] and whichever of [id], [externalId] and [pluginName] it has.
 */
public data class StoredActor(val type: ActorType, val id: Int?, val externalId: Long?, val pluginName: String?) {

    /** The actor, as far as storage remembers it. Only the id survives, so a loaded model comes back as a reference. */
    internal fun toIdentity(): ActorIdentity = when (type) {
        ActorType.System -> SystemIdentity
        ActorType.Unauthenticated -> Unauthenticated
        ActorType.Authentication -> AuthenticationIdentity
        ActorType.Plugin -> PluginIdentity(requireNotNull(pluginName) { "A stored plugin actor has no name" })
        else -> if (id != null) ModelReferenceIdentity(ModelID<Any>(id)) else CustomIdentity(null, externalId)
    }
}

internal fun ActorIdentity.toStored(): StoredActor =
    StoredActor(type, id?.value, externalId, (this as? PluginIdentity)?.pluginName)

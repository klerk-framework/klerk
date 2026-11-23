package dev.klerkframework.klerk.subscription

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.read.checkEventLogAuthorization
import dev.klerkframework.klerk.read.isAuthorized
import dev.klerkframework.klerk.view.ModelView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass

internal class SubscriptionsImpl<C : KlerkContext, V>(
    private val klerk: KlerkImpl<C, V>,
    internal val hub: ChangeHub,
) : KlerkSubscriptions<C, V> {

    override fun model(id: ModelID<out Any>, context: C, after: Long?): Flow<ModelChange> =
        hub.subscribe(after, ::MissedChanges) { record ->
            val delta = record.models.firstOrNull { it.id == id } ?: return@subscribe null
            if (readable(listOf(delta), context).isEmpty()) return@subscribe null
            val mark = record.sequenceNumber
            if (delta.after == null) ModelChange.Deleted(mark) else ModelChange.Changed(mark)
        }

    override fun models(ids: Set<ModelID<out Any>>, context: C, after: Long?): Flow<ModelsChange> {
        val wanted = ids.toSet()
        return hub.subscribe(after, ::MissedChanges) { record ->
            val deltas = record.models.filter { it.id in wanted }
            if (deltas.isEmpty()) return@subscribe null
            val (deleted, changed) = readable(deltas, context).partition { it.after == null }
            if (deleted.isEmpty() && changed.isEmpty()) return@subscribe null
            ModelsChange.Changed(
                record.sequenceNumber,
                changed.map { it.id }.toSet(),
                deleted.map { it.id }.toSet(),
            )
        }
    }

    override fun <T : Any> view(view: ModelView<T, C>, context: C, after: Long?): Flow<ViewChange<T>> {
        val modelViews = view.modelViews
        val kClass = requireNotNull(klerk.specification.managedModels.firstOrNull { it.views === modelViews }) {
            "$view does not belong to this application"
        }.kClass
        return hub.subscribe(after, ::MissedChanges) { record ->
            val members = record.models.filter {
                it.kClass == kClass && (view.mayContain(it.before) || view.mayContain(it.after))
            }
            if (members.isEmpty()) return@subscribe null
            val ids = readable(members, context).map {
                @Suppress("UNCHECKED_CAST")
                it.id as ModelID<T>
            }
            if (ids.isEmpty()) null else ViewChange.Changed(record.sequenceNumber, ids.toSet())
        }
    }

    override fun events(
        context: C,
        after: Long?,
        modelClass: KClass<out Any>?,
        events: Set<EventReference>?,
    ): Flow<EventChange> = flow {
        if (context.actor != SystemIdentity) {
            klerk.readWriteLock.withRead { checkEventLogAuthorization(klerk, context, ReaderWithoutAuth(klerk)) }
        }
        emitAll(
            hub.subscribe(after, ::MissedChanges) { record ->
                val entry = record.eventLogEntry ?: return@subscribe null
                if (modelClass != null && record.eventModelClass != modelClass) return@subscribe null
                if (events != null && entry.eventReference !in events) return@subscribe null
                EventChange.Committed(record.sequenceNumber, entry)
            },
        )
    }

    override fun job(id: JobID, context: C): Flow<JobInfo> = klerk.jobs.currentAndChanges(id, context)

    override fun jobs(context: C): Flow<JobInfo> = klerk.jobs.changes(null, context)

    private fun <T : Any> ModelView<T, C>.mayContain(model: Model<out Any>?): Boolean {
        if (model == null) return false
        @Suppress("UNCHECKED_CAST")
        return try {
            couldContain(model as Model<T>)
        } catch (e: Exception) {
            true
        }
    }

    /** The deltas whose model the actor may read before or after the change. */
    private suspend fun readable(deltas: List<ModelDelta>, context: C): List<ModelDelta> {
        if (context.actor == SystemIdentity) return deltas
        return klerk.readWriteLock.withRead {
            val reader = ReaderWithoutAuth(klerk)
            deltas.filter { delta ->
                listOfNotNull(delta.after, delta.before).any { model ->
                    try {
                        isAuthorized(model, context, klerk.specification, reader)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        }
    }
}

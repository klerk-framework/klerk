package dev.klerkframework.klerk.subscription

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.view.ModelView
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * Subscriptions to what happens in the application, as [dev.klerkframework.klerk.Klerk.subscriptions].
 *
 * The actor in the context is used for authorization when each change is delivered. A change of a model is delivered
 * if the actor may read the model as it was before the change or as it is after it, so a subscriber also learns that a
 * model it was showing has become unreadable.
 *
 * Every model, view and event change carries the sequence number of its commit, the same number as
 * [EventLogEntry.sequenceNumber]. Pass the sequence number of a read
 * ([dev.klerkframework.klerk.read.Reader.sequenceNumber]) as `after` to get exactly the changes the read did not see.
 * Without `after`, the subscription starts with the next commit.
 *
 * Klerk keeps the latest [dev.klerkframework.klerk.KlerkSettings.subscriptionHistory] commits for this. A subscriber
 * gets [MissedChanges] instead when `after` is older than that or from before a restart, or when it falls that far
 * behind. It should then read everything it shows again.
 *
 * The sequence number reveals how many commits the application has made. Do not show it to end users.
 *
 * Commands never wait for a subscriber, so a subscriber may issue commands itself.
 */
public interface KlerkSubscriptions<C : KlerkContext, V> {

    /** Changes of the model [id]. */
    public fun model(id: ModelID<out Any>, context: C, after: Long? = null): Flow<ModelChange>

    /**
     * Changes of the models [ids]: one [ModelsChange.Changed] per commit that changed or deleted any of them. Cheaper
     * than one [model] subscription per id.
     */
    public fun models(ids: Set<ModelID<out Any>>, context: C, after: Long? = null): Flow<ModelsChange>

    /**
     * Changes of [view]: one [ViewChange.Changed] per commit that changed a model that was in the view before the
     * commit or is in it after.
     *
     * For a view that is not built with `filter`, `filterStates` and `sorted` only, every change of a model of the
     * view's type counts.
     *
     * @throws IllegalArgumentException if [view] does not belong to this application
     */
    public fun <T : Any> view(view: ModelView<T, C>, context: C, after: Long? = null): Flow<ViewChange<T>>

    /**
     * Entries added to the event log. If [modelClass] is given, only events of that model type are included, and if
     * [events] is given, only those events. Entries are delivered as the event log keeps them, see
     * [dev.klerkframework.klerk.EventLogRetention].
     *
     * The flow throws [dev.klerkframework.klerk.AuthorizationException] when collected if the actor may not read the
     * event log.
     */
    public fun events(
        context: C,
        after: Long? = null,
        modelClass: KClass<out Any>? = null,
        events: Set<EventReference>? = null,
    ): Flow<EventChange>

    /**
     * The job [id]: first as it is now, then every time it changes.
     *
     * The flow throws [NoSuchElementException] when collected if there is no such job, and
     * [dev.klerkframework.klerk.AuthorizationException] if the actor may not see it.
     */
    public fun job(id: JobID, context: C): Flow<JobInfo>

    /** Every change of a job the actor may see. */
    public fun jobs(context: C): Flow<JobInfo>
}

/** A change delivered by [KlerkSubscriptions.model]. */
public sealed interface ModelChange {
    /** The sequence number of the commit. */
    public val sequenceNumber: Long

    /** The model was created, or its properties or state changed. */
    public data class Changed(override val sequenceNumber: Long) : ModelChange

    /** The model was deleted. */
    public data class Deleted(override val sequenceNumber: Long) : ModelChange
}

/** A change delivered by [KlerkSubscriptions.models]. */
public sealed interface ModelsChange {
    /** The sequence number of the commit. */
    public val sequenceNumber: Long

    /** A commit created or changed the models [changed] and deleted the models [deleted]. */
    public data class Changed(
        override val sequenceNumber: Long,
        val changed: Set<ModelID<out Any>>,
        val deleted: Set<ModelID<out Any>>,
    ) : ModelsChange
}

/** A change delivered by [KlerkSubscriptions.view]. */
public sealed interface ViewChange<out T : Any> {
    /** The sequence number of the commit. */
    public val sequenceNumber: Long

    /** A commit changed the models [ids] that were or are in the view. */
    public data class Changed<T : Any>(override val sequenceNumber: Long, val ids: Set<ModelID<T>>) : ViewChange<T>
}

/** A change delivered by [KlerkSubscriptions.events]. */
public sealed interface EventChange {
    /** The sequence number of the commit. */
    public val sequenceNumber: Long

    /** An event was committed. */
    public data class Committed(override val sequenceNumber: Long, val entry: EventLogEntry) : EventChange
}

/**
 * Changes were missed, see [KlerkSubscriptions]. Read everything that is shown again; the subscription goes on with the
 * changes after [sequenceNumber].
 */
public data class MissedChanges(override val sequenceNumber: Long) :
    ModelChange,
    ModelsChange,
    ViewChange<Nothing>,
    EventChange

package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.reflect.KProperty1

/**
 * Read-only access to models, available both inside a [dev.klerkframework.klerk.Klerk.read] (or `readSuspend`) block
 * and inside the functions Klerk calls itself — rules, validations and state machine executables, where it is
 * `args.reader`.
 *
 * Reading a *view* is done on the view — `view.count()`, `view.asSequence().toList()`, `view.query(...)` and friends, in
 * `dev.klerkframework.klerk.view`. Those take this reader as a context parameter, so inside a read block, or a
 * `with(args.reader) { }` block in a DSL function, you never write it out. See docs/reading.md.
 *
 * A read block gets the larger [Reader], which can additionally answer which events are possible right now.
 */
public interface ModelReader<C : KlerkContext, V> {

    /**
     * This is the collection of views that was provided to Klerk when you created the configuration.
     */
    public val views: V

    /**
     * Job state, as part of this block's snapshot. This is how jobs are read inside a read block —
     * `klerk.jobs.get(...)` takes the read lock itself and refuses to run inside one.
     */
    public val jobs: JobReader

    /**
     * Attached-data metadata, as part of this block's snapshot. This is how it is read inside a read block —
     * `klerk.attachedData.getMetadata(...)` takes the read lock itself and refuses to run inside one.
     *
     * Only metadata: the *value* is read after the block with `klerk.attachedData.get(...)`, since holding the read
     * lock while streaming it would block every command in the application.
     */
    public val attachedData: AttachedDataReader

    /**
     * A snapshot of the event log as of this read block. Nothing is read from storage here — call
     * [EventLogQuery.get] once the read block has ended, so that the database is never queried while the read lock
     * is held. The returned entries are limited to commands that were visible in this block.
     *
     * @param id if given, only entries for that model. If null, entries for all models.
     * @param after only entries whose [dev.klerkframework.klerk.storage.EventLogEntry.time] is at or after this
     * @param before only entries whose [dev.klerkframework.klerk.storage.EventLogEntry.time] is at or before this
     * @throws AuthorizationException if the actor is not allowed to read the event log
     */
    public fun eventLog(
        id: ModelID<out Any>? = null,
        after: Instant = Instant.DISTANT_PAST,
        before: Instant = Instant.DISTANT_FUTURE,
    ): EventLogQuery

    /**
     * A single event-log entry, as of this read block. Like [eventLog], nothing is read from storage here: call
     * [EventLogEntryQuery.get] once the read block has ended. Use it for a permalink to one entry.
     *
     * @param sequenceNumber the [dev.klerkframework.klerk.storage.EventLogEntry.sequenceNumber] to look up
     * @throws AuthorizationException if the actor is not allowed to read the event log
     */
    public fun eventLogEntry(sequenceNumber: Long): EventLogEntryQuery

    /**
     * The model with [id].
     *
     * @throws kotlin.NoSuchElementException if there is no such model
     * @throws AuthorizationException if the actor is not allowed to read it
     */
    public fun <T : Any> get(id: ModelID<T>): Model<T>

    /**
     * Like [get], but null if there is no such model, or the actor is not allowed to read it. The two cases are
     * indistinguishable.
     */
    public fun <T : Any> getOrNull(id: ModelID<T>): Model<T>?

    // Reading a view is done on the view itself: `view.count()`, `view.asSequence().toList()`, `view.query(...)` and friends,
    // in collection/ViewOperations.kt. They take this reader as a context parameter, so inside a read block you do
    // not write it out.

    /**
     * Finds the IDs of all models that reference [id] through any relation property (regardless of model type).
     */
    public fun referencingIds(id: ModelID<*>): Set<ModelID<*>>

    /**
     * Finds all models of type [clazz] that reference [id] through any relation property.
     */
    public fun <T : Any> referencing(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>>

    /**
     * Finds all models whose [property] equals [id].
     */
    public fun <T : Any, U : Any> referencing(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>,
    ): Set<Model<T>>

    /**
     * Finds all models whose [property] (a collection of IDs) contains [id].
     */
    public fun <T : Any, U : Any> referencingInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>>

}

/**
 * What a [dev.klerkframework.klerk.Klerk.read] (or `readSuspend`) block gets: everything a [ModelReader] can do, plus
 * the events that could be submitted right now.
 */
public interface Reader<C : KlerkContext, V> : ModelReader<C, V> {

    /**
     * Returns the void events (i.e. events that create a new model of type [clazz]) that the actor could
     * successfully submit right now: authorization and validation rules are both evaluated, but nothing is executed.
     */
    public fun <T : Any> getPossibleVoidEvents(
        clazz: KClass<T>,
        visibility: EventVisibility = EventVisibility.Application
    ): Set<EventReference>

    /**
     * Returns the instance events that the actor could successfully submit right now against the model with [id],
     * given its current state: authorization and validation rules are both evaluated, but nothing is executed.
     *
     * @throws AuthorizationException if the actor is not allowed to read the model itself
     */
    public fun <T : Any> getPossibleEvents(
        id: ModelID<T>,
        visibility: EventVisibility = EventVisibility.Application
    ): Set<EventReference>

}

internal sealed class ReadResult<T : Any> {
    data class Fail<T : Any>(val problem: Problem) : ReadResult<T>()
    data class Ok<T : Any>(val model: Model<T>) : ReadResult<T>()

    fun getOrNull(): Model<T>? {
        return when (this) {
            is Fail -> null
            is Ok -> this.model
        }
    }

    fun getOrThrow(): Model<T> {
        when (this) {
            is Fail -> throw this.problem.asException()
            is Ok -> return this.model
        }
    }
}

internal sealed class ReadListResult<T : Any> {
    data class Fail<T : Any>(val problem: Problem) : ReadListResult<T>()
    data class Ok<T : Any>(val models: List<Model<T>>) : ReadListResult<T>()

    fun getOrEmpty(): List<Model<T>> {
        return when (this) {
            is Fail -> emptyList()
            is Ok -> this.models
        }
    }

    fun getOrThrow(): List<Model<T>> {
        when (this) {
            is Fail -> throw this.problem.asException()
            is Ok -> return this.models
        }
    }
}


internal fun <C : KlerkContext, V> isReadPropertyAuthorized(
    args: PropertyReadRuleArgs<C, V>,
    specification: Specification<C, V>
): Boolean {
    if (specification.authorization.readPropertyPositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
        return false
    }
    if (specification.authorization.readPropertyNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
        return false
    }
    return true
}

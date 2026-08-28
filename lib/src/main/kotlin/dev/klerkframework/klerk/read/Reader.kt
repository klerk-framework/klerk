package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.reflect.KProperty1

/**
 * Read-only access to models and views, always used as the receiver inside a [dev.klerkframework.klerk.Klerk.read]
 * (or `readSuspend`) block.
 *
 * The `getIfAuthorizedOrNull`, `listIfAuthorized`, `getPossibleVoidEvents`, and `getPossibleEvents` functions are
 * only meaningful when authorization is enforced (i.e. inside a `Klerk.read` block). If called from within a state
 * machine's executable functions (create/update/validation blocks etc.), where the reader in scope does not enforce
 * authorization, they throw `RuntimeException` — use `get`/`list`/`filter` there instead.
 */
public interface Reader<C : KlerkContext, V> {

    /**
     * This is the collection of views that was provided to Klerk when you created the configuration.
     */
    public val views: V

    /**
     * Job state, as part of this block's snapshot. This is how jobs are read inside a read block —
     * `klerk.jobs.getJob(...)` takes the read lock itself and refuses to run inside one.
     */
    public val jobs: JobReader

    /**
     * A snapshot of the event log as of this read block. Nothing is read from storage here — call
     * [EventLogQuery.get] once the read block has ended, so that the database is never queried while the read lock
     * is held. The returned entries are limited to commands that were visible in this block.
     *
     * @param id if given, only entries for that model. If null, entries for all models.
     * @param after only entries whose [dev.klerkframework.klerk.storage.EventLogEntry.time] is at or after this
     * @param before only entries whose [dev.klerkframework.klerk.storage.EventLogEntry.time] is at or before this
     * @param sequenceNumber if given, only the entry with exactly this
     * [dev.klerkframework.klerk.storage.EventLogEntry.sequenceNumber]. Use it to look up a single entry, e.g. for a
     * permalink.
     * @throws AuthorizationException if the actor is not allowed to read the event log
     */
    public fun eventLog(
        id: ModelID<out Any>? = null,
        after: Instant = Instant.DISTANT_PAST,
        before: Instant = Instant.DISTANT_FUTURE,
        sequenceNumber: Long? = null,
    ): EventLogQuery

    /**
     * @throws AuthorizationException if the model is not found or the actor is not allowed to read it.
     */
    public fun <T : Any> get(id: ModelID<T>): Model<T>

    /**
     * Like [get], but returns null instead of throwing if the model doesn't exist or isn't authorized.
     */
    public fun <T : Any> getOrNull(id: ModelID<T>): Model<T>?

    /**
     * @throws NoSuchElementException if no model in [collection] matches [filter]
     * @throws AuthorizationException if the actor is not allowed to read the matching model
     */
    public fun <T : Any> getFirstWhere(
        collection: ModelView<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T>

    /**
     * Like [getFirstWhere], but returns null instead of throwing if no model matches [filter].
     *
     * @throws AuthorizationException if the actor is not allowed to read the matching model
     */
    public fun <T : Any> firstOrNull(
        collection: ModelView<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T>?

    /**
     * Like [get], but returns null instead of throwing an [AuthorizationException] when the actor isn't allowed to
     * read the model (a missing model still yields null, same as an unauthorized one — the two cases are
     * indistinguishable). Only usable where authorization is enforced; see the interface-level doc.
     */
    public fun <T : Any> getIfAuthorizedOrNull(id: ModelID<T>): Model<T>?

    /**
     * Lists all models in [collection], silently dropping any the actor is not authorized to read (as opposed to
     * [list], which throws). Only usable where authorization is enforced; see the interface-level doc.
     */
    public fun <T : Any> listIfAuthorized(collection: ModelView<T, C>): List<Model<T>>

    /**
     * @throws AuthorizationException if there is any model in [modelView] (after [filter] is applied) that the actor
     * is not allowed to read.
     */
    public fun <T : Any> list(
        modelView: ModelView<T, C>,
        filter: ((Model<T>) -> Boolean)? = null,
    ): List<Model<T>>

    /**
     * Cursor-paginated variant of [list]. See [QueryOptions] for paging/cursor parameters and [QueryResponse] for
     * the returned page metadata.
     *
     * @throws AuthorizationException if there is any matching model the actor is not allowed to read.
     */
    public fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions? = null,
        filter: ((Model<T>) -> Boolean)? = null
    ): QueryResponse<T>

    /**
     * Like [query], but silently drops the models the actor is not authorized to read instead of throwing (the same
     * relation [listIfAuthorized] has to [list]). Only usable where authorization is enforced; see the
     * interface-level doc.
     *
     * The page's cursor metadata still comes from the full result, so a page may contain fewer than
     * [QueryOptions.maxItems] items even when later pages hold more.
     */
    public fun <T : Any> queryIfAuthorized(
        collection: ModelView<T, C>,
        options: QueryOptions? = null,
        filter: ((Model<T>) -> Boolean)? = null
    ): QueryResponse<T>

    /**
     * Finds the IDs of all models that reference [id] through any relation property (regardless of model type).
     */
    public fun getAllRelatedIds(id: ModelID<*>): Set<ModelID<*>>

    /**
     * Finds all models of type [clazz] that reference [id] through any relation property.
     */
    public fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>>

    /**
     * Finds all models whose [property] equals [id].
     */
    public fun <T : Any, U : Any> getRelated(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>,

        ): Set<Model<T>>

    /**
     * Finds all models whose [property] (a collection of IDs) contains [id].
     */
    public fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>>

    /**
     * Returns the void events (i.e. events that create a new model of type [clazz]) that the actor could
     * successfully submit right now: authorization and validation rules are both evaluated, but nothing is executed.
     *
     * Only usable where authorization is enforced; see the interface-level doc.
     */
    public fun <T : Any> getPossibleVoidEvents(
        clazz: KClass<T>,
        visibility: EventVisibility = EventVisibility.CODE
    ): Set<EventReference>


    /**
     * Returns the instance events that the actor could successfully submit right now against the model with [id],
     * given its current state: authorization and validation rules are both evaluated, but nothing is executed.
     *
     * Only usable where authorization is enforced; see the interface-level doc.
     *
     * @throws AuthorizationException if the actor is not allowed to read the model itself
     */
    public fun <T : Any> getPossibleEvents(
        id: ModelID<T>,
        visibility: EventVisibility = EventVisibility.CODE
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
    args: ArgsForPropertyAuth<C, V>,
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

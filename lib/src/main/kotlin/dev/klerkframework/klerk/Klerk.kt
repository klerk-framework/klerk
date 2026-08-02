package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.JobMetadata
import dev.klerkframework.klerk.job.RunnableJob
import dev.klerkframework.klerk.log.KlerkLog
import dev.klerkframework.klerk.read.ModelModification
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.AuditEntry
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import kotlin.time.Instant

public interface Klerk<C : KlerkContext, V> {

    public companion object {

        /**
         * Creates an instance of Klerk.
         *
         * @param config can be created with [ConfigBuilder]
         */
        public fun <C : KlerkContext, V> create(
            config: Config<C, V>,
            settings: KlerkSettings = KlerkSettings()
        ): Klerk<C, V> {
            return KlerkImpl(config, settings)
        }
    }

    public val config: Config<C, V>
    public val events: EventsManager<C, V>
    public val jobs: JobManager<C, V>
    public val models: KlerkModels<C, V>

    /**
     * Large immutable data (blobs and strings) attached to models.
     */
    public val largeData: KlerkLargeData<C>
    public val meta: KlerkMeta
    public val log: KlerkLog

    /**
     * Submits a single event for processing.
     *
     * @param command the event
     * @param context including the actorIdentity on whose behalf the read happens
     * @return either a Success or a Failure describing the processing result
     */
    public suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions
    ): CommandResult<T, C, V>

    /**
     * Acquires a read lock, runs [readFunction] with a [Reader] receiver, and returns its result.
     *
     * The function suspends until the read lock has been acquired. No event is processed while [readFunction]
     * is executed.
     *
     * @param context including the actorIdentity on whose behalf the read happens. This actor can be overridden inside
     * readFunction (see [Reader]).
     * @param readFunction a function literal with a Reader receiver
     * @return whatever the readFunction returns
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> read(context: C, readFunction: Reader<C, V>.() -> T): T

    /**
     * Like [read], but the context is produced by [contextProvider] instead of being passed directly. Useful when
     * obtaining the context has its own suspending cost (e.g. resolving an actor from a token) and should only be
     * paid once the read lock is about to be acquired.
     *
     * @param contextProvider a function that provides the context for the read operation. This actor can be overridden inside
     * readFunction (see [Reader]).
     * @param readFunction a function literal with a Reader receiver
     * @return whatever the readFunction returns
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> read(contextProvider: suspend (Klerk<C, V>) -> C, readFunction: Reader<C, V>.() -> T): T

    /**
     * Like [read], but [readFunction] is itself `suspend`, so it may perform other suspending work (e.g. network
     * calls) while the read lock is held.
     *
     * Doing so blocks every other command and read in the system for the duration, so use with care. In particular,
     * never submit a command from within [readFunction] — that deadlocks the application. Prefer [read] unless you
     * specifically need to suspend inside the read.
     *
     * @param context including the actorIdentity on whose behalf the read happens. This actor can be overridden inside
     * readFunction (see [Reader]).
     * @param readFunction a function literal with a Reader receiver
     * @return whatever the readFunction returns
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, V>.() -> T): T

    /**
     * Combines [readSuspend] and the contextProvider variant of [read]: the context comes from [contextProvider],
     * and [readFunction] may itself suspend while the read lock is held. See both for the caveats that apply.
     *
     * @param contextProvider a function that provides the context for the read operation. This actor can be overridden inside
     * readFunction (see [Reader]).
     * @param readFunction a function literal with a Reader receiver
     * @return whatever the readFunction returns
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> readSuspend(
        contextProvider: suspend (Klerk<C, V>) -> C,
        readFunction: suspend Reader<C, V>.() -> T
    ): T

}

public interface EventsManager<C : KlerkContext, V> {

    /**
     * Queries the audit log of previously processed commands.
     *
     * @param context the actor must satisfy the configured event-log authorization rules, or this throws
     * @param id if provided, restricts the result to entries for this model. If null, entries for all models are returned.
     * @param after only entries at or after this instant are returned
     * @param before only entries at or before this instant are returned
     * @return the matching audit entries
     * @throws AuthorizationException if the actor is not allowed to read the audit log
     */
    public suspend fun getEventsInAuditLog(
        context: C,
        id: ModelID<Any>? = null,
        after: Instant = Instant.DISTANT_PAST,
        before: Instant = Instant.DISTANT_FUTURE
    ): Iterable<AuditEntry>


}

public interface KlerkModels<C : KlerkContext, V> {

    /**
     * Subscribes to model changes.
     *
     * If a model is changed but the actor is not authorized to read it, the model will be ignored.
     *
     * @param context containing the actor that will be used for authorization
     * @param id if provided, subscribes only to changes of the referenced model. If null, subscribes to all models.
     */
    public fun subscribe(context: C, id: ModelID<out Any>?): Flow<ModelModification>

    /**
     * Creates a model without using a state machine.
     * This is an 'escape hatch', and should be used only as a last resort.
     * No validation and no authorization rules will be applied.
     *
     * The setting allowUnsafeOperations must be enabled in order to use this.
     */
    public suspend fun <T : Any> unsafeCreate(context: C, model: Model<T>)

    /**
     * Updates a model without using a state machine.
     * This is an 'escape hatch', and should be used only as a last resort.
     * No validation and no authorization rules will be applied.
     *
     * The setting allowUnsafeOperations must be enabled in order to use this.
     */
    public suspend fun <T : Any> unsafeUpdate(context: C, model: Model<T>)

    /**
     * Deletes a model without using a state machine.
     * This is an 'escape hatch', and should be used only as a last resort.
     * No validation and no authorization rules will be applied.
     *
     * The setting allowUnsafeOperations must be enabled in order to use this.
     */
    public suspend fun <T : Any> unsafeDelete(context: C, id: ModelID<T>)

}

public interface JobManager<C : KlerkContext, V> {

    /**
     * Schedules a job for background execution. This is for manually-created [RunnableJob]s; jobs created by a
     * state machine's [dev.klerkframework.klerk.statemachine.UnmanagedJob] executable are scheduled
     * automatically.
     *
     * @return an ID that can be used with [getJob] to check on progress/result
     */
    public fun schedule(job: RunnableJob<C, V>): JobId

    /**
     * @throws kotlin.NoSuchElementException if no job with this id exists
     */
    public fun getJob(id: JobId): JobMetadata

    public fun getAllJobs(): List<JobMetadata>

}

internal interface JobManagerInternal<C : KlerkContext, V> : JobManager<C, V> {

    /**
     * Makes the JobManager aware of a new job. It is assumed that the job has already been persisted to the database.
     */
    fun notifyJobWasAddedToDb(job: RunnableJob<C, V>)


    fun isJobIdAvailable(int: Int): Boolean
}

/**
 * Large immutable data attached to models.
 *
 * Models should be kept small so they fit in the internal cache. Instead of storing a large value in the model
 * itself, prepare it here and store the returned ID in a model property (of type [LargeBlobID] or [LargeStringID]).
 *
 * Writing happens in two steps since uploading may be slow but updating a model must be quick:
 * 1. [prepare] inserts the data (slow, no lock is held)
 * 2. a command stores the returned ID in a model property (fast)
 *
 * If no committed command references a prepared ID within one minute, the data is deleted and a later attempt to use
 * that ID will fail the command.
 *
 * Attached data is exclusively owned: an ID belongs to the first model that references it in a committed command, and
 * a command trying to attach data owned by another model is rejected. The data is deleted when no property of the
 * owning model refers to it any more (i.e. on replacement, on set-to-null, and on model deletion), in the same
 * transaction as the command.
 *
 * There is no way to delete attached data directly, and there is no way to store a standalone value.
 */
public interface KlerkLargeData<C : KlerkContext> {

    /**
     * Inserts a blob so that it can be attached to a model.
     *
     * This may take a while, so it is deliberately done outside the command processing. No lock is held while the data
     * is written.
     *
     * @param visibility whether the data may be read by anyone or only by the actors the `readLargeData` rules allow.
     * Chosen here and never changed, so that a [LargeDataVisibility.Public] value can be cached by e.g. a CDN.
     * @param metadata anything the application wants to store alongside the data, such as a content type. It is handed
     * back by [getMetadata] and is *not* given to the authorization rules. Must not exceed 1000 characters when
     * JSON-encoded, since it is kept in memory for the lifetime of the data.
     * @return the ID to be stored in a model property by a subsequent command. If no command does so within one
     * minute, the data is deleted.
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalArgumentException if the metadata is too large
     */
    public suspend fun prepare(
        value: InputStream,
        context: C,
        visibility: LargeDataVisibility = LargeDataVisibility.Private,
        metadata: Map<String, String> = emptyMap(),
    ): LargeBlobID

    /**
     * Inserts a string so that it can be attached to a model.
     *
     * See [prepare] for blobs; the semantics are identical.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalArgumentException if the metadata is too large
     */
    public suspend fun prepare(
        value: String,
        context: C,
        visibility: LargeDataVisibility = LargeDataVisibility.Private,
        metadata: Map<String, String> = emptyMap(),
    ): LargeStringID

    /**
     * Retrieves a blob.
     *
     * Must be called *outside* a read block: attached data is often large, and holding the read lock while streaming
     * it would block every command and every read in the application. This function acquires the lock briefly on its
     * own to make the authorization decision, releases it, and then returns the stream.
     *
     * If the data is [LargeDataVisibility.Public], no authorization rule is evaluated at all.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id
     */
    public suspend fun get(id: LargeBlobID, context: C): InputStream

    /**
     * Retrieves a string.
     *
     * See [get] for blobs; the semantics are identical.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id
     */
    public suspend fun get(id: LargeStringID, context: C): String

    /**
     * Retrieves what is known about a blob apart from its value: visibility, creation time, size, content hash and
     * whatever metadata was provided to [prepare].
     *
     * Authorized exactly like [get]: public data is described to anyone, private data only to the actors the
     * `readLargeData` rules allow — a content hash reveals whether the data is a file the caller already has.
     *
     * This is the natural first call when serving attached data over HTTP: it provides the headers (content type from
     * the metadata, content length from the size, cache policy from the visibility) and lets a URL be stamped with the
     * hash, without touching the value.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, or if it has not yet been
     * attached to a model
     */
    public suspend fun getMetadata(id: LargeBlobID, context: C): LargeDataMetadata

    /**
     * Retrieves what is known about a string apart from its value.
     *
     * See [getMetadata] for blobs; the semantics are identical.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, or if it has not yet been
     * attached to a model
     */
    public suspend fun getMetadata(id: LargeStringID, context: C): LargeDataMetadata

}

public interface KlerkMeta {

    /**
     * Brings the framework to a state where it can process new events and jobs.
     * @param installShutdownHook if true, a shutdown hook will be added so that [dev.klerkframework.klerk.KlerkMeta.stop]
     * is called to reduce the possibility that background executions are terminated before completion.
     */
    public suspend fun start(installShutdownHook: Boolean = true)

    /**
     * Shuts down the framework in an ordered manner. It is recommended to stop clients (Ktor, gRPC etc.) first.
     */
    public fun stop()

    /**
     * The number of models currently in the system
     */
    public val modelsCount: Int
        get() = ModelCache.count

}

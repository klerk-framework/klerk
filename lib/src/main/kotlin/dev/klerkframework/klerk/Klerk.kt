package dev.klerkframework.klerk

import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.log.KlerkLog
import dev.klerkframework.klerk.read.ModelModification
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.AuditEntry
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.flow.Flow

import kotlinx.datetime.Instant
import java.io.InputStream
import kotlin.time.Duration

public interface Klerk<C : KlerkContext, D> {

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

    public val config: Config<C, D>
    public val events: EventsManager<C, D>
    public val jobs: JobManager<C, D>
    public val models: KlerkModels<C, D>

    /**
     * Note that the keys are generated for you, i.e. you cannot give your own names to the keys.
     */
    public val keyValueStore: KlerkKeyValueStore<C>
    public val meta: KlerkMeta
    public val log: KlerkLog

    /**
     * Submits a single event for processing.
     *
     * @param command the event
     * @param context including the actorIdentity on whose behalf the read happens
     * @param dryRun when true, no models will be updated and no effects will be triggered
     * @param following if provided, fails if there has been another event after the instant
     * @return either a Success or a Failure describing the processing result
     */
    public suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions
    ): CommandResult<T, C, D>

    /**
     * Read stuff
     *
     * The function will suspend until a read lock has been acquired. No event will be processed while the readFunction
     * is executed.
     *
     * @param context including the actorIdentity on whose behalf the read happens. This actor can be overridden inside
     * readFunction (see [Reader]).
     * @param readFunction a function literal with a Reader receiver
     * @return whatever the readFunction returns
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> read(context: C, readFunction: Reader<C, D>.() -> T): T

    /**
     * Read stuff
     *
     * The function will suspend until a read lock has been acquired. No event will be processed while the readFunction
     * is executed.
     *
     * This function differs from the normal read function in that the readFunction can suspend, e.g. it is possible to
     * do network calls within the readFunction. This may cause performance issues for the rest of the system, so it
     * should be used with care. Also, don't try to submit an event while in the readFunction as this will cause a
     * deadlock bringing the application to a halt. If possible, use the normal read function instead of this.
     *
     * @param context including the actorIdentity on whose behalf the read happens. This actor can be overridden inside
     * readFunction (see [Reader]).
     * @param readFunction a function literal with a Reader receiver
     * @return whatever the readFunction returns
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, D>.() -> T): T

}

public interface EventsManager<C : KlerkContext, V> {

    /**
     * Gets all possible events that are not tied to any specific model instance for a certain actor.
     *
     * @param actor the actor on whose behalf this operation occurs
     * @param clazz if provided, only void events for state machines that corresponds to that class is returned.
     * @param assumeWriteMode will return events even if the system is currently in read-only mode (e.g. during snapshot)
     * @return a list of events
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

    public fun schedule(job: RunnableJob<C, V>): JobId
    public fun getJob(id: JobId): JobMetadata
    public fun getAllJobs(): List<JobMetadata>
}

public interface KlerkKeyValueStore<C : KlerkContext> {

    /**
     * Put a String value in the key-value store.
     */
    public suspend fun put(value: String, ttl: Duration? = null) : StringKeyValueID

    /**
     * Put an Int value in the key-value store.
     * @throws AuthorizationException if the actor isn't authorized
     */
    public suspend fun put(value: Int, ttl: Duration? = null) : IntKeyValueID

    /**
     * The first step of putting a blob in the key-value store. This step inserts the blob in the database but
     * doesn't make it available in the key-value store. The reason for this extra step is that large blobs may
     * take a long time to upload and store in the database, and we don't want to block other operations during this
     * time. Therefore, this step happens without acquiring any lock.
     *
     * @throws AuthorizationException if the actor isn't authorized
     */
    public fun prepareBlob(value: InputStream) : BlobToken

    /**
     * Put a blob in the key-value store.
     * @throws AuthorizationException if the actor isn't authorized
     */
    public suspend fun put(token: BlobToken, ttl: Duration? = null) : BinaryKeyValueID

    /**
     * Retrieve a value from the key-value store.
     * @throws AuthorizationException if the actor isn't authorized
     * @throws kotlin.NoSuchElementException if there exists no value for the provided key
     */
    public suspend fun get(id: StringKeyValueID, context: C) : String

    /**
     * Retrieve a value from the key-value store.
     * @throws AuthorizationException if the actor isn't authorized
     * @throws kotlin.NoSuchElementException if there exists no value for the provided key
     */
    public suspend fun get(id: IntKeyValueID, context: C) : Int

    /**
     * Retrieve a value from the key-value store.
     * @throws AuthorizationException if the actor isn't authorized
     * @throws kotlin.NoSuchElementException if there exists no value for the provided key
     */
    public suspend fun get(id: BinaryKeyValueID, context: C) : InputStream

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

package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.log.ActivityLog
import dev.klerkframework.klerk.read.ModelModification
import dev.klerkframework.klerk.read.Reader
import kotlinx.coroutines.flow.Flow

/** A running Klerk application. Create one with [Klerk.create]. */
public interface Klerk<C : KlerkContext, V> {

    /** What the application is: models, state machines, views and plugins. */
    public val specification: Specification<C, V>

    /** How this instance runs: storage, clock, metrics and job operation. */
    public val settings: KlerkSettings

    /** Background jobs: submitting, inspecting and controlling them. */
    public val jobs: JobManager<C, V>

    /** Subscriptions to model changes. */
    public val modelChanges: KlerkModelChanges<C, V>

    /** Large immutable data (blobs and strings) attached to models. */
    public val attachedData: KlerkAttachedData<C>

    /** Lifecycle and information about the running instance. */
    public val meta: KlerkMeta

    /**
     * What has happened in the application recently: starts, stops, commands and plugin activity. Not to be confused
     * with the event log, which is persisted and read with [dev.klerkframework.klerk.read.Reader.eventLog].
     */
    public val activityLog: ActivityLog<C>

    /**
     * Submits [command] for processing on behalf of the actor in [context], and returns a [CommandResult] describing
     * the outcome.
     *
     * [options] defaults to a fresh [CommandToken.simple] token, i.e. a command that is only guarded against being
     * submitted twice. Pass a token from [CommandToken.requireUnmodifiedModel] for an optimistic-concurrency check.
     */
    public suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions = ProcessingOptions(),
    ): CommandResult<T>

    /**
     * Acquires a read lock, runs [readFunction] with a [Reader] receiver, and returns its result.
     *
     * The read happens on behalf of the actor in [context], which can be overridden inside [readFunction] (see
     * [Reader]). The function suspends until the read lock has been acquired. No event is processed while
     * [readFunction] is executed.
     *
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> read(context: C, readFunction: Reader<C, V>.() -> T): T

    /**
     * Like [read], but [readFunction] is itself `suspend`, so it may perform other suspending work (e.g. network
     * calls) while the read lock is held.
     *
     * Doing so blocks every other command and read in the system for the duration, so use with care. In particular,
     * never submit a command from within [readFunction] — that deadlocks the application. [readSuspend] is generally
     * discouraged, prefer [read] unless you have a good reason to suspend inside the read.
     *
     * @throws AuthorizationException if the actor tries to read a model it is not authorized to access
     */
    public suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, V>.() -> T): T

    public companion object {

        /**
         * Creates an instance of Klerk from a [specification] of what the application is (see
         * [SpecificationBuilder]) and [settings] for how it runs: storage, clock, metrics and job operation.
         */
        public fun <C : KlerkContext, V> create(
            specification: Specification<C, V>,
            settings: KlerkSettings,
        ): Klerk<C, V> = KlerkImpl(specification, settings)
    }
}

/**
 * A read prepared inside a read block and performed by [get] after the block has ended, so that storage is never
 * queried while the read lock is held.
 */
public interface PendingRead<out T> {

    /**
     * Performs the read. The result is as of the read block that created this, however late or often it is called.
     *
     * @throws IllegalStateException if called from inside a read block
     */
    public suspend fun get(): T
}

/**
 * Subscriptions to model changes, as [Klerk.modelChanges].
 */
public interface KlerkModelChanges<C : KlerkContext, V> {

    /**
     * Subscribes to changes of the model [id], or of all models if [id] is null. The actor in [context] is used for
     * authorization.
     *
     * Changes of models the actor may not read are left out, and so are changes of a model that no longer exists when
     * the change is delivered. Deletions are always sent.
     */
    public fun subscribe(id: ModelID<out Any>?, context: C): Flow<ModelModification>
}

/** Lifecycle and information about a running Klerk instance, as [Klerk.meta]. */
public interface KlerkMeta {

    /**
     * Brings the framework to a state where it can process new events and jobs.
     *
     * If [installShutdownHook] is true, a shutdown hook calls [stop], which reduces the possibility that background
     * executions are terminated before completion.
     */
    public suspend fun start(installShutdownHook: Boolean = true)

    /**
     * Shuts down the framework in an ordered manner. Plugins are stopped first, in reverse order. It is recommended
     * to stop clients (Ktor, gRPC etc.) first.
     *
     * Suspends until the job steps that are already running have finished, for at most 30 seconds. A step that has not
     * finished by then is abandoned without committing anything, and runs again on the next start.
     */
    public suspend fun stop()

    /**
     * The number of models currently in the system, including those created by plugins.
     *
     * So this is not a way to tell whether the application has any data of its own yet — a plugin may have created
     * models at startup. Ask the view instead: `klerk.read(context) { views.users.all.isEmpty() }`.
     */
    public val modelsCount: Int
}

package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.datatypes.AttachedStringContainer
import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.log.KlerkLog
import dev.klerkframework.klerk.read.ModelModification
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.EventLogEntry
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

public interface Klerk<C : KlerkContext, V> {

    public companion object {

        /**
         * Creates an instance of Klerk.
         *
         * @param specification what the application is; can be created with [SpecificationBuilder]
         * @param settings how this instance runs: storage, clock, metrics and job operation
         */
        public fun <C : KlerkContext, V> create(
            specification: Specification<C, V>,
            settings: KlerkSettings
        ): Klerk<C, V> {
            return KlerkImpl(specification, settings)
        }
    }

    public val spec: Specification<C, V>
    public val settings: KlerkSettings
    public val jobs: JobManager<C, V>
    public val models: KlerkModels<C, V>

    /**
     * Large immutable data (blobs and strings) attached to models.
     */
    public val attachedData: KlerkAttachedData<C>
    public val meta: KlerkMeta
    public val log: KlerkLog

    /**
     * Submits a single event for processing.
     *
     * @param command the event
     * @param context including the actorIdentity on whose behalf the read happens
     * @param options defaults to a fresh [dev.klerkframework.klerk.command.CommandToken.simple] token, i.e. a command
     * that is only guarded against being submitted twice. Pass a token from
     * [dev.klerkframework.klerk.command.CommandToken.requireUnmodifiedModel] for an optimistic-concurrency check.
     * @return either a Success or a Failure describing the processing result
     */
    public suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions = ProcessingOptions(CommandToken.simple())
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
     * never submit a command from within [readFunction] — that deadlocks the application. [readSuspend] is generally
     * discouraged, prefer [read] unless you have a good reason to suspend inside the read.
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

/**
 * A snapshot of the event log, obtained from [Reader.eventLog] inside a read block. The entries themselves are read
 * from storage by [get], after the read lock has been released.
 */
public interface EventLogQuery {

    /**
     * Reads the matching entries, ordered by [EventLogEntry.sequenceNumber], oldest first.
     *
     * Only entries whose command was already visible in the read block that created this query are returned, so the
     * log never shows an event that has not happened yet. Can be called repeatedly; the result is always as of that
     * read block.
     *
     * @throws IllegalStateException if called from inside a read block
     */
    public suspend fun get(): List<EventLogEntry>
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

/**
 * Reads job state from inside a read block, as [dev.klerkframework.klerk.read.Reader.jobs].
 *
 * What is returned is part of the block's snapshot, exactly like a model: no command and no job step can change it
 * while the block runs, so reading the same job twice always gives the same answer. Use [JobManager] instead outside
 * a read block — its methods take the read lock themselves and refuse to run inside one.
 */
public interface JobReader {

    /**
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see it.
     */
    public fun get(id: JobId): JobInfo

    /** Null if there is no such job, or the actor is not allowed to see it. */
    public fun getOrNull(id: JobId): JobInfo?

    /** Every job the actor is allowed to see, newest first. */
    public fun all(): List<JobInfo>
}

/**
 * Schedules, inspects and controls managed background jobs. See the "Jobs" documentation for the whole model.
 */
public interface JobManager<C : KlerkContext, V> {

    /**
     * Schedules one job, built with [dev.klerkframework.klerk.job.JobType.declare].
     *
     * This is the way to schedule a job that no command is responsible for. A job that belongs to a command should be
     * returned from a state machine's `job(...)` executable instead, so that it is persisted in that command's own
     * transaction and is not scheduled at all if the command fails.
     *
     * New work goes through admission control, so this can fail when the queue is not draining — see
     * [KlerkErrorCode.JobQueueOverloaded]. Yields, retries, spawned children and end-of-life hooks never do.
     *
     * @param context whose actor is recorded as the job's owner, and is what the job authorization rules see.
     * @return the id of the scheduled job.
     * @throws IllegalStateException if the job was refused by the admission policy or the hard queue cap.
     */
    public suspend fun schedule(job: DeclaredJob<C, V>, context: C): JobId

    /**
     * Everything known about one job.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see it.
     */
    public suspend fun getJob(id: JobId, context: C): JobInfo

    /** Every job the actor is allowed to see, newest first. */
    public suspend fun getAllJobs(context: C): List<JobInfo>

    /**
     * Emits a [JobInfo] every time a job the actor may see changes — for a live progress bar.
     *
     * @param id if provided, only that job's changes are emitted. If null, every visible job's are.
     */
    public fun subscribe(context: C, id: JobId?): Flow<JobInfo>

    /**
     * Requests cancellation, and returns as soon as the request has been recorded.
     *
     * The job moves to [dev.klerkframework.klerk.job.JobStatus.Cancelling] and reaches
     * [dev.klerkframework.klerk.job.JobStatus.Cancelled] only once the in-flight step has returned, every descendant
     * is terminal and `onCancelled` has finished. **Cancel latency is therefore the slowest step in the subtree**, so
     * a UI should render `Cancelling` as its own state rather than a button that appears to do nothing.
     *
     * Requires the same authorization as [getJob].
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see the job.
     */
    public suspend fun cancel(id: JobId, context: C, reason: String = "Cancelled"): Unit

    /**
     * Puts a dead-lettered job back in the queue, **resuming from its checkpoint** — never restarting from step 0,
     * because the commands from steps 1..n have already been applied and Klerk cannot recognise re-emitted ones.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see the job.
     * @throws IllegalStateException if the job is not dead-lettered.
     */
    public suspend fun resume(id: JobId, context: C): Unit

    /**
     * Deletes a terminal job, releasing any claim it holds on attached data. Data that a committed command attached to
     * a live model is never affected.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see the job.
     * @throws IllegalStateException if the job has not reached a terminal status.
     */
    public suspend fun delete(id: JobId, context: C): Unit

    /**
     * Runs exactly one step, if any job is ready. Only for [dev.klerkframework.klerk.job.JobExecution.Manual].
     *
     * @return true if a step ran, false if there was nothing to do.
     * @throws IllegalStateException if execution is [dev.klerkframework.klerk.job.JobExecution.Automatic].
     */
    public suspend fun step(): Boolean

    /**
     * Runs steps until no job is ready any more. Only for [dev.klerkframework.klerk.job.JobExecution.Manual].
     *
     * Jobs waiting for a `scheduleAt` or a backoff that has not arrived on the configured clock are *not* ready, so
     * this returns rather than spinning — advance a [dev.klerkframework.klerk.misc.MutableClock] and call it again.
     *
     * @param maxSteps a safety net against a job that yields forever.
     * @return how many steps ran.
     * @throws IllegalStateException if execution is [dev.klerkframework.klerk.job.JobExecution.Automatic], or if
     * [maxSteps] was reached (which means a test would otherwise have hung).
     */
    public suspend fun runUntilIdle(maxSteps: Int = 10_000): Int

}

internal interface JobManagerInternal<C : KlerkContext, V> : JobManager<C, V> {

    /** True if no job is using this id. Used while allocating ids during command processing. */
    fun isJobIdAvailable(int: Int): Boolean

    /**
     * Turns the jobs a command declared into rows to write, applying admission control. Called on the command path
     * before anything is committed, so that a refusal can still fail the command.
     */
    fun planNewJobs(pending: List<PendingJob<C, V>>, context: C): NewJobPlan

    /**
     * Applies a committed job commit to the in-memory queue.
     *
     * **Must be called while holding the write lock, and must never take the job manager's own mutex** — job state is
     * part of what a read block sees, so it has to flip in the same critical section as models and views, and taking
     * the mutex underneath the write lock would invert the lock order and deadlock.
     */
    fun applyToMemory(commit: JobCommit)

    /**
     * Tells subscribers and the dispatcher about rows [applyToMemory] has already applied.
     *
     * **Must be called after the write lock is released.** Emitting can resume a collector inline, and a collector
     * reading job state takes the read lock — which would deadlock against the writer emitting to it.
     */
    fun notifyCommitted(commit: JobCommit)

    /**
     * [JobManager.schedule], with the new job claiming [claim] in the same commit.
     *
     * Used when the job exists to work on attached data that nothing references yet: without the claim in the same
     * transaction, the orphan reaper could take the value between the two writes.
     */
    suspend fun scheduleClaiming(job: DeclaredJob<C, V>, context: C, claim: Set<Int>): JobId
}

/** What [JobManagerInternal.planNewJobs] decided: either rows to write, or the problems that must fail the command. */
internal sealed class NewJobPlan {
    data class Ok(val records: List<JobRecord>) : NewJobPlan() {
        val commit: JobCommit get() = JobCommit(upserted = records)
    }

    data class Rejected(val problems: List<Problem>) : NewJobPlan()
}

/**
 * Large immutable data attached to models.
 *
 * Models should be kept small so they fit in the internal cache. Instead of storing a large value in the model
 * itself, prepare it here and store the returned ID in a model property (of type [AttachedBlobID] or [AttachedStringID]).
 *
 * Writing happens in two steps since uploading may be slow but updating a model must be quick:
 * 1. [prepare] inserts the data (slow, no lock is held)
 * 2. a command stores the returned ID in a model property (fast)
 *
 * A blob is prepared for a particular [dev.klerkframework.klerk.datatypes.AttachedBlobContainer], which decides what it may be
 * and what must happen to it first. When that declaration declares steps, Klerk runs them in a job and the value
 * cannot be attached until they have all run — see [awaitProcessing].
 *
 * If no committed command references a prepared ID within one minute, the data is deleted and a later attempt to use
 * that ID will fail the command.
 *
 * Blobs and strings are the same thing stored the same way (a string is its UTF-8 bytes) and share one ID space. What
 * separates them is the type of the ID, which says what the value means, decides how it may be read back, and lets a
 * model property declare which of the two it holds. An ID used through the wrong type is rejected.
 *
 * Attached data is exclusively owned: an ID belongs to the first model that references it in a committed command, and
 * a command trying to attach data owned by another model is rejected. The data is deleted when no property of the
 * owning model refers to it any more (i.e. on replacement, on set-to-null, and on model deletion), in the same
 * transaction as the command.
 *
 * There is no way to delete attached data directly, and there is no way to store a standalone value.
 */
public interface KlerkAttachedData<C : KlerkContext> {

    /**
     * Inserts a blob so that it can be attached to a model.
     *
     * This may take a while, so it is deliberately done outside the command processing. No lock is held while the data
     * is written.
     *
     * Whether the blob may be read by anyone is *not* decided here: it is declared by the
     * [dev.klerkframework.klerk.datatypes.AttachedBlobContainer] the value ends up in, and applied when a command attaches it.
     * Whoever uploads a file cannot know what it will be used for, so it is not their decision to make.
     *
     * The [dev.klerkframework.klerk.datatypes.AttachedBlobContainer.preAttachSteps] [declaration] declares — a virus scan, a
     * Content Disarm & Reconstruct pass, a check of the contents — are run by a job Klerk schedules here, and this
     * returns as soon as the bytes are written. A command attaching a value whose steps have not all run is rejected,
     * so wait for [awaitProcessing] before issuing it. A declaration whose only step is
     * [dev.klerkframework.klerk.datatypes.noPreAttachProcessing] schedules nothing.
     *
     * The returned id is unique among *all* attached data, blobs and strings alike.
     *
     * @param declaration the property this value is being prepared for. It decides what the value must be and what
     * must happen to it first, so it is required — a blob is always prepared for somewhere.
     * @param metadata anything the application wants to store alongside the data, such as a content type. It is handed
     * back by [getMetadata] and is *not* given to the authorization rules. Must not exceed 1000 characters when
     * JSON-encoded, since it is kept in memory for the lifetime of the data.
     * @param lease how long the data survives without being claimed. Defaults to
     * [KlerkSettings.unclaimedAttachedDataLifetime] (one minute), which is right when the command follows
     * immediately. Ask for a longer one when it cannot — an upload that is prepared as its last byte arrives but is
     * not attached until the user submits a form, say. A lease may not exceed
     * [KlerkSettings.maxAttachedDataLease], and the `writeAttachedData` rules see it, so who may hold data for a
     * long time is a decision the application can make.
     * @return the ID to be stored in a model property by a subsequent command. If no command does so before the lease
     * runs out, the data is deleted.
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalArgumentException if the metadata is too large, if the lease exceeds the maximum, or if
     * [declaration] cannot be built from an id alone
     */
    public suspend fun prepare(
        value: InputStream,
        declaration: KClass<out AttachedBlobContainer>,
        context: C,
        metadata: Map<String, String> = emptyMap(),
        lease: Duration? = null,
    ): AttachedBlobID

    /**
     * Inserts a blob that is already a file, taking the file over instead of copying it when the blob store can.
     *
     * With [dev.klerkframework.klerk.storage.FileBlobStore] on the same filesystem as [file], this is a rename: the
     * bytes are read once to compute the size and hash, and never written a second time. Anywhere else it behaves
     * exactly like [prepare] with the file's stream.
     *
     * The file is *moved*, so it no longer exists at its old location afterwards — unless the store could not adopt
     * it, in which case it is copied and left alone.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws java.io.IOException if the file cannot be read
     */
    public suspend fun prepareFromFile(
        file: Path,
        declaration: KClass<out AttachedBlobContainer>,
        context: C,
        metadata: Map<String, String> = emptyMap(),
        lease: Duration? = null,
    ): AttachedBlobID

    /**
     * Waits for the steps the value's declaration declares — a virus scan, a Content Disarm & Reconstruct pass, a
     * check that a CSV has the right columns — to finish running.
     *
     * ```kotlin
     * val blob = klerk.attachedData.prepare(bytes, FlowerImage::class, context)
     * klerk.attachedData.awaitProcessing(blob)
     * // now the command that attaches it will be accepted
     * ```
     *
     * Klerk runs the steps in a job of its own, so they are retried when a scanner is briefly unreachable and end up
     * in the dead-letter queue when they are not. This returns immediately for a value whose declaration has no step
     * to run — [dev.klerkframework.klerk.datatypes.noPreAttachProcessing] — or whose steps have already run.
     *
     * A command attaching a value whose declared steps have not all run is rejected, so this is a guarantee rather
     * than a convention.
     *
     * Not subject to authorization: what runs is what the developer declared on the property, not something an actor
     * chose to do. Who may put a file into the system at all is decided by [prepare], and what may be attached to a
     * model by the command that attaches it.
     *
     * Under [dev.klerkframework.klerk.job.JobExecution.Manual] nothing runs on its own, so this drives the job queue
     * itself rather than waiting for something that will never happen.
     *
     * @param timeout how long to wait. The steps keep running afterwards; only the waiting stops.
     * @throws BlobRejected if a step refused the file, or if it does not satisfy `accept`/`maxSize`. The value is
     * deleted, so this is the only place the reason can be read.
     * @throws kotlinx.coroutines.TimeoutCancellationException if [timeout] passes first.
     */
    public suspend fun awaitProcessing(
        id: AttachedBlobID,
        timeout: Duration = 5.minutes,
    ): Unit

    /**
     * Inserts a string so that it can be attached to a model.
     *
     * See [prepare] for blobs; the semantics are identical, including [declaration] — a string is declared via an
     * [dev.klerkframework.klerk.datatypes.AttachedStringContainer] the same way a blob is declared via an
     * [dev.klerkframework.klerk.datatypes.AttachedBlobContainer], and whether it may be read by anyone is decided
     * there, not here. A string is stored as its UTF-8 bytes, so the only thing that distinguishes the two kinds is
     * the type of the id — and thus what the value means and how it may be read back. A string has no pre-attach
     * steps, so nothing here waits for a job the way [awaitProcessing] does for a blob.
     *
     * Note that the whole string is held in memory here. For something big enough that that is a problem, prepare it
     * as a blob instead.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalArgumentException if the metadata is too large, or if [declaration] cannot be built from an id
     * alone
     */
    public suspend fun prepare(
        value: String,
        declaration: KClass<out AttachedStringContainer>,
        context: C,
        metadata: Map<String, String> = emptyMap(),
        lease: Duration? = null,
    ): AttachedStringID

    /**
     * Retrieves a blob.
     *
     * Must be called *outside* a read block: attached data is often large, and holding the read lock while streaming
     * it would block every command and every read in the application. This function acquires the lock briefly on its
     * own to make the authorization decision, releases it, and then returns the stream.
     *
     * If the data is [AttachedDataVisibility.Public], no authorization rule is evaluated at all.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, or if the id refers to a
     * string rather than a blob
     */
    public suspend fun get(id: AttachedBlobID, context: C): InputStream

    /**
     * Retrieves a string, decoded from UTF-8.
     *
     * See [get] for blobs; the semantics are identical, except that the whole value is brought into memory. Use
     * [getStream] to avoid that.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, or if the id refers to a blob
     * rather than a string
     */
    public suspend fun get(id: AttachedStringID, context: C): String

    /**
     * Retrieves a string as a stream of its UTF-8 bytes, for a value large enough that holding all of it in memory is
     * undesirable — writing it straight to an HTTP response, say.
     *
     * Authorized exactly like [get].
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, or if the id refers to a blob
     * rather than a string
     */
    public suspend fun getStream(id: AttachedStringID, context: C): InputStream

    /**
     * Retrieves what is known about a blob apart from its value: visibility, creation time, size, content hash and
     * whatever metadata was provided to [prepare].
     *
     * Authorized exactly like [get]: public data is described to anyone, private data only to the actors the
     * `readAttachedData` rules allow — a content hash reveals whether the data is a file the caller already has.
     *
     * This is the natural first call when serving attached data over HTTP: it provides the headers (content type from
     * the metadata, content length from the size, cache policy from the visibility) and lets a URL be stamped with the
     * hash, without touching the value.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, if it has not yet been
     * attached to a model, or if the id refers to a string rather than a blob
     */
    public suspend fun getMetadata(id: AttachedBlobID, context: C): AttachedDataMetadata

    /**
     * Retrieves what is known about a string apart from its value.
     *
     * See [getMetadata] for blobs; the semantics are identical.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, if it has not yet been
     * attached to a model, or if the id refers to a blob rather than a string
     */
    public suspend fun getMetadata(id: AttachedStringID, context: C): AttachedDataMetadata

    /**
     * Retrieves what is known about a value whose kind is not known yet, e.g. one named by nothing but an id in a
     * URL. [AttachedDataMetadata.kind] says which kind it turned out to be; [AttachedDataID.asBlob] and
     * [AttachedDataID.asString] then give the typed id needed to read it.
     *
     * Authorized exactly like [get].
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalStateException if called inside [Klerk.read] or [Klerk.readSuspend]
     * @throws kotlin.NoSuchElementException if there exists no data for the provided id, or if it has not yet been
     * attached to a model
     */
    public suspend fun getMetadata(id: AttachedDataID, context: C): AttachedDataMetadata

}

/**
 * Attached-data metadata, as part of a read block's snapshot. This is how it is read inside a read block —
 * [KlerkAttachedData.getMetadata] takes the read lock itself and refuses to run inside one.
 *
 * Only metadata: the value itself is often large, so reading it stays outside the block, where holding the lock
 * across a stream cannot block the application.
 */
public interface AttachedDataReader {

    /**
     * What is known about the value with [id], whichever kind it is.
     *
     * @throws kotlin.NoSuchElementException if there is no such data, or it has not been attached to a model
     * @throws AuthorizationException if the actor isn't authorized
     */
    public fun metadata(id: AttachedDataID): AttachedDataMetadata

    /** Null if there is no such data, or the actor isn't allowed to read it. */
    public fun metadataOrNull(id: AttachedDataID): AttachedDataMetadata?

    /** As [metadata], and additionally throws if the id turns out to refer to a string. */
    public fun metadata(id: AttachedBlobID): AttachedDataMetadata

    /** As [metadata], and additionally throws if the id turns out to refer to a blob. */
    public fun metadata(id: AttachedStringID): AttachedDataMetadata
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
     * The number of models currently in the system, including those created by plugins.
     *
     * So this is not a way to tell whether the application has any data of its own yet — a plugin may have created
     * models at startup. Ask the view instead: `klerk.read(context) { views.users.all.isEmpty() }`.
     */
    public val modelsCount: Int

}

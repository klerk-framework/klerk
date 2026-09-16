package dev.klerkframework.klerk

import dev.klerkframework.klerk.storage.spi.*
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.datatypes.AttachedStringContainer
import dev.klerkframework.klerk.job.*
import java.io.InputStream
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Large immutable data attached to models.
 *
 * Models should be kept small so they fit in the internal cache. Instead of storing a large value in the model
 * itself, prepare it here and store the returned ID in a model property (of type [AttachedBlobID] or
 * [AttachedStringID]).
 *
 * Writing happens in two steps since uploading may be slow but updating a model must be quick:
 * 1. [prepare] inserts the data (slow, no lock is held)
 * 2. a command stores the returned ID in a model property (fast)
 *
 * A blob is prepared for a particular [dev.klerkframework.klerk.datatypes.AttachedBlobContainer], which decides what it
 * may be and what must happen to it first. When that declaration declares steps, Klerk runs them in a job and the value
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
     * [declaration] is the property this value is being prepared for. It decides what the value must be and what must
     * happen to it first, so it is required — a blob is always prepared for somewhere.
     *
     * Whether the blob may be read by anyone is *not* decided here: it is declared by the [AttachedBlobContainer] the
     * value ends up in, and applied when a command attaches it. Whoever uploads a file cannot know what it will be
     * used for, so it is not their decision to make.
     *
     * The [AttachedBlobContainer.preAttachSteps] [declaration] declares — a virus scan, a Content Disarm & Reconstruct
     * pass, a check of the contents — are run by a job Klerk schedules here, and this returns as soon as the bytes are
     * written. A command attaching a value whose steps have not all run is rejected, so wait for [awaitProcessing]
     * before issuing it. A declaration whose only step is [dev.klerkframework.klerk.datatypes.noPreAttachProcessing]
     * schedules nothing.
     *
     * [custom] is anything the application wants to store alongside the data, such as a content type. It is handed
     * back by [getMetadata] and is *not* given to the authorization rules. It must not exceed 1000 characters when
     * JSON-encoded, since it is kept in memory for the lifetime of the data.
     *
     * [lease] is how long the data survives without being claimed. It defaults to
     * [KlerkSettings.defaultAttachedDataLease] (one minute), which is right when the command follows immediately. Ask
     * for a longer one when it cannot — an upload that is prepared as its last byte arrives but is not attached until
     * the user submits a form, say. A lease may not exceed [KlerkSettings.maxAttachedDataLease], and the
     * `writeAttachedData` rules see it, so who may hold data for a long time is a decision the application can make.
     *
     * Returns the id to store in a model property with a subsequent command. If no command does so before the lease
     * runs out, the data is deleted. The id is unique among *all* attached data, blobs and strings alike.
     *
     * @throws AuthorizationException if the actor isn't authorized
     * @throws IllegalArgumentException if [custom] is too large, if the lease exceeds the maximum, or if
     * [declaration] cannot be built from an id alone
     */
    public suspend fun prepare(
        value: InputStream,
        declaration: KClass<out AttachedBlobContainer>,
        context: C,
        custom: Map<String, String> = emptyMap(),
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
        custom: Map<String, String> = emptyMap(),
        lease: Duration? = null,
    ): AttachedBlobID

    /**
     * Waits at most [timeout] for the steps the value's declaration declares — a virus scan, a Content Disarm &
     * Reconstruct pass, a check that a CSV has the right columns — to finish running. The steps keep running after a
     * timeout; only the waiting stops.
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
     * @throws BlobRejectedException if a step refused the file, or if it does not satisfy `accept`/`maxSize`. The
     * value is deleted, so this is the only place the reason can be read.
     * @throws kotlinx.coroutines.TimeoutCancellationException if [timeout] passes first.
     */
    public suspend fun awaitProcessing(
        id: AttachedBlobID,
        timeout: Duration = 5.minutes,
    )

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
     * @throws IllegalArgumentException if [custom] is too large, or if [declaration] cannot be built from an id
     * alone
     */
    public suspend fun prepare(
        value: String,
        declaration: KClass<out AttachedStringContainer>,
        context: C,
        custom: Map<String, String> = emptyMap(),
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
     * This overload could serve every case, since [AttachedBlobID.untyped] and [AttachedStringID.untyped] convert.
     * The typed overloads exist because they also check the kind: asking through an [AttachedBlobID] for something
     * that turned out to be a string is a bug, and they report it here rather than letting it travel.
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
    public fun getMetadata(id: AttachedDataID): AttachedDataMetadata

    /** Null if there is no such data, or the actor isn't allowed to read it. */
    public fun getMetadataOrNull(id: AttachedDataID): AttachedDataMetadata?

    /** As [getMetadata], and additionally throws if the id turns out to refer to a string. */
    public fun getMetadata(id: AttachedBlobID): AttachedDataMetadata

    /** As [getMetadata], and additionally throws if the id turns out to refer to a blob. */
    public fun getMetadata(id: AttachedStringID): AttachedDataMetadata
}

package dev.klerkframework.klerk.datatypes

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.InvalidPropertyProblem
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.misc.functionName
import java.io.InputStream

/**
 * A reference to an attached blob or attached string, together with what that value is allowed to be.
 *
 * Attached bytes are not in the model, so unlike every other container this one does not validate a value it holds —
 * it declares what Klerk should check about the value when a command attaches it, and what should happen when it is
 * served afterwards. Application code always extends one of the two concrete subclasses, [AttachedBlobContainer] or
 * [AttachedStringContainer], never this one directly.
 *
 * The checks run in the command pipeline, against what Klerk itself recognised the bytes to be — so they hold for a
 * command from a web form, from klerk-graphql, from a job or from a test alike.
 *
 * **A declared type is not a promise about safety.** A value can satisfy two formats at once, so `accept` keeps
 * honest mistakes out, not a determined attacker. What makes serving safe is the response headers and the origin the
 * bytes are served from.
 */
public sealed class AttachedDataContainer<ID>(id: ID) : DataContainer<ID>(id) {

    /**
     * The content types this property accepts, as IANA media types (e.g. `image/png`). Empty means anything.
     *
     * Checked against the type Klerk recognised from the bytes themselves, never against what the uploader claimed.
     */
    public open val accept: Set<String> = emptySet()

    /**
     * Whether a value whose type Klerk could not recognise is acceptable.
     *
     * Only consulted when [accept] is non-empty. It has to be a decision rather than a default: CSV, plain text and
     * plenty of binary formats have no signature at all, so a property accepting those must say so, and one
     * accepting images must not.
     */
    public open val acceptUnrecognised: Boolean = false

    /** The largest value this property accepts, in bytes. */
    public open val maxSize: Long = Long.MAX_VALUE

    /**
     * Whether the value may be read by anyone, or only by the actors the `readAttachedData` rules allow.
     *
     * Applied when a command attaches the value, and never changed afterwards — which is what makes
     * [AttachedDataVisibility.Public] safe to cache. Declaring it here rather than passing it to `prepare` means the
     * decision is made where it is known: whoever uploads a value has no idea what it will end up being used for.
     */
    public open val visibility: AttachedDataVisibility = AttachedDataVisibility.Private

    /** The attached value this property refers to. */
    public val id: ID get() = value

    /** The id unwrapped to the shared attached-data id space, regardless of whether it is a blob or a string id. */
    internal abstract val rawId: Int

    /**
     * Always valid: there is no value here to check. What this container declares is checked when a command attaches
     * the value, against metadata Klerk produced while the bytes were being written.
     */
    final override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /**
     * Whether [metadata] satisfies what this property declares. Returns null if it does, otherwise a description of
     * what is wrong, for the command's problem.
     */
    internal fun reasonToReject(metadata: AttachedDataMetadata): String? {
        if (metadata.size > maxSize) {
            return "it is ${metadata.size} bytes, and at most $maxSize is allowed"
        }
        if (accept.isEmpty()) {
            return null
        }
        val detected = metadata.contentType
            ?: return if (acceptUnrecognised) null else "its type could not be recognised, and ${describeAccepted()}"
        return if (detected in accept) null else "it is $detected, and ${describeAccepted()}"
    }

    private fun describeAccepted(): String = "only ${accept.sorted().joinToString(", ")} is allowed"
}

/**
 * A reference to an attached blob, together with what that blob is allowed to be:
 *
 * ```kotlin
 * class FlowerImage(id: AttachedBlobID) : AttachedBlobContainer(id) {
 *     override val accept = setOf("image/png", "image/jpeg", "image/webp")
 *     override val maxSize = 5_000_000L
 *     override val visibility = AttachedDataVisibility.Public
 *     override val preAttachSteps = listOf(::stripExif, ::reEncode)
 * }
 *
 * data class Flower(val name: FlowerName, val image: FlowerImage)
 * ```
 *
 * klerk-web additionally renders the declaration as the file input's `accept` attribute, the same way it renders
 * `maxLength` for a string.
 */
public abstract class AttachedBlobContainer(id: AttachedBlobID) : AttachedDataContainer<AttachedBlobID>(id) {

    override val rawId: Int get() = id.value

    /**
     * What has to happen to a file before this property will hold it: looking at the bytes, and where necessary
     * rewriting them.
     *
     * ```kotlin
     * override val preAttachSteps = listOf(::scanForViruses, ::stripMacros, ::scanForViruses)
     *
     * suspend fun stripMacros(args: BlobPreAttachStepArgs): BlobPreAttachStepResult =
     *     BlobPreAttachStepResult.Replace(disarm(args.value))
     * ```
     *
     * Steps run in declared order, each on the current bytes, and **nothing re-runs implicitly** — if the scanner
     * should see the disarmed output, declare it twice, as above.
     *
     * Klerk runs them in a job it schedules from `prepare`, one step of the job per step declared here. A command that
     * attaches a value whose declared steps have not all run is rejected; wait
     * for `klerk.attachedData.awaitProcessing(...)` before issuing it.
     *
     * A step gets the bytes and the metadata, and nothing else. Anything that
     * needs the actor or the model graph is an authorization rule or a validator, not a step.
     *
     * Each must be a named function reference. A value that has been through these steps can only be attached to a
     * property of this container; steps of another container do not count, even if they have the same names.
     *
     * At least one step is required: an uploaded file usually has to be looked at before it is kept. A property that
     * genuinely wants nothing done says so with [noPreAttachProcessing], which must then be the only step and costs
     * nothing at runtime.
     */
    public abstract val preAttachSteps: List<BlobPreAttachStep>

    /**
     * The steps that actually run, with the names they are recorded under. Computed once, and it is here that an empty
     * list, an unnamed step, or a misused [noPreAttachProcessing] is caught.
     */
    internal val stepsToRun: List<Pair<String, BlobPreAttachStep>> by lazy {
        val named = preAttachSteps.map { step ->
            val name = functionName(step)
                ?: throw IllegalArgumentException(
                    "Every step of ${this::class.simpleName} must be a named function reference (::myStep), since " +
                        "the name is what records that it has run. A lambda has no name to record.",
                )
            name to step
        }
        if (named.isEmpty()) {
            throw IllegalArgumentException(
                "${this::class.simpleName} must declare at least one preAttachStep: an uploaded file is not to be " +
                    "trusted until something has looked at it (a virus scan, an EXIF strip, a re-encode). If " +
                    "this property really wants the bytes exactly as they arrived, say so explicitly with " +
                    "'override val preAttachSteps = listOf(::noPreAttachProcessing)'.",
            )
        }
        val doNothing = named.filter { it.second == NO_PRE_ATTACH_PROCESSING }
        if (doNothing.isNotEmpty() && named.size > 1) {
            throw IllegalArgumentException(
                "${this::class.simpleName} declares noPreAttachProcessing together with other steps. It says that " +
                    "there is nothing to do, so it can only be the only step.",
            )
        }
        named.filterNot { it.second == NO_PRE_ATTACH_PROCESSING }
    }

    /** The names of the steps that run against a value before this property may hold it, in order. */
    public val stepNames: List<String> get() = stepsToRun.map { it.first }
}

/**
 * A reference to an attached string, together with what that string is allowed to be — the string-kind counterpart
 * of [AttachedBlobContainer]. See that class for what each property means; a string has no
 * [AttachedBlobContainer.preAttachSteps] equivalent, since it is never scanned or rewritten before it is kept.
 *
 * ```kotlin
 * class BookNotes(id: AttachedStringID) : AttachedStringContainer(id) {
 *     override val accept = setOf("text/plain")
 *     override val maxSize = 10_000L
 * }
 * ```
 */
public abstract class AttachedStringContainer(id: AttachedStringID) : AttachedDataContainer<AttachedStringID>(id) {
    override val rawId: Int get() = id.value
}

/**
 * One thing that must happen to a file before a property will hold it — see [AttachedBlobContainer.preAttachSteps].
 *
 * Must be a named function reference. It runs outside command processing, so it may take its time.
 */
public typealias BlobPreAttachStep = suspend (BlobPreAttachStepArgs) -> BlobPreAttachStepResult

/**
 * What a [BlobPreAttachStep] is given.
 *
 * @property value the current bytes. Opened on the first read, so a step that decides from the metadata alone costs
 * nothing.
 * @property metadata what Klerk knows about the value, including the content type it recognised and the size.
 */
public class BlobPreAttachStepArgs(public val value: InputStream, public val metadata: AttachedDataMetadata) {
    override fun toString(): String = "BlobPreAttachStepArgs($metadata)"
}

/**
 * A [BlobPreAttachStep] that does nothing, for an [AttachedBlobContainer] that wants the bytes exactly as they arrived:
 *
 * ```kotlin
 * override val preAttachSteps = listOf(::noPreAttachProcessing)
 * ```
 *
 * It must then be the only step. Klerk skips the processing job entirely, so a value prepared for such a property is
 * ready to be attached at once and needs no `awaitProcessing`.
 *
 * Think twice before using it: an uploaded file arrives from whoever sent it, and `accept` alone does not make it
 * safe to keep or to serve.
 */
public suspend fun noPreAttachProcessing(args: BlobPreAttachStepArgs): BlobPreAttachStepResult =
    BlobPreAttachStepResult.Pass

/** The reference the steps are compared against, so that a user function of the same name is not mistaken for it. */
private val NO_PRE_ATTACH_PROCESSING: BlobPreAttachStep = ::noPreAttachProcessing

/** What a [BlobPreAttachStep] concluded. */
public sealed class BlobPreAttachStepResult {

    /** The file is fine as it is. */
    public data object Pass : BlobPreAttachStepResult()

    /** The file must not be stored. [reason] is shown to whoever submitted it. */
    public data class Reject(val reason: String) : BlobPreAttachStepResult()

    /**
     * The file has been rewritten — a disarmed document, a re-encoded image — and [value] replaces it.
     *
     * Allowed only because the value is not yet claimed by any model: nothing can read it, no URL names it and no
     * cache can hold it. Once a command attaches it, it is immutable.
     */
    public data class Replace(val value: InputStream) : BlobPreAttachStepResult()
}

package dev.klerkframework.klerk.datatypes

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.validation.PropertyValidation
import dev.klerkframework.klerk.validation.PropertyValidation.Invalid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import java.io.InputStream
import java.time.LocalDate
import dev.klerkframework.klerk.misc.functionName
import dev.klerkframework.klerk.misc.requireNamedRule
import kotlin.time.Duration
import kotlin.time.Instant

private const val MASKED = "[••••••]"

internal val instantToStringFormat = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char(' ')
    hour(); char(':'); minute(); char(':'); second()
}

// optimization: can we make these as value classes? See https://kotlinlang.org/docs/inline-classes.html

/**
 * These container classes server several purposes:
 * 1. this is where you place validation rules
 * 2. authorization rules are applied when you try to extract the value in the container
 * 3. it is possible to add labels and descriptions to containers (can be used by your UI)
 * 4. it is possible to add tags to containers. This enables authorization rules like 'Top secret facts can only be
 *    read by 2-star generals and above'. It also enables queries like 'Show me all info about user X but omit any
 *    Personally Identifiable Information (PII)'.
 * 5. they make it impossible to confuse parameters, e.g. a Username and a Password even though they are both Strings
 * 6. you can use types that adds meaning to the data.
 *      E.g. let's say you have a DistanceMeters: DoubleContainer. Instead of hoping that all parts in the code that
 *      accesses its value will treat it as meters, you can add a function like
 *          fun DistanceMeters.toMeasure(): Measure<Length> = Measure(value, meters)
 *      Code that uses this function cannot misinterpret the unit.
 * 7. you can express default values by providing a no-params constructor.
 */
public abstract class DataContainer<T> internal constructor(internal val rawValue: T) : Cloneable {

    /**
     * The read authorization of *this particular instance*.
     *
     * Containers created by application code, and the containers that live in the model cache, are readable — Klerk
     * never mutates them. A reader that enforces authorization instead hands out clones (see [copyWithAuthorization])
     * that carry the decision of that one read. This is what makes a model returned from `klerk.read { }` a stable
     * snapshot: its answers cannot be changed afterwards by somebody else reading the same model.
     *
     * The field is only written on a fresh clone, before the instance is visible to the caller, and is therefore
     * effectively final by the time anything outside Klerk can observe it.
     */
    private var authorizedToRead: Boolean = true

    private var bypassAllowed: Boolean = true

    /**
     * The value in this container, ignoring the read authorization.
     *
     * @throws AuthorizationException if this container belongs to a model returned by a read or a command result for
     * an actor other than the system, and [dev.klerkframework.klerk.KlerkSettings.allowBypassAuthRead] is false.
     */
    public val valueWithoutAuthorization: T
        get() {
            if (!bypassAllowed) {
                throw AuthorizationException(
                    KlerkErrorCode.BypassAuthReadNotAllowed,
                    "valueWithoutAuthorization is not allowed on ${this::class.simpleName}. Use value or " +
                            "valueOrNullIfNotAuthorized, or enable KlerkSettings.allowBypassAuthRead."
                )
            }
            return rawValue
        }

    /**
     * The value in this container.
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val value: T
        get() {
            if (!authorizedToRead) {
                val message = "The actor is not allowed to access ${this::class.simpleName}"
                logger.warn { message }
                throw AuthorizationException(KlerkErrorCode.UnauthorizedPropertyRead, message)
            }
            return rawValue
        }

    /**
     * Like [value], but returns null instead of throwing if the actor is not allowed to read this property.
     */
    public val valueOrNullIfNotAuthorized: T?
        get() = if (authorizedToRead) rawValue else null

    /**
     * Custom validation rules, checked after the container's built-in constraints (e.g. [StringContainer.minLength]).
     * Override to add rules like "must be even". Each function is called with the value and the current [Translation]
     * and returns [PropertyValidation.Valid] or [PropertyValidation.Invalid].
     *
     * Each must be a named function reference, e.g. `setOf(::mustBeEven)`, since its name identifies the rule in
     * messages and translations. A lambda is rejected when Klerk starts.
     *
     * The value is passed in rather than read from the container, so a rule can be a top-level function shared by
     * several containers.
     */
    public open val validators: Set<(value: T, translation: Translation) -> PropertyValidation> =
        emptySet()

    /** @throws IllegalConfigurationException if [validator] is not a named function reference */
    internal fun nameOf(validator: Function<*>): String =
        requireNamedRule(validator, "A validator of ${this::class.simpleName}")

    /**
     * Checks the built-in constraints and [validators] against the value, ignoring the read authorization.
     *
     * @param propertyName used to build the returned problem's message
     * @return null if valid, otherwise the first failing rule as an [InvalidPropertyProblem]
     */
    public abstract fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem?

    /** The first [validators] rule that rejects the value, or null if they all accept it. */
    protected fun firstInvalidValidator(propertyName: String, translation: Translation): InvalidPropertyProblem? =
        validators.firstNotNullOfOrNull { validator ->
            (validator(rawValue, translation) as? Invalid)?.let {
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        nameOf(validator),
                        it.translationInfo,
                    ),
                    propertyName = propertyName,
                )
            }
        }

    /**
     * Returns a copy of this container that carries the provided authorization. The copy is made with [clone] rather
     * than by calling a constructor, so no validation or `init` block is re-executed and containers that store the
     * value in a different representation than they were constructed from (e.g. [InstantContainer]) are handled too.
     */
    internal fun copyWithAuthorization(isAuthorized: Boolean, allowBypass: Boolean): DataContainer<T> {
        @Suppress("UNCHECKED_CAST")
        val copy = clone() as DataContainer<T>
        copy.authorizedToRead = isAuthorized
        copy.bypassAllowed = allowBypass
        return copy
    }

    /** This container without the restrictions a read put on it, e.g. when application code passes it to a command. */
    internal fun withoutReadRestrictions(): DataContainer<T> =
        if (authorizedToRead && bypassAllowed) this else copyWithAuthorization(isAuthorized = true, allowBypass = true)

    /**
     * Sets the authorization directly. Intended for tests — Klerk itself uses [copyWithAuthorization] since it must not
     * mutate containers it doesn't own.
     */
    internal fun initAuthorization(isAuthorized: Boolean) {
        this.authorizedToRead = isAuthorized
    }

    protected override fun clone(): Any = super.clone()

    /**
     * Labels usable by authorization rules and queries, e.g. to allow "read everything except properties tagged PII".
     * Not enforced by Klerk itself.
     */
    public open val tags: Set<String> = emptySet()

    /**
     * A good default value for the property, e.g. to prefill a form. Read it through
     * [dev.klerkframework.klerk.misc.SchemaField.defaultContainer]. The application developer can choose to ignore it
     * and provide a different default value.
     */
    public open val recommendedDefault: T? = null

    override fun toString(): String {
        return if (authorizedToRead) rawValue.toString() else MASKED
    }

    override fun equals(other: Any?): Boolean {
        if (other?.javaClass != this.javaClass) {
            return false
        }
        return rawValue == (other as DataContainer<*>).rawValue
    }

    override fun hashCode(): Int = rawValue.hashCode()

}

/**
 * A [DataContainer] wrapping a [String], constrained by [minLength], [maxLength], [maxLines] and optionally
 * [regexPattern].
 */
public abstract class StringContainer(value: String) : DataContainer<String>(value) {
    public abstract val minLength: Int
    public abstract val maxLength: Int
    public abstract val maxLines: Int

    /**
     * The reason why this is a String and not a Regex is that it is easy to make the mistake of creating a new Regex
     * for every object, which is inefficient (both for CPU and RAM). E.g. this would be bad as a new Regex object would
     * be created for each model:
     * ```
     * class Email(value: String) : StringContainer(value) {
     *     override val validRegexPattern = Regex("^(.+)@(\\S+)$")
     * }
     * ```
     */
    public open val regexPattern: String? = null

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(minLength >= 0) { "validLengthMin cannot be < 0" }
        check(maxLength >= minLength) { "minLength > maxLength" }
        if (rawValue.length < minLength) {
            return InvalidPropertyProblem(
                if (rawValue.isEmpty()) translation.klerk.mustBeProvided else translation.klerk.tooShort(
                    minLength
                ), propertyName
            )
        }

        if (rawValue.length > maxLength) {
            return InvalidPropertyProblem(translation.klerk.tooLong(maxLength), propertyName)
        }
        if (rawValue.lines().size > maxLines) {
            return InvalidPropertyProblem(translation.klerk.tooManyLines(maxLines), propertyName)
        }
        val regex = regexPattern
        if (regex != null && !regexPatterns.computeIfAbsent(regex) { Regex(regex) }
                .matches(rawValue)) {
            return InvalidPropertyProblem(translation.klerk.invalid, propertyName)
        }
        return firstInvalidValidator(propertyName, translation)
    }

}

// So we don't have to build a Regex every time we validate
private val regexPatterns: MutableMap<String, Regex> = mutableMapOf()

/**
 * A [DataContainer] wrapping a number, constrained to the inclusive range [min]..[max].
 *
 * Extend one of the concrete kinds ([IntContainer], [LongContainer], [ShortContainer], [ByteContainer],
 * [UIntContainer], [ULongContainer], [UShortContainer], [UByteContainer], [FloatContainer], [DoubleContainer])
 * rather than this class. Code that handles any number generically — a form, an exporter — can use [minAsText],
 * [maxAsText] and [hasDecimals] without knowing which kind it has.
 */
public abstract class NumberContainer<T : Comparable<T>> internal constructor(value: T) : DataContainer<T>(value) {

    /** The smallest allowed value, inclusive. */
    public abstract val min: T

    /** The largest allowed value, inclusive. */
    public abstract val max: T

    /** True if the value can have a fractional part, i.e. for [FloatContainer] and [DoubleContainer]. */
    public abstract val hasDecimals: Boolean

    /** [min] as text, e.g. for an HTML `min` attribute. */
    public val minAsText: String get() = min.toString()

    /** [max] as text, e.g. for an HTML `max` attribute. */
    public val maxAsText: String get() = max.toString()

    /** Lossy for values above [Long.MAX_VALUE]; only used to build messages. */
    internal abstract fun asNumber(value: T): Number

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (rawValue < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(asNumber(min)), propertyName)
        }
        if (rawValue > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(asNumber(max)), propertyName)
        }
        return firstInvalidValidator(propertyName, translation)
    }
}

/** A [DataContainer] wrapping an [Int], constrained to the inclusive range [min]..[max]. */
public abstract class IntContainer(value: Int) : NumberContainer<Int>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: Int): Number = value
}

/** A [DataContainer] wrapping a [Short], constrained to the inclusive range [min]..[max]. */
public abstract class ShortContainer(value: Short) : NumberContainer<Short>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: Short): Number = value
}

/** A [DataContainer] wrapping a [Byte], constrained to the inclusive range [min]..[max]. */
public abstract class ByteContainer(value: Byte) : NumberContainer<Byte>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: Byte): Number = value
}

/** A [DataContainer] wrapping a [Long], constrained to the inclusive range [min]..[max]. */
public abstract class LongContainer(value: Long) : NumberContainer<Long>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: Long): Number = value
}

/** A [DataContainer] wrapping a [ULong], constrained to the inclusive range [min]..[max]. */
public abstract class ULongContainer(value: ULong) : NumberContainer<ULong>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: ULong): Number = value.toDouble()
}

/** A [DataContainer] wrapping a [UInt], constrained to the inclusive range [min]..[max]. */
public abstract class UIntContainer(value: UInt) : NumberContainer<UInt>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: UInt): Number = value.toLong()
}

/** A [DataContainer] wrapping a [UShort], constrained to the inclusive range [min]..[max]. */
public abstract class UShortContainer(value: UShort) : NumberContainer<UShort>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: UShort): Number = value.toInt()
}

/** A [DataContainer] wrapping a [UByte], constrained to the inclusive range [min]..[max]. */
public abstract class UByteContainer(value: UByte) : NumberContainer<UByte>(value) {
    override val hasDecimals: Boolean = false
    override fun asNumber(value: UByte): Number = value.toInt()
}

/** A [DataContainer] wrapping a [Float], constrained to the inclusive range [min]..[max]. */
public abstract class FloatContainer(value: Float) : NumberContainer<Float>(value) {
    override val hasDecimals: Boolean = true
    override fun asNumber(value: Float): Number = value
}

/** A [DataContainer] wrapping a [Double], constrained to the inclusive range [min]..[max]. */
public abstract class DoubleContainer(value: Double) : NumberContainer<Double>(value) {
    override val hasDecimals: Boolean = true
    override fun asNumber(value: Double): Number = value
}

/**
 * A [DataContainer] wrapping an [Enum] `E`, stored as its name. Not restricted to a subset of `E`'s values by
 * default — use `validEnums` in the state machine's `event { }` block to restrict which values a given event
 * parameter accepts.
 */
public abstract class EnumContainer<E : Enum<E>>(value: E) : DataContainer<E>(value) {
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/** A [DataContainer] wrapping a [Boolean]. No built-in constraints. */
public abstract class BooleanContainer(value: Boolean) : DataContainer<Boolean>(value) {
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/**
 * A container for Instants with microsecond resolution.
 *
 * Handles years between -290308 and +294247. Instants earlier/later will be set to -290308/+294247 respectively.
 */
public abstract class InstantContainer(value: Instant) : DataContainer<Instant>(value) {
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /** `yyyy-MM-dd HH:mm:ss` in the system default time zone, or the masked placeholder if unauthorized. */
    override fun toString(): String {
        val v = valueOrNullIfNotAuthorized ?: return super.toString()
        return instantToStringFormat.format(v.toLocalDateTime(TimeZone.currentSystemDefault()))
    }
}

/**
 * A container for a calendar date, without a time of day or time zone — e.g. a contract's start date.
 */
public abstract class DateContainer(value: LocalDate) : DataContainer<LocalDate>(value) {
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /** ISO-8601 calendar date (`yyyy-MM-dd`), or the masked placeholder if unauthorized. */
    override fun toString(): String {
        val v = valueOrNullIfNotAuthorized ?: return super.toString()
        return v.toString()
    }
}

/**
 * A container for Durations with microsecond resolution.
 */
public abstract class DurationContainer(value: Duration) : DataContainer<Duration>(value) {
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /** The [Duration]'s default rendering (e.g. `1h 30m`), or the masked placeholder if unauthorized. */
    override fun toString(): String {
        val v = valueOrNullIfNotAuthorized ?: return super.toString()
        return v.toString()
    }
}

/**
 * A container for latitude and longitude.
 *
 * The precision is at least 6 decimals, which translates to sub-meter precision.
 */
public abstract class GeoPositionContainer(value: GeoPosition) : DataContainer<GeoPosition>(value) {
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/**
 * A latitude/longitude pair. Wrapped by [GeoPositionContainer] for use on a model or event parameters.
 *
 * @throws IllegalArgumentException if latitude is outside -90.0..90.0 or longitude is outside -180.0..180.0
 */
public data class GeoPosition(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90.0 and +90.0" }
        require(longitude in -180.0..180.0) { "longitude must be between -180.0 and +180.0" }
    }

    override fun toString(): String = toISO6709()

    internal constructor(uLong: ULong) : this(decodeLatitude(uLong), decodeLongitude(uLong))

    internal val uLongEncoded: ULong
        get() {
            val latitudeULong = (latitude * DOUBLE_TO_LONG_FACTOR).toInt().toUInt().toULong()
            val longitudeULong = (longitude * DOUBLE_TO_LONG_FACTOR).toInt().toUInt().toULong()
            return (latitudeULong shl 32 or longitudeULong)
        }

    /**
     * Serializes this position to an ISO 6709 string, e.g. "+48.8577+002.2950/". Altitude is omitted.
     */
    public fun toISO6709(): String {
        val lat = if (latitude >= 0) "+%09.6f".format(
            java.util.Locale.US,
            latitude
        ) else "%010.6f".format(java.util.Locale.US, latitude)
        val lon = if (longitude >= 0) "+%010.6f".format(
            java.util.Locale.US,
            longitude
        ) else "%011.6f".format(java.util.Locale.US, longitude)
        return "$lat$lon/"
    }

    public companion object {
        private const val DOUBLE_TO_LONG_FACTOR = 10000000

        private fun decodeLatitude(uLong: ULong): Double {
            return (uLong and (UInt.MAX_VALUE.toULong() shl 32) shr 32).toInt().toDouble() / DOUBLE_TO_LONG_FACTOR
        }

        private fun decodeLongitude(uLong: ULong): Double {
            return (uLong and UInt.MAX_VALUE.toULong()).toInt().toDouble() / DOUBLE_TO_LONG_FACTOR
        }

        /**
         * Deserializes a GeoPosition from an ISO 6709 string, e.g. "+48.8577+002.2950/".
         * @throws IllegalArgumentException if [iso6709] is not such a string
         */
        public fun parse(iso6709: String): GeoPosition {
            val s = iso6709.trimEnd('/')
            // Find the second sign character (+ or -) which starts the longitude
            val lonStart = s.indexOfFirst { it == '+' || it == '-' }.let { first ->
                require(first == 0) { "Invalid ISO 6709 string: $iso6709" }
                s.drop(1).indexOfFirst { it == '+' || it == '-' }.let { rel ->
                    require(rel >= 0) { "Invalid ISO 6709 string: $iso6709" }
                    rel + 1
                }
            }
            val latitude = s.substring(0, lonStart).toDouble()
            val longitude = s.substring(lonStart).toDouble()
            return GeoPosition(latitude, longitude)
        }

        /** The position in [iso6709], or null if it is not an ISO 6709 string. */
        public fun parseOrNull(iso6709: String): GeoPosition? = runCatching { parse(iso6709) }.getOrNull()
    }
}

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
     * Whether [metadata] satisfies what this property declares.
     *
     * @return null if it does, otherwise a description of what is wrong, for the command's problem.
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
        return if (accept.contains(detected)) null else "it is $detected, and ${describeAccepted()}"
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
                            "the name is what records that it has run. A lambda has no name to record."
                )
            name to step
        }
        if (named.isEmpty()) {
            throw IllegalArgumentException(
                "${this::class.simpleName} must declare at least one preAttachStep: an uploaded file is not to be " +
                        "trusted until something has looked at it (a virus scan, an EXIF strip, a re-encode). If " +
                        "this property really wants the bytes exactly as they arrived, say so explicitly with " +
                        "'override val preAttachSteps = listOf(::noPreAttachProcessing)'."
            )
        }
        val doNothing = named.filter { it.second == NO_PRE_ATTACH_PROCESSING }
        if (doNothing.isNotEmpty() && named.size > 1) {
            throw IllegalArgumentException(
                "${this::class.simpleName} declares noPreAttachProcessing together with other steps. It says that " +
                        "there is nothing to do, so it can only be the only step."
            )
        }
        named.filterNot { it.second == NO_PRE_ATTACH_PROCESSING }
    }

    /** The names of the steps that run against a value before this property may hold it, in order. */
    public val stepNames: List<String> get() = stepsToRun.map { it.first }
}

/**
 * A reference to an attached string, together with what that string is allowed to be — the string-kind counterpart
 * of [AttachedBlobContainer]. See that class for what each property means; a string has no [AttachedBlobContainer.preAttachSteps]
 * equivalent, since it is never scanned or rewritten before it is kept.
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
public class BlobPreAttachStepArgs(public val value: InputStream, public val metadata: AttachedDataMetadata)

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

/**
 * A [DataContainer] wrapping a [JobId], so that a model can hold a reference to a job it started.
 */
public class JobIdContainer(value: Long) : LongContainer(value) {

    public companion object {
        /** Wraps [id]. */
        public fun of(id: JobId): JobIdContainer = JobIdContainer(id.value)
    }

    override val min: Long = 0
    override val max: Long = Long.MAX_VALUE

    /**
     * The wrapped value as a [JobId].
     *
     * @throws dev.klerkframework.klerk.AuthorizationException if the actor that read the model is not allowed to read
     * this property.
     */
    public val jobId: JobId get() = JobId(value)
}

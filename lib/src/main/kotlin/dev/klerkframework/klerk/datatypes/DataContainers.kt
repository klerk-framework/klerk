package dev.klerkframework.klerk.datatypes

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.validation.Valid
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.validation.PropertyValidity
import dev.klerkframework.klerk.validation.PropertyValidity.Invalid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDate
import java.util.concurrent.ConcurrentHashMap
import dev.klerkframework.klerk.misc.requireNamedRule
import kotlin.time.Duration
import kotlin.time.Instant

private const val MASKED = "[••••••]"

internal val instantToStringFormat = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    day()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
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
                            "valueOrNullIfNotAuthorized, or enable KlerkSettings.allowBypassAuthRead.",
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
     * and returns [Valid] or [PropertyValidity.Invalid].
     *
     * Each must be a named function reference, e.g. `setOf(::mustBeEven)`, since its name identifies the rule in
     * messages and translations. A lambda is rejected when Klerk starts.
     *
     * The value is passed in rather than read from the container, so a rule can be a top-level function shared by
     * several containers.
     */
    public open val validators: Set<(value: T, translation: Translation) -> PropertyValidity> =
        emptySet()

    /** @throws IllegalConfigurationException if [validator] is not a named function reference */
    internal fun nameOf(validator: Function<*>): String =
        requireNamedRule(validator, "A validator of ${this::class.simpleName}")

    /**
     * Checks the built-in constraints and [validators] against the value, ignoring the read authorization.
     *
     * Returns null if valid, otherwise the first failing rule as an [InvalidPropertyProblem] whose message names
     * [propertyName].
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

    override fun toString(): String = if (authorizedToRead) rawValue.toString() else MASKED

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
    /** The shortest allowed length. */
    public abstract val minLength: Int
    /** The longest allowed length. */
    public abstract val maxLength: Int
    /** The most lines the value may have. */
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
                    minLength,
                ), propertyName,
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
private val regexPatterns: ConcurrentHashMap<String, Regex> = ConcurrentHashMap()

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
            latitude,
        ) else "%010.6f".format(java.util.Locale.US, latitude)
        val lon = if (longitude >= 0) "+%010.6f".format(
            java.util.Locale.US,
            longitude,
        ) else "%011.6f".format(java.util.Locale.US, longitude)
        return "$lat$lon/"
    }

    public companion object {
        private const val DOUBLE_TO_LONG_FACTOR = 10000000

        private fun decodeLatitude(uLong: ULong): Double =
            (uLong and (UInt.MAX_VALUE.toULong() shl 32) shr 32).toInt().toDouble() / DOUBLE_TO_LONG_FACTOR

        private fun decodeLongitude(uLong: ULong): Double =
            (uLong and UInt.MAX_VALUE.toULong()).toInt().toDouble() / DOUBLE_TO_LONG_FACTOR

        /**
         * Deserializes a GeoPosition from an ISO 6709 string, e.g. "+48.8577+002.2950/".
         * @throws IllegalArgumentException if [value] is not such a string
         */
        public fun parse(value: String): GeoPosition {
            val s = value.trimEnd('/')
            // Find the second sign character (+ or -) which starts the longitude
            val lonStart = s.indexOfFirst { it == '+' || it == '-' }.let { first ->
                require(first == 0) { "Invalid ISO 6709 string: $value" }
                s.drop(1).indexOfFirst { it == '+' || it == '-' }.let { rel ->
                    require(rel >= 0) { "Invalid ISO 6709 string: $value" }
                    rel + 1
                }
            }
            val latitude = s.substring(0, lonStart).toDouble()
            val longitude = s.substring(lonStart).toDouble()
            return GeoPosition(latitude, longitude)
        }

        /** The position in [value], or null if it is not an ISO 6709 string. */
        public fun parseOrNull(value: String): GeoPosition? = runCatching { parse(value) }.getOrNull()
    }
}

/**
 * A [DataContainer] wrapping a [JobID], so that a model can hold a reference to a job it started.
 */
public class JobIdContainer(value: Long) : LongContainer(value) {

    public companion object {
        /** Wraps [id]. */
        public fun of(id: JobID): JobIdContainer = JobIdContainer(id.value)
    }

    override val min: Long = 0
    override val max: Long = Long.MAX_VALUE

    /**
     * The wrapped value as a [JobID].
     *
     * @throws dev.klerkframework.klerk.AuthorizationException if the actor that read the model is not allowed to read
     * this property.
     */
    public val jobId: JobID get() = JobID(value)
}

package dev.klerkframework.klerk.datatypes

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.validation.PropertyValidation
import dev.klerkframework.klerk.validation.PropertyValidation.Invalid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import java.io.InputStream
import java.time.LocalDate
import kotlin.reflect.KFunction
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
public abstract class DataContainer<T>(public val valueWithoutAuthorization: T) : Cloneable {

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
            return valueWithoutAuthorization
        }

    /**
     * Like [value], but returns null instead of throwing if the actor is not allowed to read this property.
     */
    public val valueOrNullIfNotAuthorized: T?
        get() = if (authorizedToRead) valueWithoutAuthorization else null

    /**
     * Custom validation rules, checked after the container's built-in constraints (e.g. [StringContainer.minLength]).
     * Override to add rules like "must be even". Each function is called with the current [Translation] and returns
     * [PropertyValidation.Valid] or [PropertyValidation.Invalid].
     */
    public open val validators: Set<(translator: Translation) -> PropertyValidation> =
        emptySet()

    /**
     * Checks the built-in constraints and [validators] against [valueWithoutAuthorization].
     *
     * @param propertyName used to build the returned problem's message
     * @return null if valid, otherwise the first failing rule as an [InvalidPropertyProblem]
     */
    public abstract fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem?

    /**
     * Returns a copy of this container that carries the provided authorization. The copy is made with [clone] rather
     * than by calling a constructor, so no validation or `init` block is re-executed and containers that store the
     * value in a different representation than they were constructed from (e.g. [InstantContainer]) are handled too.
     */
    internal fun copyWithAuthorization(isAuthorized: Boolean): DataContainer<T> {
        @Suppress("UNCHECKED_CAST")
        val copy = clone() as DataContainer<T>
        copy.authorizedToRead = isAuthorized
        return copy
    }

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
     * This value indicates a good default value for the property. Note that the application developer can choose to ignore this value and provide
     * a different default value (e.g. when rendering a form).
     */
    public open val recommendedDefault: T? = null

    override fun toString(): String {
        return if (authorizedToRead) valueWithoutAuthorization.toString() else MASKED
    }

    override fun equals(other: Any?): Boolean {
        if (other?.javaClass != this.javaClass) {
            return false
        }
        return valueWithoutAuthorization == (other as DataContainer<*>).valueWithoutAuthorization
    }

    override fun hashCode(): Int = valueWithoutAuthorization.hashCode()
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

    public val string: String get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(minLength >= 0) { "validLengthMin cannot be < 0" }
        check(maxLength >= minLength) { "minLength > maxLength" }
        if (valueWithoutAuthorization.length < minLength) {
            return InvalidPropertyProblem(
                if (valueWithoutAuthorization.isEmpty()) translation.klerk.mustBeProvided else translation.klerk.tooShort(
                    minLength
                ), propertyName
            )
        }

        if (valueWithoutAuthorization.length > maxLength) {
            return InvalidPropertyProblem(translation.klerk.tooLong(maxLength), propertyName)
        }
        if (valueWithoutAuthorization.lines().size > maxLines) {
            return InvalidPropertyProblem(translation.klerk.tooManyLines(maxLines), propertyName)
        }
        val regex = regexPattern
        if (regex != null && !regexPatterns.computeIfAbsent(regex) { Regex(regex) }
                .matches(valueWithoutAuthorization)) {
            return InvalidPropertyProblem(translation.klerk.invalid, propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }

}

// So we don't have to build a Regex every time we validate
private val regexPatterns: MutableMap<String, Regex> = mutableMapOf()

/** A [DataContainer] wrapping an [Int], constrained to the inclusive range [min]..[max]. */
public abstract class IntContainer(value: Int) :
    DataContainer<Int>(value) {       // can we support Int stuff (e.g. newScore = score + Score(3)
    public abstract val min: Int
    public abstract val max: Int

    public val int: Int get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {

        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }

}

/** A [DataContainer] wrapping a [Short], constrained to the inclusive range [min]..[max]. */
public abstract class ShortContainer(value: Short) : DataContainer<Short>(value) {
    public abstract val min: Short
    public abstract val max: Short

    public val short: Short get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [Byte], constrained to the inclusive range [min]..[max]. */
public abstract class ByteContainer(value: Byte) : DataContainer<Byte>(value) {
    public abstract val min: Byte
    public abstract val max: Byte

    public val byte: Byte get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [Long], constrained to the inclusive range [min]..[max]. */
public abstract class LongContainer(value: Long) : DataContainer<Long>(value) {
    public abstract val min: Long
    public abstract val max: Long

    public val long: Long get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [ULong], constrained to the inclusive range [min]..[max]. */
public abstract class ULongContainer(value: ULong) : DataContainer<ULong>(value) {
    public abstract val min: ULong
    public abstract val max: ULong

    public val uLong: ULong get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min.toDouble()), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max.toDouble()), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [UInt], constrained to the inclusive range [min]..[max]. */
public abstract class UIntContainer(value: UInt) : DataContainer<UInt>(value) {
    public abstract val min: UInt
    public abstract val max: UInt

    public val uInt: UInt get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min.toLong()), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max.toLong()), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [UShort], constrained to the inclusive range [min]..[max]. */
public abstract class UShortContainer(value: UShort) : DataContainer<UShort>(value) {
    public abstract val min: UShort
    public abstract val max: UShort

    public val uShort: UShort get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min.toInt()), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max.toInt()), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [UByte], constrained to the inclusive range [min]..[max]. */
public abstract class UByteContainer(value: UByte) : DataContainer<UByte>(value) {
    public abstract val min: UByte
    public abstract val max: UByte

    public val uByte: UByte get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min.toInt()), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max.toInt()), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [Float], constrained to the inclusive range [min]..[max]. */
public abstract class FloatContainer(value: Float) : DataContainer<Float>(value) {
    public abstract val min: Float
    public abstract val max: Float

    public val float: Float get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/** A [DataContainer] wrapping a [Double], constrained to the inclusive range [min]..[max]. */
public abstract class DoubleContainer(value: Double) : DataContainer<Double>(value) {
    public abstract val min: Double
    public abstract val max: Double

    public val double: Double get() = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(max >= min) { "max < min" }
        if (valueWithoutAuthorization < min) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtLeast(min), propertyName)
        }
        if (valueWithoutAuthorization > max) {
            return InvalidPropertyProblem(translation.klerk.mustBeAtMost(max), propertyName)
        }
        return validators
            .map { Pair(it, it.invoke(translation)) }
            .filter { it.second is Invalid }
            .map { functionAndResult ->
                InvalidPropertyProblem(
                    endUserTranslatedMessage = translation.klerk.invalidProperty(
                        propertyName,
                        (functionAndResult.first as KFunction<*>).name,
                        (functionAndResult.second as Invalid).translationInfo
                    ), propertyName = propertyName
                )
            }
            .firstOrNull()
    }
}

/**
 * A [DataContainer] wrapping an [Enum] `E`, stored as its name. Not restricted to a subset of `E`'s values by
 * default — use `validEnums` in the state machine's `event { }` block to restrict which values a given event
 * parameter accepts.
 */
public abstract class EnumContainer<E : Enum<E>>(value: E) : DataContainer<String>(value.name) {
    private val enumValue: E = value

    /**
     * The enum value in this container.
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val enum: E get() { this.value; return enumValue }
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/** A [DataContainer] wrapping a [Boolean]. No built-in constraints. */
public abstract class BooleanContainer(value: Boolean) : DataContainer<Boolean>(value) {
    public val boolean: Boolean get() = value
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/**
 * A container for Instants with microsecond resolution.
 *
 * Handles years between -290308 and +294247. Instants earlier/later will be set to -290308/+294247 respectively.
 */
public abstract class InstantContainer(value: Instant) : DataContainer<Long>(value.to64bitMicroseconds()) {
    private val instantValue: Instant = value

    /**
     * The instant in this container.
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val instant: Instant get() { this.value; return instantValue }
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /** `yyyy-MM-dd HH:mm:ss` in the system default time zone, or the masked placeholder if unauthorized. */
    override fun toString(): String {
        valueOrNullIfNotAuthorized ?: return super.toString()
        return instantToStringFormat.format(instant.toLocalDateTime(TimeZone.currentSystemDefault()))
    }
}

/**
 * A container for a calendar date, without a time of day or time zone — e.g. a contract's start date.
 */
public abstract class DateContainer(value: LocalDate) : DataContainer<Int>(value.toEpochDay().toInt()) {
    private val dateValue: LocalDate = value

    /**
     * The date in this container.
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val date: LocalDate get() { this.value; return dateValue }
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /** ISO-8601 calendar date (`yyyy-MM-dd`), or the masked placeholder if unauthorized. */
    override fun toString(): String {
        valueOrNullIfNotAuthorized ?: return super.toString()
        return date.toString()
    }
}

/**
 * A container for Durations with microsecond resolution.
 */
public abstract class DurationContainer(value: Duration) : DataContainer<Long>(value.inWholeMicroseconds) {
    private val durationValue: Duration = value

    /**
     * The duration in this container.
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val duration: Duration get() { this.value; return durationValue }
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null

    /** The [Duration]'s default rendering (e.g. `1h 30m`), or the masked placeholder if unauthorized. */
    override fun toString(): String {
        valueOrNullIfNotAuthorized ?: return super.toString()
        return duration.toString()
    }
}

/**
 * A container for latitude and longitude.
 *
 * The precision is at least 6 decimals, which translates to sub-meter precision.
 */
public abstract class GeoPositionContainer(value: GeoPosition) : DataContainer<ULong>(value.uLongEncoded) {
    private val geoPositionValue: GeoPosition = value

    /**
     * The position in this container.
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val geoPosition: GeoPosition get() { this.value; return geoPositionValue }
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
         */
        public fun fromISO6709(iso6709: String): GeoPosition {
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
    }
}

/** A ready-to-use [StringContainer] for examples/tests where a real domain-specific container isn't the point. */
public class KlerkExampleDataContainer(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
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
    public fun reasonToReject(metadata: AttachedDataMetadata): String? {
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

    override val rawId: Int get() = id.id

    /**
     * What has to happen to a file before this property will hold it: looking at the bytes, and where necessary
     * rewriting them.
     *
     * ```kotlin
     * override val preAttachSteps = listOf(::scanForViruses, ::stripMacros, ::scanForViruses)
     *
     * suspend fun stripMacros(args: BlobStepArgs): BlobStepResult = BlobStepResult.Replace(disarm(args.value))
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
     * Each must be a named function reference.
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
            val name = (step as? KFunction<*>)?.name
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
    override val rawId: Int get() = id.id
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

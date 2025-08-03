package dev.klerkframework.klerk.datatypes

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.logger
import dev.klerkframework.klerk.validation.PropertyValidation
import dev.klerkframework.klerk.validation.PropertyValidation.*
import kotlinx.datetime.Instant
import kotlin.reflect.KFunction
import kotlin.time.Duration
import kotlin.toString

private const val MASKED = "[••••••]"

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
 *      E.g. let's say you have a DistanceMeters : DoubleContainer. Instead of hoping that all parts in the code that
 *      accesses its value treats it as meters, you can add a function like
 *          fun DistanceMeters.toMeasure(): Measure<Length> = Measure(value, meters)
 *      Code that uses this function cannot misinterpret the unit.
 */
public abstract class DataContainer<T>(public val valueWithoutAuthorization: T) {
    private var isAuthorizedToReadProperty: Boolean =
        true    // true until it is set by the framework, this makes unit testing simpler.

    public val value: T = valueWithoutAuthorization
        get() {
            if (!isAuthorizedToReadProperty) {
                val message = "The actor is not allowed to access ${this::class.simpleName}"
                logger.warn { message }
                throw AuthorizationException(message)
            }
            return field
        }

    public val valueOrNullIfNotAuthorized: T? =
        valueWithoutAuthorization
        get() {
            if (!isAuthorizedToReadProperty) {
                return null
            }
            return field
        }

    public open val validators: Set<(translator: Translation) -> PropertyValidation> =
        emptySet()

    public abstract fun validate(propertyName: String, context: Translation): InvalidPropertyProblem?

    internal fun initAuthorization(isAuthorized: Boolean) {
        this.isAuthorizedToReadProperty = isAuthorized
    }

    public open val tags: Set<String> = emptySet()

    override fun toString(): String {
        return try {
            value.toString()
        } catch (e: Exception) {
            MASKED
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other?.javaClass != this.javaClass) {
            return false
        }
        return valueWithoutAuthorization == (other as DataContainer<*>).valueWithoutAuthorization
    }

    override fun hashCode(): Int = valueWithoutAuthorization.hashCode()
}

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

    public val string: String = value

    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? {
        check(minLength >= 0) { "validLengthMin cannot be < 0" }
        check(maxLength >= minLength) { "minLength > maxLength" }
        if (valueWithoutAuthorization.length < minLength) {
            return InvalidPropertyProblem(if (valueWithoutAuthorization.isEmpty()) translation.klerk.mustBeProvided else translation.klerk.tooShort(minLength), propertyName)
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
            .filter { it.second is Invalid}
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

public abstract class IntContainer(value: Int) :
    DataContainer<Int>(value) {       // can we support Int stuff (e.g. newScore = score + Score(3)
    public abstract val min: Int
    public abstract val max: Int

    public val int: Int = value

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
            .filter { it.second is Invalid}
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

public abstract class LongContainer(value: Long) : DataContainer<Long>(value) {
    public abstract val min: Long
    public abstract val max: Long

    public val long: Long = value

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
            .filter { it.second is Invalid}
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

public abstract class ULongContainer(value: ULong) : DataContainer<ULong>(value) {
    public abstract val min: ULong
    public abstract val max: ULong

    public val long: ULong = value

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
            .filter { it.second is Invalid}
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

public abstract class FloatContainer(value: Float) : DataContainer<Float>(value) {
    public abstract val min: Float
    public abstract val max: Float

    public val float: Float = value

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
            .filter { it.second is Invalid}
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

public abstract class BooleanContainer(value: Boolean) : DataContainer<Boolean>(value) {
    public val boolean: Boolean = value
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/* Not supported yet. We attempted an implementation but got stuck on how auto-UI should behave. Current guess is that
we should have a validator like validReferences, i.e. validEnums(CreateAuthorParams::Popularity, PopularityEnum.entries)
so that we know what to render.
abstract class EnumContainer<T : Enum<T>>(value: Enum<T>) : DataContainer<Enum<T>>(value) {
    override fun validate(fieldName: String): InvalidParametersProblem? = null
}
 */

/**
 * A container for Instants with microsecond resolution.
 *
 * Handles years between -290308 and +294247. Instants earlier/later will be set to -290308/+294247 respectively.
 */
public abstract class InstantContainer(value: Instant) : DataContainer<Long>(value.to64bitMicroseconds()) {
    public val instant: Instant = value
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
    override fun toString(): String {
        return try {
            value.toString()
        } catch (e: Exception) {
            MASKED
        }
    }
}

public abstract class DurationContainer(value: Duration) : DataContainer<Long>(value.inWholeMicroseconds) {
    public val duration: Duration = value
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

/**
 * A container for geo positions (latitude and longitude).
 *
 * The precision is at least 6 decimals, which translates to sub-meter precision.
 */
public abstract class GeoPositionContainer(value: GeoPosition) : DataContainer<ULong>(value.uLongEncoded) {
    public val geoPosition: GeoPosition = value
    override fun validate(propertyName: String, translation: Translation): InvalidPropertyProblem? = null
}

public data class GeoPosition(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90.0 and +90.0" }
        require(longitude in -180.0..180.0) { "longitude must be between -180.0 and +180.0" }
    }

    internal constructor(uLong: ULong) : this(decodeLatitude(uLong), decodeLongitude(uLong))

    internal val uLongEncoded: ULong
        get() {
            val latitudeULong = (latitude * DOUBLE_TO_LONG_FACTOR).toInt().toUInt().toULong()
            val longitudeULong = (longitude * DOUBLE_TO_LONG_FACTOR).toInt().toUInt().toULong()
            return (latitudeULong shl 32 or longitudeULong)
        }

    internal companion object {
        private const val DOUBLE_TO_LONG_FACTOR = 10000000

        private fun decodeLatitude(uLong: ULong): Double {
            return (uLong and (UInt.MAX_VALUE.toULong() shl 32) shr 32).toInt().toDouble() / DOUBLE_TO_LONG_FACTOR
        }

        private fun decodeLongitude(uLong: ULong): Double {
            return (uLong and UInt.MAX_VALUE.toULong()).toInt().toDouble() / DOUBLE_TO_LONG_FACTOR
        }
    }
}

internal val propertiesMustInheritFrom = setOf(
    StringContainer::class,
    IntContainer::class,
    LongContainer::class,
    FloatContainer::class,
    BooleanContainer::class
)

package dev.klerkframework.klerk.datatypes

import dev.klerkframework.klerk.InvalidPropertyProblem
import dev.klerkframework.klerk.Translation

/**
 * A [DataContainer] wrapping a number, constrained to the inclusive range [min]..[max].
 *
 * Extend one of the concrete kinds ([IntContainer], [LongContainer], [ShortContainer], [ByteContainer],
 * [UIntContainer], [ULongContainer], [UShortContainer], [UByteContainer], [FloatContainer], [DoubleContainer])
 * rather than this class.
 */
public abstract class NumberContainer<T : Comparable<T>> internal constructor(value: T) : DataContainer<T>(value) {

    /** The smallest allowed value, inclusive. */
    public abstract val min: T

    /** The largest allowed value, inclusive. */
    public abstract val max: T

    /** True if the value can have a fractional part, i.e. for [FloatContainer] and [DoubleContainer]. */
    public abstract val hasDecimals: Boolean

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

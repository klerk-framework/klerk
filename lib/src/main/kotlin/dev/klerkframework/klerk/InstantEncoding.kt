package dev.klerkframework.klerk

import java.math.BigInteger
import kotlin.time.Instant

/**
 * Returns microseconds since 1970.
 * It only works for instants between years -290308 and +294247.
 */
internal fun Instant.to64bitMicroseconds(): Long {
    if (this <= KLERK_INSTANT_MIN) return Long.MIN_VALUE
    if (this >= KLERK_INSTANT_MAX) return Long.MAX_VALUE
    return BigInteger.valueOf(this.epochSeconds).multiply(ONE_MILLION)
        .plus(BigInteger.valueOf(this.nanosecondsOfSecond.toLong()).divide(ONE_THOUSAND))
        .toLong()
}

private val KLERK_INSTANT_MIN = decode64bitMicroseconds(Long.MIN_VALUE)

private val KLERK_INSTANT_MAX = decode64bitMicroseconds(Long.MAX_VALUE)

private val ONE_MILLION = BigInteger.valueOf(1000000)

private val ONE_THOUSAND = BigInteger.valueOf(1000)

internal fun decode64bitMicroseconds(microsecondsSince1970: Long): Instant =
    Instant.fromEpochSeconds(
        Math.floorDiv(microsecondsSince1970, 1_000_000L),
        Math.floorMod(microsecondsSince1970, 1_000_000L) * 1000,
    )

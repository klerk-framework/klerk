package dev.klerkframework.klerk.misc

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [Clock] a test can move by hand, for use with `KlerkSettings.clock`.
 *
 * Everything background in Klerk — job scheduling, retry backoff, cron, delay-based admission, state-machine time
 * triggers — reads its time from the configured clock, so advancing this is how a test travels in time without
 * sleeping:
 *
 * ```
 * val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
 * val settings = KlerkSettings(
 *     persistence = RamStorage(),
 *     clock = clock,
 *     jobs = JobSettings(execution = JobExecution.Manual),
 * )
 *
 * clock += 1.hours
 * klerk.jobs.runUntilIdle()
 * ```
 *
 * Safe to move from any thread.
 */
public class MutableClock(initial: Instant) : Clock {

    private val current = AtomicReference(initial)

    override fun now(): Instant = current.get()

    /** Moves the clock to [instant]. Moving backwards is allowed, but little in Klerk expects it. */
    public fun set(instant: Instant) {
        current.set(instant)
    }

    /** Moves the clock forward by [duration] and returns the new time. */
    public fun advance(duration: Duration): Instant = current.updateAndGet { it.plus(duration) }

    public operator fun plusAssign(duration: Duration) {
        advance(duration)
    }

    override fun toString(): String = "MutableClock(${current.get()})"
}

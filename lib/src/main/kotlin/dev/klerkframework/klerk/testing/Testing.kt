package dev.klerkframework.klerk.testing

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.JobManager
import dev.klerkframework.klerk.JobManagerInternal

/**
 * Runs exactly one step, if any job is ready. Returns true if a step ran, false if there was nothing to do.
 *
 * @throws IllegalStateException if execution is [dev.klerkframework.klerk.job.JobExecution.Automatic].
 */
public suspend fun <C : KlerkContext, V> JobManager<C, V>.step(): Boolean =
    (this as JobManagerInternal<C, V>).step()

/**
 * Runs steps until no job is ready any more, and returns how many ran. [maxSteps] is a safety net against a job that
 * yields forever.
 *
 * Jobs waiting for a `scheduleAt` or a backoff that has not arrived on the configured clock are *not* ready, so
 * this returns rather than spinning — advance a [dev.klerkframework.klerk.misc.MutableClock] and call it again.
 *
 * @throws IllegalStateException if execution is [dev.klerkframework.klerk.job.JobExecution.Automatic], or if
 * [maxSteps] was reached (which means a test would otherwise have hung).
 */
public suspend fun <C : KlerkContext, V> JobManager<C, V>.runUntilIdle(maxSteps: Int = 10_000): Int =
    (this as JobManagerInternal<C, V>).runUntilIdle(maxSteps)

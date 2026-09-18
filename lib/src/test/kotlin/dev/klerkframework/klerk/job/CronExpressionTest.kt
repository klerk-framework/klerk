package dev.klerkframework.klerk.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class CronExpressionTest {

    private fun next(expression: String, from: String): String? =
        CronExpression.parse(expression).nextAfter(Instant.parse(from))?.toString()

    @Test
    fun `every minute`() {
        assertEquals("2026-01-01T00:01:00Z", next("* * * * *", "2026-01-01T00:00:30Z"))
    }

    @Test
    fun `a fire is never returned twice`() {
        // Asking again from the instant just returned must move on, or a catch-up loop would spin forever.
        assertEquals("2026-01-01T00:02:00Z", next("* * * * *", "2026-01-01T00:01:00Z"))
    }

    @Test
    fun `daily at three`() {
        assertEquals("2026-01-01T03:00:00Z", next("0 3 * * *", "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-02T03:00:00Z", next("0 3 * * *", "2026-01-01T03:00:00Z"))
    }

    @Test
    fun `steps and lists`() {
        assertEquals("2026-01-01T00:15:00Z", next("0,15,30,45 * * * *", "2026-01-01T00:01:00Z"))
        assertEquals("2026-01-01T00:15:00Z", next("*/15 * * * *", "2026-01-01T00:01:00Z"))
        assertEquals("2026-01-01T02:00:00Z", next("0 */2 * * *", "2026-01-01T00:30:00Z"))
    }

    @Test
    fun `ranges`() {
        assertEquals("2026-01-01T09:00:00Z", next("0 9-17 * * *", "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-01T10:00:00Z", next("0 9-17 * * *", "2026-01-01T09:00:00Z"))
        assertEquals("2026-01-02T09:00:00Z", next("0 9-17 * * *", "2026-01-01T17:00:00Z"))
    }

    @Test
    fun `day of week is Sunday-based, and 7 also means Sunday`() {
        // 2026-01-01 is a Thursday, so the next Monday is the 5th.
        assertEquals("2026-01-05T00:00:00Z", next("0 0 * * 1", "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-04T00:00:00Z", next("0 0 * * 0", "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-04T00:00:00Z", next("0 0 * * 7", "2026-01-01T00:00:00Z"))
    }

    @Test
    fun `day-of-month and day-of-week are ORed when both are restricted`() {
        // The 10th, or any Monday — standard cron's one genuinely surprising rule.
        assertEquals("2026-01-05T00:00:00Z", next("0 0 10 * 1", "2026-01-01T00:00:00Z"))
        assertEquals("2026-01-10T00:00:00Z", next("0 0 10 * 1", "2026-01-05T00:00:00Z"))
    }

    @Test
    fun `an expression that can never match returns null rather than looping`() {
        assertNull(next("0 0 30 2 *", "2026-01-01T00:00:00Z"))
    }

    @Test
    fun `occurrences between two instants, oldest first`() {
        val cron = CronExpression.parse("0 * * * *")
        val missed = cron.occurrencesBetween(
            Instant.parse("2026-01-01T00:30:00Z"),
            Instant.parse("2026-01-01T04:30:00Z"),
            limit = 100,
        )
        assertEquals(4, missed.size)
        assertEquals("2026-01-01T01:00:00Z", missed.first().toString())
        assertEquals("2026-01-01T04:00:00Z", missed.last().toString())
    }

    @Test
    fun `invalid expressions are rejected at parse time`() {
        assertFailsWith<IllegalArgumentException> { CronExpression.parse("* * * *") }
        assertFailsWith<IllegalArgumentException> { CronExpression.parse("60 * * * *") }
        assertFailsWith<IllegalArgumentException> { CronExpression.parse("0 25 * * *") }
        assertFailsWith<IllegalArgumentException> { CronExpression.parse("0 0 * * 8") }
        assertFailsWith<IllegalArgumentException> { CronExpression.parse("*/0 * * * *") }
        assertFailsWith<IllegalArgumentException> { CronExpression.parse("17-3 * * * *") }
    }
}

package dev.klerkframework.klerk.job

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Instant

/**
 * A parsed five-field cron expression, evaluated in **UTC**.
 *
 * `minute hour day-of-month month day-of-week`, where each field is a wildcard, a single value, an `a-b` range, a
 * `base/step` (`&#42;&#47;15` meaning "every 15"), or a comma-separated list of those. Day-of-week is 0–6 with
 * 0 = Sunday; 7 is also accepted for Sunday.
 *
 * As in standard cron, when *both* day-of-month and day-of-week are restricted a day matches if **either** matches;
 * when only one is restricted, only that one is consulted.
 *
 * There is deliberately no timezone support — see [CronSchedule].
 */
internal class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val dayOfMonthRestricted: Boolean,
    private val dayOfWeekRestricted: Boolean,
    private val expression: String,
) {

    /**
     * The first occurrence strictly after [after], or null if there is none within the next four years (which only
     * happens for an expression such as `0 0 30 2 *` that can never match).
     */
    fun nextAfter(after: Instant): Instant? {
        // Start at the beginning of the minute after `after`, so a fire is never returned twice.
        var candidate = after.toUtcDateTime().withSecond(0).withNano(0).plusMinutes(1)
        val limit = candidate.plusYears(SEARCH_YEARS)
        while (candidate.isBefore(limit)) {
            if (!matchesMonth(candidate)) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0)
                continue
            }
            if (!matchesDay(candidate)) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0)
                continue
            }
            if (!hours.contains(candidate.hour)) {
                candidate = candidate.plusHours(1).withMinute(0)
                continue
            }
            if (!minutes.contains(candidate.minute)) {
                candidate = candidate.plusMinutes(1)
                continue
            }
            return candidate.toInstant()
        }
        return null
    }

    /**
     * Every occurrence in `(after, until]`, oldest first, at most [limit] of them. Used to work out what was missed
     * while the node was down.
     */
    fun occurrencesBetween(after: Instant, until: Instant, limit: Int): List<Instant> {
        val result = mutableListOf<Instant>()
        var cursor = after
        while (result.size < limit) {
            val next = nextAfter(cursor) ?: break
            if (next > until) {
                break
            }
            result.add(next)
            cursor = next
        }
        return result
    }

    private fun matchesMonth(t: LocalDateTime): Boolean = months.contains(t.monthValue)

    private fun matchesDay(t: LocalDateTime): Boolean {
        // java.time's DayOfWeek is 1 = Monday .. 7 = Sunday; cron's is 0 = Sunday .. 6 = Saturday.
        val cronDayOfWeek = t.dayOfWeek.value % 7
        val byDayOfMonth = daysOfMonth.contains(t.dayOfMonth)
        val byDayOfWeek = daysOfWeek.contains(cronDayOfWeek)
        return when {
            dayOfMonthRestricted && dayOfWeekRestricted -> byDayOfMonth || byDayOfWeek
            dayOfMonthRestricted -> byDayOfMonth
            dayOfWeekRestricted -> byDayOfWeek
            else -> true
        }
    }

    override fun toString(): String = expression

    companion object {

        private const val SEARCH_YEARS = 4L

        /**
         * @throws IllegalArgumentException if [expression] is not a valid five-field cron expression.
         */
        fun parse(expression: String): CronExpression {
            val fields = expression.trim().split(Regex("\\s+"))
            require(fields.size == 5) {
                "A cron expression must have five fields (minute hour day-of-month month day-of-week), " +
                        "but '$expression' has ${fields.size}"
            }
            val (minute, hour, dayOfMonth, month, dayOfWeek) = fields
            return CronExpression(
                minutes = parseField(minute, 0, 59, expression, "minute"),
                hours = parseField(hour, 0, 23, expression, "hour"),
                daysOfMonth = parseField(dayOfMonth, 1, 31, expression, "day-of-month"),
                months = parseField(month, 1, 12, expression, "month"),
                daysOfWeek = parseField(dayOfWeek, 0, 7, expression, "day-of-week").map { it % 7 }.toSet(),
                dayOfMonthRestricted = dayOfMonth != "*",
                dayOfWeekRestricted = dayOfWeek != "*",
                expression = expression.trim(),
            )
        }

        private fun parseField(field: String, min: Int, max: Int, expression: String, name: String): Set<Int> {
            require(field.isNotBlank()) { "The $name field of the cron expression '$expression' is empty" }
            val values = field.split(",").flatMap { parseTerm(it, min, max, expression, name) }.toSortedSet()
            require(values.isNotEmpty()) { "The $name field of the cron expression '$expression' matches nothing" }
            return values
        }

        private fun parseTerm(term: String, min: Int, max: Int, expression: String, name: String): List<Int> {
            val (rangePart, step) = when {
                term.contains("/") -> {
                    val parts = term.split("/")
                    require(parts.size == 2) { "Invalid $name term '$term' in the cron expression '$expression'" }
                    val stepValue = parts[1].toIntOrNull()
                    require(stepValue != null && stepValue > 0) {
                        "The step in the $name term '$term' of the cron expression '$expression' must be a positive " +
                            "number"
                    }
                    parts[0] to stepValue
                }

                else -> term to 1
            }

            val range = when {
                rangePart == "*" -> min..max
                rangePart.contains("-") -> {
                    val bounds = rangePart.split("-")
                    require(bounds.size == 2) {
                        "Invalid $name range '$rangePart' in the cron expression '$expression'"
                    }
                    val from = bounds[0].toValue(min, max, expression, name)
                    val to = bounds[1].toValue(min, max, expression, name)
                    require(from <= to) {
                        "The $name range '$rangePart' in the cron expression '$expression' is backwards"
                    }
                    from..to
                }

                else -> {
                    val single = rangePart.toValue(min, max, expression, name)
                    // 'n/step' means "from n to the end of the field, every step" — the same as cron's 'n-max/step'.
                    if (step == 1) single..single else single..max
                }
            }
            return range.step(step).toList()
        }

        private fun String.toValue(min: Int, max: Int, expression: String, name: String): Int {
            val value = trim().toIntOrNull()
            require(value != null && value in min..max) {
                "'$this' is not a valid $name (expected $min-$max) in the cron expression '$expression'"
            }
            return value
        }
    }
}

private fun Instant.toUtcDateTime(): LocalDateTime =
    LocalDateTime.ofEpochSecond(epochSeconds, nanosecondsOfSecond, ZoneOffset.UTC)

private fun LocalDateTime.toInstant(): Instant =
    Instant.fromEpochSeconds(toEpochSecond(ZoneOffset.UTC), nano)

package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import kotlin.time.Duration

/** What to do with cron fires that were missed because the node was down. */
public enum class CatchUp {
    /**
     * Fire once on recovery, however many fires were missed. The default, because it is what people mean by "the
     * nightly cleanup should still run".
     */
    RunOnce,

    /** Fire once for every missed occurrence. */
    RunAll,

    /** Fire none; wait for the next scheduled occurrence. */
    Skip,
}

/** What to do when a cron fires while the previous run of the same schedule is still going. */
public enum class Overlap {
    /** Don't fire. The default. */
    Skip,

    /** Fire once the previous run is terminal. */
    Queue,

    /** Fire anyway, so two runs are in flight at once. */
    Allow,
}

/**
 * A recurring schedule, declared statically in specification next to the job type it runs.
 *
 * Times are **UTC**, deliberately: a daily 02:30 local job does not exist on the spring-forward day and happens twice
 * in autumn, and there is no answer to that which is correct for everyone.
 */
public class CronSchedule<C : KlerkContext, V> internal constructor(
    /** The job type that is run. */
    public val type: JobType<*, C, V>,
    /** The cron expression, as declared. */
    public val expression: String,
    internal val parsed: CronExpression,
    internal val encodedCursor: String,
    /** What happens to occurrences missed while the application was down. */
    public val catchUp: CatchUp,
    /** What happens when an occurrence is due while the previous run is still going. */
    public val overlap: Overlap,
    /** The largest random delay added to each occurrence. */
    public val jitter: Duration,
) {
    /** Stable identity of this schedule, used as the key for the persisted last-fired time. */
    public val id: String = "${type.name.value}|$expression"

    override fun toString(): String = "cron(${type.name.value}, '$expression')"
}

/** Builder for the optional settings of a [CronSchedule]. */
@JobsSpecificationMarker
public class CronBuilder<Cursor : Any> internal constructor(private val defaultCursor: Cursor?) {
    /** What to do about fires missed while the node was down. */
    public var catchUp: CatchUp = CatchUp.RunOnce

    /** What to do when the previous run is still going. Distinct from the job type's `maxConcurrent`. */
    public var overlap: Overlap = Overlap.Skip

    /** Spreads the fire time over a random window, to avoid a thundering herd. */
    public var jitter: Duration = Duration.ZERO

    /** The cursor each fire starts with. Required unless the job type's cursor has a no-argument constructor. */
    public var cursor: Cursor? = defaultCursor

    internal fun require(): Cursor =
        requireNotNull(cursor) { "A cron schedule must declare the 'cursor' each fire starts with" }
}

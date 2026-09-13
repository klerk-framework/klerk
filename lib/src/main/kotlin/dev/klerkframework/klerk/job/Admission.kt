package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.StateProblem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What an admission policy decides about a piece of **new** work.
 *
 * Yields, retries, spawned children, end-of-life hooks and anything already accepted never go through admission.
 */
public sealed interface AdmissionDecision {

    /** Accept the job as scheduled. */
    public data object Allow : AdmissionDecision

    /**
     * Accept the job, but in a lower class.
     *
     * **This is the preferred way to degrade.** The best answer to load is almost always "accept this as `Bulk`", not
     * "fail the user's checkout".
     */
    public data class Downgrade(val priority: JobPriority) : AdmissionDecision

    /** Accept the job but hold it back until [until]. */
    public data class Delay(val until: Instant) : AdmissionDecision

    /**
     * Refuse the job, failing the command that scheduled it with [problem]. A last resort — see [Downgrade].
     */
    public data class Deny(val problem: Problem) : AdmissionDecision {
        public companion object {
            /** The standard refusal, carrying [KlerkErrorCode.JobQueueOverloaded]. */
            public fun overloaded(message: String): Deny = Deny(
                StateProblem(
                    "The system is busy. Please try again shortly.",
                    message,
                    KlerkErrorCode.JobQueueOverloaded,
                )
            )
        }
    }
}

/**
 * A read-only snapshot of the job queue, handed to the admission policy.
 *
 * Everything here is already computed, so a policy can be a cheap pure function — which it has to be, because it runs
 * inside command processing on the single writer.
 */
public class JobQueueSnapshot internal constructor(
    private val oldestReady: Map<JobPriority, Instant>,
    private val depths: Map<JobPriority, Int>,
    private val running: Map<JobPriority, Int>,
    private val overBudgetSince: Map<JobPriority, Instant>,
    /** The number of non-terminal jobs in the system. */
    public val total: Int,
    /** The hard cap on non-terminal jobs. Not overridable by a policy. */
    public val hardLimit: Int,
    private val now: Instant,
) {
    /** How long the oldest job in [priority] that is ready but not yet running has been waiting. Zero if none. */
    public fun oldestReadyAge(priority: JobPriority): Duration =
        oldestReady[priority]?.let { now - it } ?: Duration.ZERO

    /** How many jobs of [priority] are queued (scheduled, ready, backing off or waiting). */
    public fun depth(priority: JobPriority): Int = depths[priority] ?: 0

    /** How many jobs of [priority] are running a step right now. */
    public fun running(priority: JobPriority): Int = running[priority] ?: 0

    /**
     * Since when [priority] has been continuously over its delay budget, or null if it is not over budget.
     *
     * This is what lets a policy apply hysteresis without keeping state of its own: shed only once a class has been
     * over budget for a while, so a single slow step does not start refusing work.
     */
    public fun overBudgetSince(priority: JobPriority): Instant? = overBudgetSince[priority]

    /**
     * How long a job of [priority] may sit ready before the class counts as over budget.
     *
     * Always [AdmissionPolicy.defaultBudgets]: the budgets are Klerk's, not the policy's, and it is these that
     * [overBudgetSince] is computed from. A policy that wants different thresholds compares [oldestReadyAge] against
     * its own instead.
     */
    public fun budget(priority: JobPriority): Duration = AdmissionPolicy.defaultBudgets.getValue(priority)
}

/** The job being considered for admission. */
public class JobCandidate internal constructor(
    public val name: JobName,
    public val priority: JobPriority,
    public val scheduleAt: Instant?,
)

/**
 * The arguments handed to an admission policy.
 *
 * @property context the scheduling actor's context. Note that the policy runs on the single writer inside command
 * processing: **do no IO here**, a database lookup makes every command in the system slower.
 */
public class AdmissionArgs<C : KlerkContext> internal constructor(
    public val queue: JobQueueSnapshot,
    public val job: JobCandidate,
    public val context: C,
    public val now: Instant,
)

/**
 * The built-in admission policies.
 *
 * Admission is **delay-based, not depth-based**. Queue depth is a poor signal: one entry may be a one-step webhook and
 * another a 500-step import, and a deep queue that is draining fast is healthier than a shallow one that has not moved
 * in ten minutes. Waiting time captures both.
 */
public object AdmissionPolicy {

    /** How long a job of each class may sit ready before its class counts as over budget. */
    public val defaultBudgets: Map<JobPriority, Duration> = mapOf(
        JobPriority.Interactive to 5.seconds,
        JobPriority.High to 1.minutes,
        JobPriority.Normal to 10.minutes,
        JobPriority.Bulk to 2.hours,
    )

    /**
     * How long a class must be *continuously* over budget before this policy starts shedding it. Without this, one
     * slow step would make the system flap between accepting and refusing.
     */
    public val hysteresis: Duration = 10.seconds

    /**
     * The default policy: shed a class once its oldest ready job has exceeded the class budget continuously for
     * [hysteresis].
     *
     * Shedding means downgrading to the next class down, not refusing — a job that is not [JobPriority.Bulk] is
     * accepted as one class lower. Only `Bulk` itself, the bottom of the ladder, is refused, and only when `Bulk` has
     * been over its two-hour budget for that long, which means the system has genuinely stopped draining.
     */
    public fun <C : KlerkContext> delayBudget(args: AdmissionArgs<C>): AdmissionDecision {
        val priority = args.job.priority
        val since = args.queue.overBudgetSince(priority) ?: return AdmissionDecision.Allow
        if (args.now - since < hysteresis) {
            return AdmissionDecision.Allow
        }
        val next = priority.oneClassLower()
            ?: return AdmissionDecision.Deny.overloaded(
                "The ${priority.name} job queue has been over its ${args.queue.budget(priority)} budget since $since"
            )
        return AdmissionDecision.Downgrade(next)
    }

    /** Accepts everything. The hard queue cap still applies. */
    public fun <C : KlerkContext> allowAll(args: AdmissionArgs<C>): AdmissionDecision = AdmissionDecision.Allow

    private fun JobPriority.oneClassLower(): JobPriority? =
        JobPriority.entries.getOrNull(ordinal + 1)
}

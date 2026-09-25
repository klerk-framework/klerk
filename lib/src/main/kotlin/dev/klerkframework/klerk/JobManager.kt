package dev.klerkframework.klerk

import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.job.JobOperation
import dev.klerkframework.klerk.job.PendingJob
import dev.klerkframework.klerk.storage.spi.JobCommit
import dev.klerkframework.klerk.storage.spi.JobRecord
import kotlinx.coroutines.flow.Flow

/**
 * Reads job state from inside a read block, as [dev.klerkframework.klerk.read.Reader.jobs].
 *
 * What is returned is part of the block's snapshot, exactly like a model: no command and no job step can change it
 * while the block runs, so reading the same job twice always gives the same answer. Use [JobManager] instead outside
 * a read block — its methods take the read lock themselves and refuse to run inside one.
 */
public interface JobReader {

    /**
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see it.
     */
    public fun get(id: JobID): JobInfo

    /** Null if there is no such job, or the actor is not allowed to see it. */
    public fun getOrNull(id: JobID): JobInfo?

    /** Every job the actor is allowed to see, newest first. */
    public fun all(): List<JobInfo>
}

/**
 * Schedules, inspects and controls managed background jobs. See the "Jobs" documentation for the whole model.
 */
public interface JobManager<C : KlerkContext, V> {

    /**
     * Schedules one job, built with [JobType.declare], and returns its id. The actor in [context] is recorded as the
     * job's owner, and is what the `readJobs` and `controlJobs` rules see.
     *
     * This is the way to schedule a job that no command is responsible for. A job that belongs to a command should be
     * returned from a state machine's `job(...)` executable instead, so that it is persisted in that command's own
     * transaction and is not scheduled at all if the command fails.
     *
     * New work goes through admission control, so this can fail when the queue is not draining — see
     * [KlerkErrorCode.JobQueueOverloaded]. Yields, retries, spawned children and end-of-life hooks never do.
     *
     * @throws JobRejectedException if the job was refused by the admission policy or the hard queue cap.
     */
    public suspend fun schedule(job: DeclaredJob<C, V>, context: C): JobID

    /**
     * Everything known about one job.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the actor is not allowed to see it.
     */
    public suspend fun get(id: JobID, context: C): JobInfo

    /** Every job the actor is allowed to see, newest first. */
    public suspend fun all(context: C): List<JobInfo>

    /**
     * Emits a [JobInfo] every time a job the actor may see changes — for a live progress bar. If [id] is given, only
     * that job's changes are emitted.
     */
    public fun subscribe(id: JobID?, context: C): Flow<JobInfo>

    /**
     * Requests cancellation, and returns as soon as the request has been recorded.
     *
     * The job moves to [dev.klerkframework.klerk.job.JobStatus.Cancelling] and reaches
     * [dev.klerkframework.klerk.job.JobStatus.Cancelled] only once the in-flight step has returned, every descendant
     * is terminal and `onCancelled` has finished. **Cancel latency is therefore the slowest step in the subtree**, so
     * a UI should render `Cancelling` as its own state rather than a button that appears to do nothing.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the `controlJobs` rules do not allow the actor to cancel the job.
     */
    public suspend fun cancel(id: JobID, context: C, reason: String = "Cancelled")

    /**
     * Puts a dead-lettered job back in the queue, **resuming from its checkpoint** — never restarting from step 0,
     * because the commands from steps 1..n have already been applied and Klerk cannot recognise re-emitted ones.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the `controlJobs` rules do not allow the actor to resume the job.
     * @throws IllegalStateException if the job is not dead-lettered.
     */
    public suspend fun resume(id: JobID, context: C)

    /**
     * Deletes a terminal job, releasing any claim it holds on attached data. Data that a committed command attached to
     * a live model is never affected.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     * @throws AuthorizationException if the `controlJobs` rules do not allow the actor to delete the job.
     * @throws IllegalStateException if the job has not reached a terminal status.
     */
    public suspend fun delete(id: JobID, context: C)

    /**
     * True if the `controlJobs` rules allow the actor to perform [operation] on the job, e.g. to decide whether to show
     * a button. Does not check whether the job's status allows it.
     *
     * @throws kotlin.NoSuchElementException if there is no job with this id.
     */
    public suspend fun isAllowed(id: JobID, operation: JobOperation, context: C): Boolean
}

internal interface JobManagerInternal<C : KlerkContext, V> : JobManager<C, V> {

    /** Runs one step. Exposed to applications as `dev.klerkframework.klerk.testing.step`. */
    suspend fun step(): Boolean

    /** Runs steps until idle. Exposed to applications as `dev.klerkframework.klerk.testing.runUntilIdle`. */
    suspend fun runUntilIdle(maxSteps: Int = 10_000): Int

    /** True if no job is using this id. Used while allocating ids during command processing. */
    fun isJobIdAvailable(id: Long): Boolean

    /**
     * Turns the jobs a command declared into rows to write, applying admission control. Called on the command path
     * before anything is committed, so that a refusal can still fail the command.
     */
    fun planNewJobs(pending: List<PendingJob<C, V>>, context: C): NewJobPlan

    /**
     * Applies a committed job commit to the in-memory queue.
     *
     * **Must be called while holding the write lock, and must never take the job manager's own mutex** — job state is
     * part of what a read block sees, so it has to flip in the same critical section as models and views, and taking
     * the mutex underneath the write lock would invert the lock order and deadlock.
     */
    fun applyToMemory(commit: JobCommit)

    /**
     * Tells subscribers and the dispatcher about rows [applyToMemory] has already applied.
     *
     * **Must be called after the write lock is released.** Emitting can resume a collector inline, and a collector
     * reading job state takes the read lock — which would deadlock against the writer emitting to it.
     */
    fun notifyCommitted(commit: JobCommit)

    /**
     * [JobManager.schedule], with the new job claiming [claim] in the same commit.
     *
     * Used when the job exists to work on attached data that nothing references yet: without the claim in the same
     * transaction, the orphan reaper could take the value between the two writes.
     */
    suspend fun scheduleClaiming(job: DeclaredJob<C, V>, context: C, claim: Set<Int>): JobID
}

/** What [JobManagerInternal.planNewJobs] decided: either rows to write, or the problems that must fail the command. */
internal sealed class NewJobPlan {
    data class Ok(val records: List<JobRecord>) : NewJobPlan() {
        val commit: JobCommit get() = JobCommit(upserted = records)
    }

    data class Rejected(val problems: List<Problem>) : NewJobPlan()
}

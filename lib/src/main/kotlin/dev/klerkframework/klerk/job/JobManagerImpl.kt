package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.ActorType
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.InternalProblem
import dev.klerkframework.klerk.JobContextRequest
import dev.klerkframework.klerk.JobManagerInternal
import dev.klerkframework.klerk.JobReader
import dev.klerkframework.klerk.JobRejectedException
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.NewJobPlan
import dev.klerkframework.klerk.PluginIdentity
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.StateProblem
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.read.AuthorizingJobReader
import dev.klerkframework.klerk.read.ReadBlockGuard
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.CommitBatch
import dev.klerkframework.klerk.storage.spi.JobCommit
import dev.klerkframework.klerk.storage.spi.JobRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Marks the coroutine a job step runs in, so that attached data prepared during the step can be claimed by that job
 * without the job author having to say so.
 *
 * A step often prepares data before any command references it; without a claim, the orphan reaper would delete it a
 * minute later. See [dev.klerkframework.klerk.KlerkAttachedData].
 */
internal class RunningJobElement(val jobId: JobID) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunningJobElement>
}

/** The id of the job whose step is running in the current coroutine, or null if none is. */
internal suspend fun currentJobId(): JobID? = coroutineContext[RunningJobElement]?.jobId

/**
 * The scheduler.
 *
 * The whole in-memory picture of every non-deleted job lives here, mirroring what is persisted. That is what makes the
 * queue snapshot the admission policy needs a pure lookup rather than a query, and it is affordable because the number
 * of live jobs is bounded by [JobSettings.hardQueueLimit].
 *
 * Klerk is single-writer, so there is exactly one of these and it is permanently the master.
 */
internal class JobManagerImpl<C : KlerkContext, V>(private val klerk: KlerkImpl<C, V>) : JobManagerInternal<C, V> {

    private val specification get() = klerk.specification
    private val jobSpec get() = specification.jobs
    private val jobSettings get() = klerk.settings.jobs

    /**
     * Serializes the *compound* updates — claiming a job, applying a commit — so that two of them cannot interleave.
     * Never held across a call into application code.
     *
     * The collections below are concurrent regardless, because they are also read without this lock: `get`,
     * `all` and the admission policy all run on caller threads while the dispatcher is stepping.
     */
    private val lock = Mutex()
    private val records = ConcurrentHashMap<JobID, JobRecord>()
    private val running = ConcurrentHashMap.newKeySet<JobID>()

    /**
     * Ids [allocateId] has handed out that are not in [records] yet.
     *
     * An id only reaches [records] when its commit lands, which is many steps after it was chosen, so the choice
     * needs somewhere else to be recorded meanwhile or two allocations pick the same free id. Holding [lock] across
     * the choice does not help: the window is between choosing and committing, not inside [allocateId].
     */
    private val reservedIds = ConcurrentHashMap.newKeySet<JobID>()
    private val overBudgetSince = ConcurrentHashMap<JobPriority, Instant>()

    /** Position in the dispatch queue, oldest first. See [updateQueueOrder] for why `readyAt` alone will not do. */
    private val queueOrder = ConcurrentHashMap<JobID, Long>()
    private var queueCounter = 0L

    /**
     * The outcome of the command each job's previous step emitted. Deliberately in memory only: a `CommandResult`
     * cannot be faithfully reconstructed from storage, and inventing a lossy stand-in would be worse than being
     * explicit that it is gone. A step that must know the outcome after a restart should record what it needs in its
     * own cursor.
     */
    private val previousResults = ConcurrentHashMap<JobID, CommandResult<*>>()

    private val random = SecureRandom()

    /**
     * Where [allocateId] draws candidates from. Overridable only so that a test can shrink the space to where
     * collisions are likely — over the full range they never happen, so a concurrency test against it proves nothing.
     */
    internal var idCandidates: () -> Long = { random.nextLong(Long.MAX_VALUE) }
    private val changes = MutableSharedFlow<JobRecord>(extraBufferCapacity = 256)
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    private var scope: CoroutineScope? = null
    private var dispatcher: Job? = null

    @Volatile
    private var started = false

    @Volatile
    private var stopping = false

    // ------------------------------------------------------------------ lifecycle

    /**
     * Reloads every persisted job, resolves it against the registry, and starts dispatching.
     *
     * Every cursor is decoded here rather than lazily, so a deploy that changed a cursor type is caught at startup —
     * loudly, once — instead of on whichever step happens to run first.
     */
    suspend fun start() {
        check(!started) { "The job manager has already been started" }
        started = true

        val now = klerk.settings.now()
        val unloadable = mutableListOf<Pair<JobRecord, String>>()
        for (record in klerk.settings.persistence.allJobs()) {
            when (val problem = whyUnloadable(record)) {
                null -> records[record.id] = recoverAfterRestart(record, now)
                else -> unloadable.add(record to problem)
            }
        }
        handleUnloadable(unloadable, now)
        initialiseCronState(now)
        logger.info { "Jobs ready (${records.size} jobs, ${jobSpec.allTypes.size} registered types)" }

        if (jobSettings.execution == JobExecution.Automatic) {
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = newScope
            dispatcher = newScope.launch { dispatchLoop() }
        }
    }

    /**
     * Stops dispatching, lets the steps that are already running finish and commit their checkpoints, and returns.
     *
     * Bounded: a step that will not return within [STOP_TIMEOUT] is abandoned. It has not committed anything, so on
     * the next start its job simply runs that step again.
     */
    suspend fun stop() {
        if (!started || stopping) {
            return
        }
        stopping = true
        wakeup.trySend(Unit)
        val currentScope = scope ?: return
        dispatcher?.cancelAndJoin()
        withTimeoutOrNull(STOP_TIMEOUT) {
            currentScope.coroutineContext.job.children.toList().joinAll()
        } ?: logger.warn { "Gave up waiting for job steps to finish after $STOP_TIMEOUT" }
        currentScope.cancel()
    }

    private fun whyUnloadable(record: JobRecord): String? {
        val type = jobSpec.allTypes[record.name]
            ?: return "there is no job type registered under the name '${record.name.value}'"
        return try {
            type.decodeCursor(record.activeCursor)
            record.failedAtCursor?.let { type.decodeCursor(it) }
            null
        } catch (e: Exception) {
            logger.warn(e) { "The cursor of job ${record.id} (${record.name}) could not be decoded" }
            // Not the message: it may quote the stored cursor.
            "its cursor could not be decoded (${e::class.simpleName})"
        }
    }

    /**
     * A job that was mid-step when the process died has committed nothing, so running that step again is exactly
     * right. Its attempt counter is left alone: the step never reported a failure.
     */
    private fun recoverAfterRestart(record: JobRecord, now: Instant): JobRecord = when (record.status) {
        JobStatus.Running -> record.copy(status = JobStatus.Ready, readyAt = now)
        else -> record
    }

    private fun handleUnloadable(unloadable: List<Pair<JobRecord, String>>, now: Instant) {
        if (unloadable.isEmpty()) {
            return
        }
        val described = unloadable.joinToString("\n") { (record, why) -> "  job ${record.id} (${record.name}): $why" }
        when (jobSettings.onUnloadableJob) {
            UnloadableJobPolicy.FailToStart -> throw IllegalConfigurationException(
                KlerkErrorCode.UnregisteredJobName,
                "${unloadable.size} persisted job(s) cannot be loaded:\n$described\n" +
                    "Either restore the job types and cursor shapes, or set " +
                    "'jobs { onUnloadableJob = UnloadableJobPolicy.DeadLetter }' to dead-letter them instead. " +
                    "Treat cursor types as a persisted schema: add optional fields, never remove or retype them " +
                    "while jobs may be in flight.",
            )

            UnloadableJobPolicy.DeadLetter -> {
                logger.error { "Dead-lettering ${unloadable.size} job(s) that cannot be loaded:\n$described" }
                val dead = unloadable.map { (record, why) ->
                    // No hook: the cursor a hook would need is the very thing that failed to load.
                    record.copy(
                        status = JobStatus.DeadLettered,
                        readyAt = null,
                        reason = "Could not be loaded at startup: $why",
                        lastAttemptFinished = now,
                    )
                }
                for (record in dead) {
                    records[record.id] = record
                }
                klerk.settings.persistence.commitJobStep(CommitBatch(jobs = JobCommit(upserted = dead)))
            }
        }
    }

    // ------------------------------------------------------------------ dispatch

    private suspend fun dispatchLoop() {
        while (!stopping) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Bug in Klerk: the job dispatcher threw" }
            }
            withTimeoutOrNull(jobSettings.pollInterval) { wakeup.receive() }
        }
    }

    /** One round of housekeeping plus as much dispatching as the concurrency limits allow. */
    private suspend fun tick() {
        fireDueCrons()
        deleteExpiredTerminalJobs()
        val currentScope = scope ?: return
        while (!stopping) {
            val next = claimNext() ?: break
            currentScope.launch {
                try {
                    runStep(next)
                } catch (e: CancellationException) {
                    releaseWithoutCommit(next.id)
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Bug in Klerk: running job ${next.id} threw outside the step" }
                    releaseWithoutCommit(next.id)
                } finally {
                    wakeup.trySend(Unit)
                }
            }
        }
    }

    /**
     * Picks the next job to step, marks it running and returns it — or null when nothing is dispatchable.
     *
     * The running marker is in memory only. Persisting it would double the writes per step and buy nothing: a job
     * interrupted mid-step committed nothing, and [recoverAfterRestart] puts it back in the queue either way.
     */
    private suspend fun claimNext(): JobRecord? = lock.withLock {
        val now = klerk.settings.now()
        promoteReady(now)
        updateQueueOrder(now)
        updateBudgetTracking(now)

        if (running.size >= jobSettings.maxParallelSteps) {
            return@withLock null
        }
        val perType = running.mapNotNull { records[it]?.name }.groupingBy { it }.eachCount()

        val candidate = records.values
            .asSequence()
            .filter { it.id !in running }
            .filter { isDispatchableStatus(it) }
            .filter { it.status != JobStatus.Cancelling || isUnwindable(it, now) }
            .filter { it.readyAt == null || it.readyAt <= now }
            .filter { hasConcurrencySlot(it, perType) }
            .filter { !isQueuedBehindEarlierCronRun(it) }
            // Priority first (Interactive is ordinal 0), then first-in-first-out within a class.
            .sortedWith(compareBy({ it.priority.ordinal }, { queueOrder[it.id] ?: Long.MAX_VALUE }))
            .firstOrNull() ?: return@withLock null

        // The candidate came out of a scan taken without the write lock, so it may already be stale — a commit could
        // have finished, cancelled or deleted it in between. Re-read it under the lock and only claim what is still
        // exactly what was selected; anything else is left for the next dispatch attempt.
        klerk.readWriteLock.withWrite {
            if (records[candidate.id] != candidate) {
                return@withWrite null
            }
            // Dropping the queue position here is what makes dispatch round-robin: when the job comes back ready
            // after this step it goes to the tail. Otherwise a job that yields immediately would be picked again and
            // again, starving everything that happened to tie with it.
            queueOrder.remove(candidate.id)
            running.add(candidate.id)
            candidate
        }
    }

    private fun isDispatchableStatus(record: JobRecord): Boolean =
        record.status == JobStatus.Ready || record.status == JobStatus.Cancelling

    /**
     * Gives every newly-dispatchable job its place in the queue.
     *
     * Ordering by `readyAt` alone is not enough: timestamps tie constantly — at millisecond resolution under load, and
     * always under a test clock that does not move — and a tie broken by job id means the same job wins forever.
     */
    private fun updateQueueOrder(now: Instant) {
        queueOrder.keys.retainAll { id -> records[id]?.let { isDispatchableStatus(it) } == true }
        records.values
            .filter { isDispatchableStatus(it) && !queueOrder.containsKey(it.id) && it.id !in running }
            .sortedWith(compareBy({ it.readyAt ?: it.createdAt }, { it.id.value }))
            .forEach { queueOrder[it.id] = queueCounter++ }
    }

    /**
     * Moves jobs whose `scheduleAt` or backoff has arrived into [JobStatus.Ready].
     *
     * The scan stays off the write lock — it is O(all jobs) and runs constantly — so the rows it produces are applied
     * conditionally: a job someone cancelled in between keeps the newer state instead of being promoted over.
     */
    private suspend fun promoteReady(now: Instant) {
        val due = records.values
            .filter {
                when (it.status) {
                    JobStatus.Scheduled, JobStatus.Backoff -> true
                    // A parent wakes as soon as nothing it spawned is still running. Deriving that here, rather than
                    // having each finishing child decrement a counter, is what makes concurrent siblings safe: this
                    // reads the children and writes only the parent, so there is no shared value to lose.
                    JobStatus.Waiting -> !awaitedChildrenRemain(it.id)
                    else -> false
                }
            }
            .filter { it.readyAt == null || it.readyAt <= now }
        if (due.isEmpty()) {
            return
        }
        val promoted = due.map { it.copy(status = JobStatus.Ready, readyAt = it.readyAt ?: now) }
        klerk.readWriteLock.withWrite {
            applyToMemory(JobCommit(upserted = promoted), due.associateBy { it.id })
        }
    }

    private fun hasConcurrencySlot(record: JobRecord, perType: Map<JobName, Int>): Boolean {
        val max = jobSpec.allTypes[record.name]?.maxConcurrent ?: return true
        return (perType[record.name] ?: 0) < max
    }

    /**
     * A [Overlap.Queue] cron run waits for every earlier run of the same schedule to become terminal. Without this,
     * "queue" would mean nothing more than "start it anyway".
     */
    private fun isQueuedBehindEarlierCronRun(record: JobRecord): Boolean {
        val scheduleId = record.cronScheduleId ?: return false
        val schedule = jobSpec.crons.firstOrNull { it.id == scheduleId } ?: return false
        if (schedule.overlap != Overlap.Queue) {
            return false
        }
        return records.values.any {
            it.cronScheduleId == scheduleId && !it.status.isTerminal && it.createdAt < record.createdAt
        }
    }

    /**
     * A cancelling job may only start unwinding once every descendant of it is terminal — children are cancelled
     * first, and the parent's `onCancelled` runs after.
     */
    private fun isUnwindable(record: JobRecord, now: Instant): Boolean {
        if (record.readyAt != null && record.readyAt > now) {
            return false
        }
        return records.values.none { it.parent == record.id && !it.status.isTerminal }
    }

    private suspend fun releaseWithoutCommit(id: JobID) =
        lock.withLock { klerk.readWriteLock.withWrite { running.remove(id) } }

    // ------------------------------------------------------------------ running one step

    private suspend fun runStep(record: JobRecord) {
        val type = jobSpec.allTypes[record.name] ?: run {
            // Only reachable if the registry changed after startup, which it cannot; treated as a bug, not a job error.
            logger.error { "Job ${record.id} has the unregistered name '${record.name}'" }
            releaseWithoutCommit(record.id)
            return
        }

        val now = klerk.settings.now()
        val started = record.copy(
            lastAttemptStarted = now,
            firstAttemptStarted = record.firstAttemptStarted ?: now,
        )

        exceededLimits(started, now)?.let { reason ->
            commitOutcome(started, type, JobResult.Abort(reason), null)
            return
        }

        val info = started.toJobInfo()
        val context = buildContext(started, info, now)
        val result = withContext(RunningJobElement(record.id)) {
            invokeStep(type, started, info, context)
        }
        commitOutcome(started, type, result, context)
    }

    /**
     * Re-reads the control flags that may have been set while the step was running. Cancellation in particular arrives
     * from another coroutine, and the row the step started from does not know about it.
     */
    private fun refreshControlFlags(record: JobRecord): JobRecord {
        val current = records[record.id] ?: return record
        return record.copy(
            cancellationRequested = current.cancellationRequested,
            reason = record.reason ?: current.reason,
        )
    }

    /**
     * Calls the application's step (or hook) function. Any throw is a [JobResult.Fail]: a step that dies from an
     * exception is exactly the case retries exist for.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeStep(
        type: JobType<*, C, V>,
        record: JobRecord,
        info: JobInfo,
        context: C,
    ): JobResult<Any, C, V> = try {
        val cursor = type.decodeCursor(record.activeCursor)
        val previous = previousResults[record.id]
        val outcomes = childOutcomesOf(record.id)
        when (type) {
            is JobType.Local<*, C, V> -> {
                val local = type as JobType.Local<Any, C, V>
                val reader = ReaderWithoutAuth<C, V>(klerk)
                when (record.hookKind) {
                    null -> local.step(
                        JobStepArgs.Local(
                            cursor = cursor,
                            previousResult = previous,
                            job = info,
                            context = context,
                            reader = reader,
                            klerk = klerk,
                            children = outcomes,
                            cancellationRequested = record.cancellationRequested,
                        ),
                    )

                    else -> {
                        val args = JobEndArgs.Local(
                            cursor = cursor,
                            failedAtCursor = local.decodeCursor(record.failedAtCursor ?: record.cursor),
                            reason = record.reason ?: "",
                            previousResult = previous,
                            job = info,
                            context = context,
                            reader = reader,
                            klerk = klerk,
                            children = outcomes,
                        )
                        if (record.hookKind == JobHookKind.OnCancelled) {
                            local.onCancelled(args)
                        } else {
                            local.onDeadLettered(args)
                        }
                    }
                }
            }

            is JobType.Portable<*, C, V> -> {
                val portable = type as JobType.Portable<Any, C, V>
                when (record.hookKind) {
                    null -> portable.step(
                        JobStepArgs.Portable(
                            cursor = cursor,
                            previousResult = previous,
                            job = info,
                            context = context,
                            children = outcomes,
                            cancellationRequested = record.cancellationRequested,
                        ),
                    )

                    else -> {
                        val args = JobEndArgs.Portable<Any, C, V>(
                            cursor = cursor,
                            failedAtCursor = portable.decodeCursor(record.failedAtCursor ?: record.cursor),
                            reason = record.reason ?: "",
                            previousResult = previous,
                            job = info,
                            context = context,
                            children = outcomes,
                        )
                        if (record.hookKind == JobHookKind.OnCancelled) {
                            portable.onCancelled(args)
                        } else {
                            portable.onDeadLettered(args)
                        }
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn(e) { "Job ${record.id} (${record.name}) threw" }
        // Only the type: the message may contain anything, and the reason is shown to everyone who may see the job.
        JobResult.Fail("The step threw ${e::class.simpleName}")
    }

    private fun buildContext(record: JobRecord, info: JobInfo, now: Instant): C {
        val provider = specification.jobContextProvider
            ?: return specification.systemContextProvider.invoke()
        val actor = when (record.agent) {
            JobAgent.System -> SystemIdentity
            JobAgent.Scheduler -> record.rebuildOwner()
        }
        return provider.invoke(JobContextRequest(actor, now, info))
    }

    /** The caps that end a job regardless of what its step would have said. */
    private fun exceededLimits(record: JobRecord, now: Instant): String? {
        if (record.hookKind != null) {
            // Hooks are bounded by their own retries rather than by the job's step/duration caps, which the job has
            // already exhausted by the time a hook runs.
            return null
        }
        val type = jobSpec.allTypes[record.name] ?: return null
        type.maxSteps?.let { if (record.step >= it) return "Reached maxSteps ($it)" }
        type.maxDuration?.let { max ->
            val since = record.firstAttemptStarted ?: return@let
            if (now - since > max) return "Reached maxDuration ($max)"
        }
        if (record.noProgressStreak >= LIVELOCK_STEPS) {
            return "Made no progress in $LIVELOCK_STEPS consecutive steps (neither the cursor nor the progress changed)"
        }
        return null
    }

    // ------------------------------------------------------------------ turning a result into a commit

    /**
     * Works out every row the step implies, commits them with the step's command in one transaction, and then updates
     * the in-memory mirror to match.
     */
    private suspend fun commitOutcome(
        record: JobRecord,
        type: JobType<*, C, V>,
        result: JobResult<Any, C, V>,
        context: C?,
    ) {
        val now = klerk.settings.now()
        val transition = try {
            planTransition(refreshControlFlags(record), type, result, now)
        } catch (e: Exception) {
            logger.error(e) { "Bug in Klerk: could not plan the outcome of job ${record.id}" }
            releaseWithoutCommit(record.id)
            return
        }

        val commandResult = try {
            klerk.eventsManager.commitJobStep(
                command = transition.command,
                context = if (transition.command == null) null else context,
                options = transition.options ?: ProcessingOptions(),
                jobCommit = transition.commit,
            )
        } catch (e: Exception) {
            // The transaction failed, so nothing was written. Put the job back and let the retry machinery see it as a
            // failed attempt, rather than losing the step silently.
            logger.error(e) { "Could not commit the step of job ${record.id}" }
            // Any child this step planned to spawn was never written, so its id goes back in the pool.
            releaseReservations(transition.commit.upserted.map { it.id })
            releaseWithoutCommit(record.id)
            return
        }

        // The rows themselves were applied inside the commit's own write-locked section, so all that is left here is
        // the state readers do not see (previousResults) and the Running overlay, which they do.
        lock.withLock {
            klerk.readWriteLock.withWrite { running.remove(record.id) }
            if (commandResult == null) {
                previousResults.remove(record.id)
            } else {
                previousResults[record.id] = commandResult
            }
        }
        // Spawned children are in records now, so they no longer need reserving.
        releaseReservations(transition.commit.upserted.map { it.id })
        klerk.attachedDataImpl.releaseJobClaims(transition.commit.attachedDataReleased)
        for (record in transition.commit.upserted) {
            changes.tryEmit(record)
        }
        wakeup.trySend(Unit)
    }

    private class Transition(
        val commit: JobCommit,
        val command: Command<Any, Any?>? = null,
        val options: ProcessingOptions? = null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun planTransition(
        record: JobRecord,
        type: JobType<*, C, V>,
        result: JobResult<Any, C, V>,
        now: Instant,
    ): Transition {
        val logged = record.withLog(result.log).copy(lastAttemptFinished = now)
        val rows = RowSet(records)

        val command = when (result) {
            is JobResult.Yield -> result.command
            is JobResult.Success -> result.command
            else -> null
        } as Command<Any, Any?>?
        val options = when (result) {
            is JobResult.Yield -> result.options
            is JobResult.Success -> result.options
            else -> null
        }

        when (result) {
            is JobResult.Yield -> {
                val encoded = type.encodeUnknownCursor(result.cursor)
                val spawnProblem = validateSpawn(record, type, result.spawn.size)
                if (spawnProblem != null) {
                    return Transition(deadLetter(logged, type, spawnProblem, runHook = true, now, rows))
                }
                val children = result.spawn.map { spawnRecord(record, it, now) }
                for (child in children) {
                    rows.put(child)
                }
                if (children.isNotEmpty()) {
                }

                // Progress means "the cursor or the progress changed". A job reporting "file 312 of 500" is by
                // definition alive; one re-emitting a command that keeps being rejected, forever, is not.
                val madeProgress =
                    encoded != record.cursor || (result.progress != null && result.progress != record.progress)
                val next = logged
                    .withProgress(result.progress)
                    .copy(
                        cursor = if (record.hookKind == null) encoded else record.cursor,
                        hookCursor = if (record.hookKind == null) record.hookCursor else encoded,
                        step = record.step + 1,
                        attempt = 0,
                        noProgressStreak = if (madeProgress) 0 else record.noProgressStreak + 1,
                    )
                val awaiting = result.awaitSpawned && children.isNotEmpty()
                rows.put(
                    when {
                        awaiting -> next.copy(
                            status = JobStatus.Waiting,
                            readyAt = null,
                        )

                        record.cancellationRequested && record.hookKind == null ->
                            beginCancellation(next, now, rows)

                        else -> next.copy(status = JobStatus.Ready, readyAt = now)
                    },
                )
                return Transition(rows.toCommit(), command, options)
            }

            is JobResult.Success -> {
                val finished = logged.withProgress(result.progress).copy(
                    step = record.step + 1,
                    attempt = 0,
                    result = result.result ?: logged.result,
                )
                // A step that returns Success while cancellation is pending has stopped early because it was asked
                // to, so it still unwinds through onCancelled rather than being reported as having simply succeeded.
                if (record.cancellationRequested && record.hookKind == null) {
                    rows.put(beginCancellation(finished, now, rows))
                    return Transition(rows.toCommit(), command, options)
                }
                return Transition(finish(finished, terminalStatusFor(record), now, rows), command, options)
            }

            is JobResult.Fail -> {
                val attempt = record.attempt + 1
                val maxRetries = if (record.hookKind == null) type.maxRetries else HOOK_MAX_RETRIES
                if (attempt > maxRetries) {
                    val reason = "Failed $attempt times, last: ${result.reason}"
                    return Transition(
                        if (record.hookKind == null) {
                            deadLetter(logged, type, reason, runHook = true, now, rows)
                        } else {
                            finish(logged.copy(reason = reason), JobStatus.CompensationFailed, now, rows)
                        },
                    )
                }
                rows.put(
                    logged.copy(
                        status = JobStatus.Backoff,
                        attempt = attempt,
                        reason = result.reason,
                        readyAt = now + backoffFor(attempt),
                    ),
                )
                return Transition(rows.toCommit())
            }

            is JobResult.Abort -> {
                if (record.hookKind != null) {
                    return Transition(
                        finish(logged.copy(reason = result.reason), JobStatus.CompensationFailed, now, rows),
                    )
                }
                return Transition(deadLetter(logged, type, result.reason, result.runHook, now, rows))
            }
        }
    }

    /** Which terminal status a job that ran to completion should land in, given what it was doing. */
    private fun terminalStatusFor(record: JobRecord): JobStatus = when (record.hookKind) {
        null -> JobStatus.Succeeded
        JobHookKind.OnCancelled -> JobStatus.Cancelled
        JobHookKind.OnDeadLettered -> JobStatus.DeadLettered
    }

    /**
     * Moves a job into its dead-letter unwind. The cursor it died at is preserved read-only for the hook, which
     * checkpoints separately — so unwinding never destroys the record of where the job got to.
     */
    private fun deadLetter(
        record: JobRecord,
        type: JobType<*, C, V>,
        reason: String,
        runHook: Boolean,
        now: Instant,
        rows: RowSet,
    ): JobCommit {
        val dead = record.copy(reason = reason, failedAtCursor = record.failedAtCursor ?: record.cursor)
        if (!runHook) {
            return finish(dead, JobStatus.DeadLettered, now, rows)
        }
        rows.put(
            dead.copy(
                status = JobStatus.Ready,
                hookKind = JobHookKind.OnDeadLettered,
                hookCursor = dead.failedAtCursor,
                attempt = 0,
                readyAt = now,
                noProgressStreak = 0,
            ),
        )
        return rows.toCommit()
    }

    /** Starts the cancellation unwind, or finishes straight away if there is no hook work to await. */
    private fun beginCancellation(record: JobRecord, now: Instant, rows: RowSet): JobRecord = record.copy(
        status = JobStatus.Cancelling,
        hookKind = JobHookKind.OnCancelled,
        hookCursor = record.cursor,
        failedAtCursor = record.failedAtCursor ?: record.cursor,
        attempt = 0,
        readyAt = now,
        noProgressStreak = 0,
    )

    /**
     * Writes a job's terminal row and lets go of the attached data it claimed. A parent awaiting this job is not
     * touched: it notices on its own, see [awaitedChildrenRemain].
     *
     * A job that ran to completion has no working set any more, so its claims are released and whatever it prepared
     * but never attached goes back to being governed by its lease. A job that died or was stopped keeps them: the
     * point of the claim is that a human can still look at what it was working on, until the job itself is deleted.
     */
    private fun finish(record: JobRecord, status: JobStatus, now: Instant, rows: RowSet): JobCommit {
        val terminal = record.copy(
            status = status,
            readyAt = null,
            hookKind = null,
            lastAttemptFinished = now,
        )
        rows.put(terminal)
        // A finishing child writes nothing but its own row. Whether its parent may now run is derived from the
        // children whenever the question is asked — see [awaitedChildrenRemain] — rather than tracked in a counter on
        // the parent. A counter has to be read, decremented and written, and siblings finishing at the same time all
        // read the same value before any of them writes, so all but one of the decrements is lost and the parent
        // waits forever.
        val released = if (status == JobStatus.Succeeded) klerk.attachedDataImpl.claimedBy(record.id) else emptySet()
        return rows.toCommit().copy(attachedDataReleased = released)
    }

    /** True while any child of [id] has yet to reach a terminal status. */
    private fun awaitedChildrenRemain(id: JobID): Boolean =
        records.values.any { it.parent == id && !it.status.isTerminal }

    /** What the children of [id] reported, read from their own rows at the moment the parent asks. */
    private fun childOutcomesOf(id: JobID): List<ChildOutcome> = records.values
        .filter { it.parent == id && it.status.isTerminal }
        .sortedBy { it.createdAt }
        .map { it.toChildOutcome() }

    /**
     * The spawn budgets. Children bypass admission control — refusing them would strand the parent mid-job — so this
     * is what stops a job that spawns in a loop from evading every other protection.
     */
    private fun validateSpawn(record: JobRecord, type: JobType<*, C, V>, count: Int): String? {
        if (count == 0) {
            return null
        }
        if (record.depth + 1 > type.maxDepth) {
            return "Spawning would exceed maxDepth (${type.maxDepth})"
        }
        if (descendantCountOf(record.root) + count > type.maxDescendants) {
            return "Spawning $count would exceed maxDescendants (${type.maxDescendants})"
        }
        return null
    }

    /**
     * How many jobs the tree rooted at [root] currently consists of, not counting the root.
     *
     * Counted from the tree rather than tracked on the root, for the same reason the parent's children are: a counter
     * has to be read and written, and two jobs spawning at the same instant both read it before either writes, so one
     * of the increments is lost and the budget is quietly larger than configured.
     */
    private fun descendantCountOf(root: JobID): Int = records.values.count { it.root == root && it.id != root }

    private fun spawnRecord(parent: JobRecord, child: DeclaredJob<C, V>, now: Instant): JobRecord = newRecord(
        id = allocateId(),
        scheduled = child,
        priority = child.priority ?: child.type.priority ?: parent.priority,
        ownerActorType = parent.ownerActorType,
        ownerActorId = parent.ownerActorId,
        ownerActorExternalId = parent.ownerActorExternalId,
        ownerActorName = parent.ownerActorName,
        now = now,
        parent = parent.id,
        root = parent.root,
        depth = parent.depth + 1,
    )

    private fun backoffFor(attempt: Int): Duration = jobSettings.backoffBase * 3.0.pow(attempt - 1)

    /** Bookkeeping for the rows one commit touches, so that a row updated twice is written once. */
    private inner class RowSet(private val current: Map<JobID, JobRecord>) {
        private val rows = linkedMapOf<JobID, JobRecord>()

        fun put(record: JobRecord) {
            rows[record.id] = record
        }

        fun update(id: JobID, transform: (JobRecord) -> JobRecord) {
            val existing = rows[id] ?: current[id] ?: return
            rows[id] = transform(existing)
        }

        fun toCommit(): JobCommit = JobCommit(upserted = rows.values.toList())
    }

    // ------------------------------------------------------------------ scheduling

    override fun isJobIdAvailable(id: Long): Boolean = !records.containsKey(JobID(id))

    private fun allocateId(): JobID {
        while (true) {
            val candidate = JobID(idCandidates())
            // add() is the atomic step: whichever caller wins it owns the id until its row is committed.
            if (!records.containsKey(candidate) && reservedIds.add(candidate)) {
                return candidate
            }
        }
    }

    /**
     * Ends the reservations [allocateId] made, once the rows are in [records] — or once the plan that chose them was
     * abandoned. Ids that were never reserved are ignored, so a whole commit's worth of rows can be passed in.
     */
    private fun releaseReservations(ids: Iterable<JobID>) {
        for (id in ids) {
            reservedIds.remove(id)
        }
    }

    override fun planNewJobs(pending: List<PendingJob<C, V>>, context: C): NewJobPlan {
        if (pending.isEmpty()) {
            return NewJobPlan.Ok(emptyList())
        }
        val now = klerk.settings.now()
        val snapshot = queueSnapshot(now)
        val problems = mutableListOf<Problem>()
        val accepted = mutableListOf<JobRecord>()

        for (job in pending) {
            val scheduled = job.scheduled
            val basePriority = scheduled.priority ?: scheduled.type.priority ?: JobPriority.Normal
            // The hard cap comes first and is not overridable: a policy that always says Allow must still not be able
            // to exhaust memory.
            if (records.size + accepted.size >= jobSettings.hardQueueLimit) {
                problems.add(
                    StateProblem(
                        "The system is busy. Please try again shortly.",
                        "The job queue is at its hard limit of ${jobSettings.hardQueueLimit} jobs",
                        KlerkErrorCode.JobQueueOverloaded,
                    ),
                )
                continue
            }
            val candidate = JobCandidate(scheduled.name, basePriority, scheduled.scheduleAt)
            val decision = try {
                jobSpec.admission(AdmissionArgs(snapshot, candidate, context, now))
            } catch (e: Exception) {
                // Refused rather than admitted, since the policy may exist to deny exactly this job.
                logger.error(e) { "The admission policy threw; refusing the job" }
                AdmissionDecision.Deny(InternalProblem("The job could not be admitted"))
            }
            when (decision) {
                is AdmissionDecision.Allow -> accepted.add(newRecord(job.id, scheduled, basePriority, context, now))
                is AdmissionDecision.Downgrade ->
                    accepted.add(newRecord(job.id, scheduled, decision.priority, context, now))

                is AdmissionDecision.Delay -> accepted.add(
                    newRecord(job.id, scheduled, basePriority, context, now, scheduleAt = decision.until),
                )

                is AdmissionDecision.Deny -> problems.add(decision.problem)
            }
        }

        return if (problems.isEmpty()) NewJobPlan.Ok(accepted) else NewJobPlan.Rejected(problems)
    }

    /**
     * See [JobManagerInternal.applyToMemory]: write lock held, job mutex not.
     *
     * `expected` holds the rows the plan was computed from, for the callers that planned outside the write lock. Such
     * a row is only written if it has not changed since, so a stale plan is dropped rather than clobbering whatever
     * happened in between. A row the planner owns exclusively — a brand new job, or the checkpoint of the job that is
     * mid-step — needs no entry here and is written unconditionally.
     */
    override fun applyToMemory(commit: JobCommit) = applyToMemory(commit, emptyMap())

    internal fun applyToMemory(commit: JobCommit, expected: Map<JobID, JobRecord>) {
        for (row in commit.upserted) {
            when (val was = expected[row.id]) {
                null -> records[row.id] = row
                else -> records.computeIfPresent(row.id) { _, current -> if (current == was) row else current }
            }
        }
        for (id in commit.deleted) {
            when (val was = expected[id]) {
                null -> records.remove(id)
                else -> records.remove(id, was)
            }
        }
    }

    /** See [JobManagerInternal.notifyCommitted]: never called with the write lock held. */
    override fun notifyCommitted(commit: JobCommit) {
        if (commit.upserted.isEmpty() && commit.deleted.isEmpty()) {
            return
        }
        for (record in commit.upserted) {
            changes.tryEmit(record)
        }
        wakeup.trySend(Unit)
    }

    private fun newRecord(
        id: JobID,
        scheduled: DeclaredJob<C, V>,
        priority: JobPriority,
        context: C,
        now: Instant,
        scheduleAt: Instant? = null,
        cronScheduleId: String? = null,
    ): JobRecord = newRecord(
        id = id,
        scheduled = scheduled,
        priority = priority,
        ownerActorType = context.actor.type,
        ownerActorId = context.actor.id?.value,
        ownerActorExternalId = context.actor.externalId,
        ownerActorName = (context.actor as? PluginIdentity)?.pluginName,
        now = now,
        scheduleAt = scheduleAt,
        cronScheduleId = cronScheduleId,
    )

    private fun newRecord(
        id: JobID,
        scheduled: DeclaredJob<C, V>,
        priority: JobPriority,
        ownerActorType: ActorType,
        ownerActorId: Int?,
        ownerActorExternalId: Long?,
        ownerActorName: String?,
        now: Instant,
        scheduleAt: Instant? = null,
        parent: JobID? = null,
        root: JobID? = null,
        depth: Int = 0,
        cronScheduleId: String? = null,
    ): JobRecord {
        val readyAt = scheduleAt ?: scheduled.scheduleAt ?: now
        return JobRecord(
            id = id,
            name = scheduled.name,
            cursor = scheduled.encodedCursor,
            status = if (readyAt > now) JobStatus.Scheduled else JobStatus.Ready,
            priority = priority,
            agent = scheduled.type.agent,
            ownerActorType = ownerActorType,
            ownerActorId = ownerActorId,
            ownerActorExternalId = ownerActorExternalId,
            ownerActorName = ownerActorName,
            step = 0,
            attempt = 0,
            createdAt = now,
            readyAt = readyAt,
            firstAttemptStarted = null,
            lastAttemptStarted = null,
            lastAttemptFinished = null,
            progressCompleted = null,
            progressTotal = null,
            progressMessage = null,
            log = emptyList(),
            parent = parent,
            root = root ?: id,
            depth = depth,
            result = null,
            failedAtCursor = null,
            hookCursor = null,
            hookKind = null,
            cancellationRequested = false,
            reason = null,
            noProgressStreak = 0,
            cronScheduleId = cronScheduleId,
        )
    }

    override suspend fun schedule(job: DeclaredJob<C, V>, context: C): JobID =
        scheduleClaiming(job, context, emptySet())

    override suspend fun scheduleClaiming(job: DeclaredJob<C, V>, context: C, claim: Set<Int>): JobID {
        check(started) { "Klerk has not been started" }
        val id = allocateId()
        try {
            when (val plan = planNewJobs(listOf(PendingJob(id, job)), context)) {
                is NewJobPlan.Rejected -> throw JobRejectedException(plan.problems.first())
                is NewJobPlan.Ok -> {
                    val commit = plan.commit.copy(attachedDataClaimed = claim.associateWith { id })
                    klerk.settings.persistence.commitJobStep(CommitBatch(jobs = commit))
                    lock.withLock { klerk.readWriteLock.withWrite { applyToMemory(plan.commit) } }
                    notifyCommitted(plan.commit)
                }
            }
        } finally {
            // Either the row is in records now, or the job was refused and the id was never used.
            releaseReservations(listOf(id))
        }
        return id
    }

    // ------------------------------------------------------------------ inspection and control

    /**
     * [JobRecord.toJobInfo], with [JobStatus.Running] overlaid for a job whose step is currently executing.
     *
     * The record's persisted `status` stays [JobStatus.Ready] for the whole time a step is in flight — see [running]
     * — so every read path (not just the dispatcher) needs to consult [running] to ever report [JobStatus.Running].
     */
    private fun JobRecord.toJobInfoWithRunningOverlay(): JobInfo {
        val info = toJobInfo()
        return if (id in running) info.copy(status = JobStatus.Running) else info
    }

    /**
     * The job's committed state plus the live `Running` overlay, without authorization.
     *
     * Only sound while the read lock is held — that is what stops the record changing under the caller — so this is
     * for `Reader.jobs` and for the lock-taking wrappers below, not for general use.
     */
    internal fun jobInfoOrNull(id: JobID): JobInfo? = records[id]?.toJobInfoWithRunningOverlay()

    /** Every job that exists, newest first, without authorization. Same locking requirement as [jobInfoOrNull]. */
    internal fun allJobInfo(): List<JobInfo> =
        records.values.sortedByDescending { it.createdAt }.map { it.toJobInfoWithRunningOverlay() }

    /** What both read entry points share: one read lock for the whole call, and the same rules as `Reader.jobs`. */
    private suspend fun <T> reading(context: C, block: (JobReader) -> T): T {
        ReadBlockGuard.checkNotInsideReadBlock("klerk.jobs", "Use reader.jobs inside a read block instead.")
        return klerk.readWriteLock.withRead {
            block(AuthorizingJobReader(klerk, context, ReaderWithoutAuth(klerk)))
        }
    }

    override suspend fun get(id: JobID, context: C): JobInfo = reading(context) { it.get(id) }

    override suspend fun all(context: C): List<JobInfo> = reading(context) { it.all() }

    override fun subscribe(id: JobID?, context: C): Flow<JobInfo> = changes
        .filter { id == null || it.id == id }
        .map { it.toJobInfoWithRunningOverlay() }
        .filter { isAuthorized(it, context) }

    override suspend fun cancel(id: JobID, context: C, reason: String) {
        val record = records[id] ?: throw NoSuchElementException("There is no job with id $id")
        authorizeControl(record.toJobInfo(), JobOperation.Cancel, context)
        if (record.status.isTerminal) {
            return
        }
        val now = klerk.settings.now()
        // The whole subtree is marked, and children are cancelled first: a parent's onCancelled runs only once every
        // descendant is terminal (see isUnwindable).
        val subtree = descendantsOf(id) + record
        val updated = subtree.filter { !it.status.isTerminal }.map {
            // A job with a step in flight is only *marked*. Rewriting its status or hook state here would be applied
            // on top of by the commit that step is about to make; instead it picks the request up at its next step
            // boundary, which is the only place cancellation may take effect.
            if (it.id in running || it.status == JobStatus.Running) {
                it.copy(cancellationRequested = true, reason = it.reason ?: reason)
            } else {
                it.copy(
                    cancellationRequested = true,
                    reason = it.reason ?: reason,
                    status = JobStatus.Cancelling,
                    readyAt = now,
                    hookKind = it.hookKind ?: JobHookKind.OnCancelled,
                    hookCursor = it.hookCursor ?: it.cursor,
                    failedAtCursor = it.failedAtCursor ?: it.cursor,
                )
            }
        }
        commitControlChange(JobCommit(upserted = updated))
    }

    override suspend fun resume(id: JobID, context: C) {
        val record = records[id] ?: throw NoSuchElementException("There is no job with id $id")
        authorizeControl(record.toJobInfo(), JobOperation.Resume, context)
        check(record.status == JobStatus.DeadLettered || record.status == JobStatus.CompensationFailed) {
            "Only a dead-lettered job can be resumed, but job $id is ${record.status}"
        }
        val now = klerk.settings.now()
        // From the checkpoint, never from step 0: the commands of steps 1..n have already been applied and Klerk has
        // no way to recognise re-emitted ones.
        val resumed = record.copy(
            status = JobStatus.Ready,
            readyAt = now,
            attempt = 0,
            noProgressStreak = 0,
            hookKind = null,
            hookCursor = null,
            reason = null,
        )
        commitControlChange(JobCommit(upserted = listOf(resumed)))
    }

    override suspend fun delete(id: JobID, context: C) {
        val record = records[id] ?: throw NoSuchElementException("There is no job with id $id")
        authorizeControl(record.toJobInfo(), JobOperation.Delete, context)
        check(record.status.isTerminal) { "Only a terminal job can be deleted, but job $id is ${record.status}" }
        commitControlChange(
            JobCommit(deleted = setOf(id), attachedDataReleased = klerk.attachedDataImpl.claimedBy(id)),
        )
    }

    /**
     * Commits [commit]. [expected] holds the rows the change was planned from, so a plan that a step or another control
     * change has overtaken is dropped rather than applied over the newer state. This is what stops a retention sweep
     * deleting a job that was resumed after the sweep decided it was terminal.
     */
    private suspend fun commitControlChange(commit: JobCommit, expected: Map<JobID, JobRecord> = emptyMap()) {
        klerk.settings.persistence.commitJobStep(CommitBatch(jobs = commit))
        lock.withLock {
            klerk.readWriteLock.withWrite { applyToMemory(commit, expected) }
            for (id in commit.deleted) {
                previousResults.remove(id)
            }
        }
        klerk.attachedDataImpl.releaseJobClaims(commit.attachedDataReleased)
        notifyCommitted(commit)
    }

    private fun descendantsOf(id: JobID): List<JobRecord> {
        val result = mutableListOf<JobRecord>()
        var frontier = records.values.filter { it.parent == id }
        while (frontier.isNotEmpty()) {
            result.addAll(frontier)
            val ids = frontier.map { it.id }.toSet()
            frontier = records.values.filter { it.parent in ids }
        }
        return result
    }

    // ------------------------------------------------------------------ manual execution

    override suspend fun step(): Boolean {
        requireManual()
        fireDueCrons()
        deleteExpiredTerminalJobs()
        val next = claimNext() ?: return false
        runStep(next)
        return true
    }

    override suspend fun runUntilIdle(maxSteps: Int): Int {
        requireManual()
        repeat(maxSteps) { steps ->
            if (!step()) return steps
        }
        throw IllegalStateException(
            "runUntilIdle ran $maxSteps steps without the queue going idle. Either raise maxSteps, or a job is " +
                "yielding forever.",
        )
    }

    private fun requireManual() {
        check(jobSettings.execution == JobExecution.Manual) {
            "step()/runUntilIdle() are only available with 'jobs { execution = JobExecution.Manual }'. With " +
                "JobExecution.Automatic, Klerk runs jobs on its own."
        }
    }

    // ------------------------------------------------------------------ authorization

    override suspend fun isAllowed(id: JobID, operation: JobOperation, context: C): Boolean {
        val record = records[id] ?: throw NoSuchElementException("There is no job with id $id")
        return controlFailure(record.toJobInfo(), operation, context) == null
    }

    private suspend fun authorizeControl(job: JobInfo, operation: JobOperation, context: C) {
        controlFailure(job, operation, context)?.let {
            throw AuthorizationException(it, "Not allowed to ${operation.name.lowercase()} job ${job.id}")
        }
    }

    private suspend fun controlFailure(job: JobInfo, operation: JobOperation, context: C): KlerkErrorCode? {
        if (context.actor == SystemIdentity) {
            return null
        }
        return klerk.readWriteLock.withRead {
            jobControlFailure(job, operation, context, specification, ReaderWithoutAuth(klerk))
        }
    }

    private suspend fun isAuthorized(job: JobInfo, context: C): Boolean = authorizationFailure(job, context) == null

    /**
     * Takes the read lock for the decision, so it can be called from outside a read block. Inside one the lock is
     * already held; use [jobAuthorizationFailure] directly there.
     */
    private suspend fun authorizationFailure(job: JobInfo, context: C): KlerkErrorCode? {
        if (context.actor == SystemIdentity) {
            return null
        }
        // The reader handed to a rule is only sound while the read lock is held, exactly as for the other rule sets.
        return klerk.readWriteLock.withRead {
            jobAuthorizationFailure(job, context, specification, ReaderWithoutAuth(klerk))
        }
    }

    // ------------------------------------------------------------------ admission input

    /**
     * Recomputes for how long each priority class has been continuously over its delay budget. Keeping this here
     * rather than in the policy is what lets the default policy be a pure function while still having hysteresis.
     */
    private fun updateBudgetTracking(now: Instant) {
        for (priority in JobPriority.entries) {
            val oldest = oldestReadyAt(priority)
            val overBudget = oldest != null && now - oldest > AdmissionPolicy.defaultBudgets.getValue(priority)
            if (overBudget) {
                overBudgetSince.putIfAbsent(priority, now)
            } else {
                overBudgetSince.remove(priority)
            }
        }
    }

    private fun oldestReadyAt(priority: JobPriority): Instant? = records.values
        .filter { it.priority == priority && it.status == JobStatus.Ready && it.id !in running }
        .minOfOrNull { it.readyAt ?: it.createdAt }

    private fun queueSnapshot(now: Instant): JobQueueSnapshot {
        updateBudgetTracking(now)
        val queued = records.values.filter { !it.status.isTerminal }
        return JobQueueSnapshot(
            oldestReady = JobPriority.entries.mapNotNull { p -> oldestReadyAt(p)?.let { p to it } }.toMap(),
            depths = queued.groupingBy { it.priority }.eachCount(),
            running = running.mapNotNull { records[it]?.priority }.groupingBy { it }.eachCount(),
            overBudgetSince = overBudgetSince.toMap(),
            total = queued.size,
            hardLimit = jobSettings.hardQueueLimit,
            now = now,
        )
    }

    // ------------------------------------------------------------------ cron

    private var cronLastFired = mutableMapOf<String, Instant>()

    private fun initialiseCronState(now: Instant) {
        cronLastFired = klerk.settings.persistence.cronState().toMutableMap()
        // A schedule that has never fired starts from now, so adding a cron to an existing system does not
        // immediately fire every occurrence since the epoch.
        for (cron in jobSpec.crons) {
            cronLastFired.putIfAbsent(cron.id, now)
        }
    }

    private suspend fun fireDueCrons() {
        if (jobSpec.crons.isEmpty()) {
            return
        }
        val now = klerk.settings.now()
        for (schedule in jobSpec.crons) {
            try {
                fireIfDue(schedule, now)
            } catch (e: Exception) {
                logger.error(e) { "Could not evaluate $schedule" }
            }
        }
    }

    private suspend fun fireIfDue(schedule: CronSchedule<C, V>, now: Instant) {
        val last = cronLastFired[schedule.id] ?: now
        val missed = schedule.parsed.occurrencesBetween(last, now, MAX_CATCH_UP_FIRES)
        if (missed.isEmpty()) {
            return
        }
        cronLastFired[schedule.id] = missed.last()
        klerk.settings.persistence.setCronFired(schedule.id, missed.last())

        val fires = when (schedule.catchUp) {
            CatchUp.RunAll -> missed.size
            CatchUp.RunOnce -> 1
            // "Skip" means missed fires are dropped. An occurrence that is not late is not a missed one.
            CatchUp.Skip -> if (missed.size == 1 && now - missed.last() <= missedThreshold()) 1 else 0
        }
        if (fires == 0) {
            return
        }
        if (schedule.overlap == Overlap.Skip && hasActiveRun(schedule)) {
            logger.debug { "Skipping $schedule because the previous run is still going" }
            return
        }

        val context = specification.systemContextProvider.invoke()
        repeat(fires) {
            val id = allocateId()
            // Jitter spreads the fire over a random window, so that many nodes (or many schedules on the same
            // expression) do not all start at the same instant.
            val scheduled = DeclaredJob(
                schedule.type,
                schedule.encodedCursor,
                scheduleAt = now + jitterFor(schedule),
                priority = null,
            )
            // Cron fires are new work, so they go through admission control like anything else.
            try {
                when (val plan = planNewJobs(listOf(PendingJob(id, scheduled)), context)) {
                    is NewJobPlan.Rejected -> logger.warn {
                        "$schedule was refused: ${plan.problems.joinToString(", ") { p -> p.toString() }}"
                    }

                    is NewJobPlan.Ok -> {
                        val admitted = plan.records.map { it.copy(cronScheduleId = schedule.id) }
                        klerk.settings.persistence.commitJobStep(CommitBatch(jobs = JobCommit(upserted = admitted)))
                        lock.withLock {
                            klerk.readWriteLock.withWrite { applyToMemory(JobCommit(upserted = admitted)) }
                        }
                        for (record in admitted) {
                            changes.tryEmit(record)
                        }
                        wakeup.trySend(Unit)
                        logger.debug { "Fired $schedule as job $id" }
                    }
                }
            } finally {
                releaseReservations(listOf(id))
            }
        }
    }

    private fun hasActiveRun(schedule: CronSchedule<C, V>): Boolean =
        records.values.any { it.cronScheduleId == schedule.id && !it.status.isTerminal }

    private fun jitterFor(schedule: CronSchedule<C, V>): Duration = if (schedule.jitter <= Duration.ZERO) {
        Duration.ZERO
    } else {
        schedule.jitter * random.nextDouble()
    }

    /** How late an occurrence may be and still count as "now" rather than "missed". */
    private fun missedThreshold(): Duration = maxOf(jobSettings.pollInterval * 5, 1.minutes)

    // ------------------------------------------------------------------ retention

    /** The retention setting that governs a terminal status, or `null` for a non-terminal one. */
    private fun retentionFor(status: JobStatus): Duration? = when (status) {
        JobStatus.Succeeded -> jobSettings.succeededRetention
        JobStatus.Cancelled -> jobSettings.cancelledRetention
        JobStatus.DeadLettered, JobStatus.CompensationFailed -> jobSettings.deadLetterRetention
        else -> null
    }

    private suspend fun deleteExpiredTerminalJobs() {
        val now = klerk.settings.now()
        val expired = records.values.filter { record ->
            val finished = record.lastAttemptFinished ?: return@filter false
            val retention = retentionFor(record.status) ?: return@filter false
            now - finished > retention
        }
        if (expired.isEmpty()) {
            return
        }
        val released = expired.flatMap { klerk.attachedDataImpl.claimedBy(it.id) }.toSet()
        // Conditional on the rows still being the terminal ones this scan saw: a job resumed in between is no longer
        // expired, and deleting it would take a live job with it.
        commitControlChange(
            JobCommit(deleted = expired.map { it.id }.toSet(), attachedDataReleased = released),
            expired.associateBy { it.id },
        )
    }

    private companion object {
        /** Three consecutive steps that change neither the cursor nor the progress are treated as a livelock. */
        const val LIVELOCK_STEPS = 3

        /** End-of-life hooks get their own, fixed retry budget; the job's own is already spent by then. */
        const val HOOK_MAX_RETRIES = 3

        const val MAX_CATCH_UP_FIRES = 1000

        /** How long [stop] waits for in-flight steps to commit before abandoning them. */
        val STOP_TIMEOUT: Duration = 30.seconds
    }
}

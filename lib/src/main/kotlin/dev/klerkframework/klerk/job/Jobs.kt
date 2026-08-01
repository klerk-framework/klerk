package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.JobManagerInternal
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.job.JobResult.*
import dev.klerkframework.klerk.misc.IdFactory
import dev.klerkframework.klerk.misc.getCurrentInstant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.coroutines.suspendCoroutine
import kotlin.math.pow
import kotlin.reflect.KFunction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

private val logger = KotlinLogging.logger {}

/**
 * Declares the properties of a managed, persisted background job. See [RunnableJob] for the class to actually extend.
 */
public interface Job {

    /** Number of retries after the first failed attempt before the job is marked [JobStatus.Failed]. */
    public val maxRetries: Int
        get() = 3

    /** Declared but not currently read by [JobManagerImpl] — there is a single in-process worker, so delivery is always effectively at-least-once. */
    public val delivery: JobDelivery
        get() = JobDelivery.AtLeastOnce

    /** Declared but not currently read by [JobManagerImpl] — there is no worker-tag filtering yet. */
    public val requiredWorkerTags: Set<String>
        get() = emptySet()

    /** Declared but not currently read by [JobManagerImpl]. */
    public val tags: Set<String>
        get() = emptySet()

    /**
     * The only job state that survives a process restart (persisted alongside the class/method name and
     * re-supplied to the run function on every attempt). Encode whatever the job needs into this string, e.g. a
     * JSON payload or a [dev.klerkframework.klerk.ModelID]'s string form.
     */
    public val parameters: String

    /**
     * The time to schedule the job. If null, the job will be scheduled immediately.
     */
    public val scheduleAt: Instant?
        get() = null

}

public enum class JobDelivery {
    AtLeastOnce, AtMostOnce
}

/**
 * Base class for a managed, persisted, retried background job. Return instances of a subclass from a state
 * machine's `job(...)` executable, or schedule one directly via [dev.klerkframework.klerk.JobManager.schedule].
 *
 * [getRunFunction] must return a reference to a function declared in the subclass's `companion object` — the job
 * manager persists the class name and method name and re-resolves the function reflectively, including after a
 * process restart, so it cannot be a lambda or an instance method.
 */
public abstract class RunnableJob<C : KlerkContext, V> : Job {
    public var id: JobId? = null

    //public suspend fun run(metadata: JobMetadata, klerk: Klerk<C, V>): JobResult

    /**
     * A reference to the function that should be executed.
     * Note that any exception thrown by the function will be logged and ignored.
     */
    public abstract fun getRunFunction(): suspend (metadata: JobMetadata, klerk: Klerk<C, V>) -> JobResult

    public fun setId(id: JobId) {
        this.id = id
    }

    /** @throws IllegalStateException if [setId] has not been called yet (i.e. the job has not been scheduled). */
    public fun getMetadata(): JobMetadata {
        val functionName = (getRunFunction() as KFunction<*>).name
        require(functionName.isNotBlank()) { "runFunction must be a function with a name" }
        val now = getCurrentInstant()
        return JobMetadata(
            id = id ?: throw IllegalStateException("Job must be initialized before creating metadata"),
            className = javaClass.name,
            methodName = functionName,
            status = JobStatus.Scheduled,
            created = now,
            lastAttemptStarted = null,
            lastAttemptFinished = null,
            nextAttempt = scheduleAt ?: now,
            state = "",
            failedAttempts = 0,
            maxRetries = maxRetries,
            log = emptyList(),
            parameters = parameters
        )
    }
}

/** Snapshot of a scheduled job's state, as returned by [dev.klerkframework.klerk.JobManager.getJob]/`getAllJobs`. */
public data class JobMetadata(
    val id: JobId,
    val className: String,
    val methodName: String,
    val status: JobStatus,
    val state: String,
    val created: Instant,
    val lastAttemptStarted: Instant?,
    val lastAttemptFinished: Instant?,
    val nextAttempt: Instant?,
    val parameters: String,
    val failedAttempts: Int,
    val log: List<String>,
    val maxRetries: Int
)


public enum class JobStatus {
    Scheduled, Running, Success, Failed, Backoff
}

/**
 * The outcome of one run attempt of a [RunnableJob], returned by its run function.
 *
 * @param state a string that the job can use to remember its state between runs (e.g. after a failed attempt).
 */
public sealed class JobResult(
    public val state: String,
    public val log: List<String>,
) {
    /** Attempt failed. Retried with exponential backoff (base 3s) until [Job.maxRetries] is reached, then [JobStatus.Failed]. An uncaught exception from the run function is treated the same as this. */
    public class Fail(state: String = "", log: List<String> = emptyList()) : JobResult(state, log)

    /** Not finished but not a failure either; re-queued immediately without counting against [Job.maxRetries]. Use for jobs that run in several steps. */
    public class Yield(state: String = "", log: List<String> = emptyList()) : JobResult(state, log)

    /** Done. The job is marked [JobStatus.Success] and not retried. */
    public class Success(state: String = "", log: List<String> = emptyList()) : JobResult(state, log)
}

internal class JobWorker<C : KlerkContext, V>(
    private val queue: Channel<Triple<JobMetadata, Method, Any>>,
    private val klerk: Klerk<C, V>,
    private val notifyStarted: (JobMetadata) -> JobMetadata,
    private val executionComplete: (JobMetadata, JobResult) -> Unit
) {

    fun start() {
        CoroutineScope(Dispatchers.Default).launch(Dispatchers.Default) {
            for ((meta, method, companionObject) in queue) {
                println("Worker picked job: ${meta.id}")
                val startedMeta = notifyStarted(meta)
                try {
                    suspendCoroutine<Any> { continuation ->
                        val result = method.invoke(companionObject, startedMeta, klerk, continuation) as? JobResult
                            ?: throw IllegalArgumentException("runFunction must return a JobResult")
                        executionComplete(startedMeta, result)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Exception when executing job" }
                    executionComplete(
                        startedMeta,
                        Fail(log = listOf("Job threw exception: ${e.message ?: "Unknown error"}"))
                    )
                }
            }
        }
    }

}

public typealias JobId = Int

internal class JobManagerImpl<C : KlerkContext, V>(private val klerkImpl: KlerkImpl<C, V>) :
    JobManagerInternal<C, V> {

    private var isStarted: Boolean = false
    val queue = Channel<Triple<JobMetadata, Method, Any>>(capacity = Channel.UNLIMITED)
    val jobWorker = JobWorker(queue, klerkImpl, ::notifyStarted, ::executionComplete)

    private val scheduledJobs = mutableMapOf<JobId, Triple<JobMetadata, Method, Any>>()
    private val unscheduledJobs = mutableSetOf<JobId>()
    private val idFactory = IdFactory(::isJobIdAvailable)

    fun notifyStarted(meta: JobMetadata): JobMetadata {
        logger.info { "Job ${meta.id} started" }
        val updatedMeta = meta.copy(status = JobStatus.Running, lastAttemptStarted = Clock.System.now())
        klerkImpl.config.persistence.updateJob(updatedMeta)
        val method =
            scheduledJobs[meta.id]?.second ?: throw NoSuchElementException("Cannot find job with id=${meta.id}")
        val companionInstance =
            scheduledJobs[meta.id]?.third ?: throw NoSuchElementException("Cannot find job with id=${meta.id}")
        scheduledJobs[meta.id] = Triple(updatedMeta, method, companionInstance)
        return updatedMeta
    }

    fun executionComplete(meta: JobMetadata, result: JobResult) {
        logger.info { "Execution complete for job ${meta.id}" }
        var updatedMeta = meta.copy(
            lastAttemptFinished = Clock.System.now(),
            state = result.state,
            log = meta.log + result.log
        )
        updatedMeta = when (result) {
            is Fail -> {
                if (meta.failedAttempts >= meta.maxRetries) {
                    updatedMeta.copy(
                        status = JobStatus.Failed,
                        failedAttempts = updatedMeta.failedAttempts.inc(),
                        nextAttempt = null
                    )
                } else {
                    updatedMeta.copy(
                        status = JobStatus.Backoff,
                        failedAttempts = updatedMeta.failedAttempts.inc(),
                        nextAttempt = Clock.System.now() + 3f.pow(updatedMeta.failedAttempts - 1).toLong()
                            .toDuration(DurationUnit.SECONDS)
                    )
                }
            }

            is Success -> updatedMeta.copy(
                status = JobStatus.Success,
                nextAttempt = null
            )

            is Yield -> updatedMeta.copy(
                status = JobStatus.Scheduled,
                nextAttempt = Clock.System.now()
            )
        }
        val method =
            scheduledJobs[meta.id]?.second ?: throw NoSuchElementException("Cannot find job with id=${meta.id}")
        val companionInstance =
            scheduledJobs[meta.id]?.third ?: throw NoSuchElementException("Cannot find job with id=${meta.id}")
        scheduledJobs[meta.id] = Triple(updatedMeta, method, companionInstance)
        klerkImpl.config.persistence.updateJob(updatedMeta)
    }

    override fun schedule(job: RunnableJob<C, V>): JobId {
        job.setId(idFactory.getNextJobID())
        val meta = job.getMetadata()
        val (method, companionObject) = getRunMethod(meta)
        klerkImpl.config.persistence.insertJob(meta)
        if (job.scheduleAt == null) {
            queue.trySend(Triple(meta, method, companionObject)).getOrThrow()
        }
        scheduledJobs[meta.id] = Triple(meta, method, companionObject)
        return meta.id
    }

    override fun isJobIdAvailable(int: Int): Boolean {
        if (scheduledJobs.containsKey(int)) {
            return false
        }
        if (unscheduledJobs.contains(int)) {
            return false
        }
        return true
    }

    override fun notifyJobWasAddedToDb(job: RunnableJob<C, V>) {
        val meta = job.getMetadata()
        val (method, companionObject) = getRunMethod(meta)
        if (job.scheduleAt == null) {
            queue.trySend(Triple(meta, method, companionObject)).getOrThrow()
        }
        scheduledJobs[meta.id] = Triple(meta, method, companionObject)
    }

    override fun getAllJobs(): List<JobMetadata> {
        return scheduledJobs.toSortedMap().map { it.value.first }
    }

    override fun getJob(id: JobId): JobMetadata {
        return scheduledJobs[id]?.first ?: throw NoSuchElementException("Cannot find job with id=$id")
    }

    fun start() {
        isStarted = true
        jobWorker.start()

        // load all jobs from db
        klerkImpl.config.persistence.getAllJobs().forEach {
            val (method, companionObject) = getRunMethod(it)
            scheduledJobs[it.id] = Triple(it, method, companionObject)
        }


        // start a thread that periodically checks for jobs that should be executed
        thread(isDaemon = false) {
            runBlocking {
                while (isStarted) {
                    delay(1.seconds)
                    pruneScheduledJobs()
                    scheduledJobs.values
                        .filter { it.first.nextAttempt?.let { it <= Clock.System.now() } ?: false }
                        .forEach { queue.trySend(it) }

                }
            }
        }
    }

    private fun getRunMethod(meta: JobMetadata): Pair<Method, Any> {
        val companionField = Class.forName(meta.className).getDeclaredField("Companion")
        val companionInstance = companionField.get(null) // static field, so null for instance
        val method =
            companionInstance.javaClass.declaredMethods.single { it.name == meta.methodName && it.parameterCount == 3 }
        return Pair(method, companionInstance)
    }

    fun stop() {
        isStarted = false
    }

    private fun pruneScheduledJobs() {
        scheduledJobs.keys.removeAll(scheduledJobs.filter { job -> job.value.first.nextAttempt == null }.keys)
    }

}

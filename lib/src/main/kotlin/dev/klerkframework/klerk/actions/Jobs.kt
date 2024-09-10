package dev.klerkframework.klerk.actions

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.JobManager
import dev.klerkframework.klerk.actions.JobResult.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import mu.KotlinLogging

import kotlinx.datetime.Instant
import java.util.*
import kotlin.math.pow
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

public interface Job<C:KlerkContext, V> {
    public val isCpuIntensive: Boolean
        get() = false

    public val maxRetries: Int
        get() = 3

    public val id: JobId

    public suspend fun run(jobContext: JobContext<C, V>): JobResult

    public suspend fun cleanup(context: JobMetadata) {}
}

public class JobContext<C:KlerkContext, V> internal constructor(public val jobId: JobId, public val attempt: Int, stage: String, public val klerk: Klerk<C, V>) {

    private var _data: String = stage
    public val data: String
        get() = _data

    public fun isFirstAttempt(): Boolean = attempt == 1
    public fun isRetry(): Boolean = attempt > 1

    public suspend fun log(message: String) {
        logger.info(message)
    }

}

public enum class JobResult {
    Success,
    Retry,
    Fail
}

internal typealias JobId = Long

internal class JobManagerImpl<C:KlerkContext, V>(val klerkImpl: KlerkImpl<C, V>) : JobManager<C, V> {

    private val jobs = mutableMapOf<JobId, JobMetadata>()

    override fun scheduleAction(action: Job<C, V>): JobId {
        val id = (jobs.keys.maxOrNull() ?: 0L) + 1
        val newMetadata = JobMetadata(
            id = id,
            name = action.javaClass.simpleName,
            status = JobStatus.New,
            created = Clock.System.now(),
            lastAttemptStarted = null,
            lastAttemptFinished = null,
            data = "",
            failedAttempts = 0u,
            maxRetries = action.maxRetries,
            log = sortedMapOf()
        )

        jobs[newMetadata.id] = newMetadata

        val dispatcher = if (action.isCpuIntensive) Dispatchers.Default else Dispatchers.IO

        CoroutineScope(dispatcher).launch {
            try {
                do {
                    val metadata = jobs[id]!!
                    val attempt = metadata.failedAttempts + 1u
                    if (attempt > 1u) {
                        jobs[id] = jobs[id]!!.copy(status = JobStatus.Backoff)
                        val waitTime = 3f.pow(attempt.toInt() - 1).toLong()
                        logger.info { "Waiting $waitTime s before trying '${metadata.name}' again" }
                        delay(waitTime * 1000)
                    }
                    logger.info { "Executing action '${metadata.name}', attempt: $attempt" }
                    val context = JobContext(metadata.id, attempt.toInt(), metadata.data, klerkImpl)
                    jobs[id] = jobs[id]!!.copy(status = JobStatus.Running, lastAttemptStarted = Clock.System.now())
                    val result = action.run(context)
                    jobs[id] = jobs[id]!!.copy(lastAttemptFinished = Clock.System.now())
                    if (result != Success) {
                        logger.warn { "Result was $result for action '${metadata.name}'" }
                    }

                    when (result) {
                        Retry -> {
                            jobs[id] = jobs[id]!!.copy(failedAttempts = metadata.failedAttempts.inc())
                        }

                        Success -> handleSuccess(action, context.jobId)
                        Fail -> handleFail(action, context.jobId)
                    }
                } while (result == Retry && attempt.toInt() <= action.maxRetries)
                if (jobs[id]!!.status == JobStatus.Running) {
                    log("Maximum attempts reached, job failed", id)
                    jobs[id] = jobs[id]!!.copy(status = JobStatus.Failed)
                }
            } catch (e: Exception) {
                logger.info(e) { "Exception when executing job" }
                log(if (e.message == null) "Exception" else "Exception: ${e.message}", id)
                jobs[id] = jobs[id]!!.copy(lastAttemptFinished = Clock.System.now())
                handleFail(action, id)
            }
        }
        return id
    }

    private suspend fun handleSuccess(job: Job<C, V>, jobId: JobId) {
        jobs[jobId] = jobs[jobId]!!.copy(status = JobStatus.Success)
        job.cleanup(jobs[jobId]!!)
    }

    private suspend fun handleFail(job: Job<C, V>, jobId: JobId) {
        jobs[jobId] = jobs[jobId]!!.copy(status = JobStatus.Failed)
        job.cleanup(jobs[jobId]!!)
    }

    suspend fun updateData(value: String, jobId: JobId) {
        jobs[jobId] = jobs[jobId]!!.copy(data = value)
    }

    override fun getAllJobs(): List<JobMetadata> {
        return jobs.toSortedMap().map { it.value }
    }

    override suspend fun awaitCompletion(id: JobId, timeout: Duration): JobMetadata? {
        // Someday: should not poll but return immediately when the job is done
        val startTime = Clock.System.now()
        do {
            val job = jobs[id] ?: throw NoSuchElementException()
            if (job.status.isCompleted) {
                return job
            }
            delay(100)
        } while (Clock.System.now().minus(startTime) < timeout)
        return null
    }

    override fun getJob(id: JobId): JobMetadata {
        return jobs[id] ?: throw NoSuchElementException("Cannot find job with id=$id")
    }

    fun log(message: String, jobId: JobId) {
        logger.info { "Job $jobId: $message" }
        jobs[jobId]!!.log[Clock.System.now()] = message
    }

    fun start() {
    }

    fun stop() {
    }

}

public enum class JobStatus(public val isCompleted: Boolean) {
    New(false),
    Running(false),
    Success(true),
    Backoff(false),
    Failed(true)
}

public data class JobMetadata(
    val id: JobId,
    val name: String,
    val status: JobStatus,
    val created: Instant,
    val lastAttemptStarted: Instant?,
    val lastAttemptFinished: Instant?,
    val data: String,
    val failedAttempts: UByte,
    val log: SortedMap<Instant, String>,
    val maxRetries: Int
)

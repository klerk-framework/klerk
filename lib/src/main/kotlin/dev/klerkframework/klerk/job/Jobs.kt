package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.JobManagerInternal
import dev.klerkframework.klerk.job.JobResult.*
import dev.klerkframework.klerk.misc.IdFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import mu.KotlinLogging

import kotlinx.datetime.Instant
import java.lang.reflect.Method
import java.util.*
import kotlin.concurrent.thread
import kotlin.coroutines.suspendCoroutine
import kotlin.math.pow
import kotlin.reflect.KFunction
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private val logger = KotlinLogging.logger {}

public interface Job {

    public val maxRetries: Int
        get() = 3

    public val delivery: JobDelivery
        get() = JobDelivery.AtLeastOnce

    public val requiredWorkerTags: Set<String>
        get() = emptySet()

    public val tags: Set<String>
        get() = emptySet()

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

public abstract class RunnableJob<C : KlerkContext, V> : Job {
    private var id: JobId? = null

    //public suspend fun run(metadata: JobMetadata, klerk: Klerk<C, V>): JobResult

    /**
     * A reference to the function that should be executed.
     * Note that any exception thrown by the function will be logged and ignored.
     */
    public abstract fun getRunFunction(): suspend (metadata: JobMetadata, klerk: Klerk<C, V>) -> JobResult

    public fun setId(id: JobId) {
        this.id = id
    }

    public fun getMetadata(): JobMetadata {
        val functionName = (getRunFunction() as KFunction<*>).name
        require(functionName.isNotBlank()) { "runFunction must be a function with a name" }
        return JobMetadata(
            id = id ?: throw IllegalStateException("Job must be initialized before creating metadata"),
            className = javaClass.name,
            methodName = functionName,
            status = JobStatus.Scheduled,
            created = Clock.System.now(),
            lastAttemptStarted = null,
            lastAttemptFinished = null,
            nextAttempt = scheduleAt ?: Clock.System.now(),
            state = "",
            failedAttempts = 0,
            maxRetries = maxRetries,
            log = emptyList(),
            parameters = parameters
        )
    }
}

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
     * @param state a string that the job can use to remember its state between runs (e.g. after a failed attempt).
     */
    public sealed class JobResult(
        public val state: String,
        public val log: List<String>,
    ) {
        public class Fail(state: String = "", log: List<String> = emptyList()) : JobResult(state, log)
        public class Yeld(state: String = "", log: List<String> = emptyList()) : JobResult(state, log)
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

                is Yeld -> updatedMeta.copy(
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

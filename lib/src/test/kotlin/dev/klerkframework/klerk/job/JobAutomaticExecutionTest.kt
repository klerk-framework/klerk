package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The rest of the job suite runs on manual execution, which is what makes it deterministic. This one covers the
 * *default* configuration — a background dispatcher on a real clock — because that is what production uses and a bug
 * there would otherwise never be caught.
 *
 * It waits on a condition rather than sleeping for a fixed period, so it is not timing-sensitive; the only real time
 * involved is the timeout that turns a hang into a failure.
 */
class JobAutomaticExecutionTest {

    @Serializable
    data class TickCursor(val remaining: Int)

    object Ticker : JobType.Local<TickCursor, Ctx, Views>() {
        override val name = JobName("ticker")
        override val agent: JobAgent = JobAgent.System

        val stepsRun = AtomicInteger(0)

        override suspend fun step(args: JobStepArgs.Local<TickCursor, Ctx, Views>): JobResult<TickCursor> {
            stepsRun.incrementAndGet()
            if (args.cursor.remaining == 0) {
                return JobResult.Success()
            }
            return JobResult.Yield(cursor = TickCursor(args.cursor.remaining - 1))
        }
    }

    private suspend fun awaitStatus(
        klerk: Klerk<Ctx, Views>,
        id: JobId,
        status: JobStatus,
    ): JobInfo = withTimeout(20.seconds) {
        while (true) {
            val info = klerk.jobs.getJob(id, Ctx.system())
            if (info.status == status) {
                return@withTimeout info
            }
            kotlinx.coroutines.delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @Test
    fun `the background dispatcher runs a yielding job to completion without being told to`() = runBlocking<Unit> {
        Ticker.stepsRun.set(0)
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = Klerk.create(
            createConfig(collections, RamStorage()) {
                execution = JobExecution.Automatic
                register(Ticker)
            }
        )
        klerk.meta.start(installShutdownHook = false)

        val id = klerk.jobs.schedule(Ticker.declare(TickCursor(remaining = 25)), Ctx.system())
        val finished = awaitStatus(klerk, id, JobStatus.Succeeded)

        assertEquals(26, finished.step)
        assertEquals(26, Ticker.stepsRun.get())
        klerk.meta.stop()
    }

    @Test
    fun `stop lets the in-flight step finish and commit its checkpoint`() = runBlocking<Unit> {
        Ticker.stepsRun.set(0)
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val storage = RamStorage()
        val klerk = Klerk.create(
            createConfig(collections, storage) {
                execution = JobExecution.Automatic
                register(Ticker)
            }
        )
        klerk.meta.start(installShutdownHook = false)

        val id = klerk.jobs.schedule(Ticker.declare(TickCursor(remaining = 10_000)), Ctx.system())
        // Let it get going, then stop while it is still nowhere near done.
        withTimeout(20.seconds) {
            while (klerk.jobs.getJob(id, Ctx.system()).step < 3) {
                kotlinx.coroutines.delay(10)
            }
        }
        klerk.meta.stop()

        val persisted = storage.getAllJobs().single { it.id == id }
        assertTrue(persisted.stepNumber >= 3, "the checkpoint of the last finished step must have been committed")
        assertTrue(!persisted.status.isTerminal, "the job is not done; it should be resumable")

        // Nothing keeps running after stop.
        val after = persisted.stepNumber
        kotlinx.coroutines.delay(200)
        assertEquals(after, storage.getAllJobs().single { it.id == id }.stepNumber)
    }
}

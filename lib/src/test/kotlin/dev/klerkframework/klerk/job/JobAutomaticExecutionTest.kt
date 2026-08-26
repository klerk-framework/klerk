package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
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
        val klerk = createKlerk(collections, RamStorage(), jobs = JobSettings(execution = JobExecution.Automatic)) {
            register(Ticker)
        }
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
        val klerk = createKlerk(collections, storage, jobs = JobSettings(execution = JobExecution.Automatic)) {
            register(Ticker)
        }
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

    @Serializable
    data class FanCursor(val children: Int, val awaiting: Boolean = false)

    object Leaf : JobType.Local<FanCursor, Ctx, Views>() {
        override val name = JobName("fan-leaf")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<FanCursor, Ctx, Views>): JobResult<FanCursor> =
            JobResult.Success(result = "leaf")
    }

    object Fan : JobType.Local<FanCursor, Ctx, Views>() {
        override val name = JobName("fan-parent")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<FanCursor, Ctx, Views>): JobResult<FanCursor> {
            if (!args.cursor.awaiting) {
                return JobResult.Yield(
                    cursor = args.cursor.copy(awaiting = true),
                    spawn = (1..args.cursor.children).map { Leaf.declare(FanCursor(0)) },
                    awaitSpawned = true,
                )
            }
            return JobResult.Success(result = "saw ${args.children.size} children")
        }
    }

    /**
     * KNOWN BUG — ignored until the parent-wake mechanism is redesigned.
     *
     * The existing fan-out coverage runs on manual execution, which steps one job at a time and so never has two
     * siblings finishing at once. With the real dispatcher and `maxParallelSteps > 1` they do: every sibling reads the
     * parent's `awaitedChildren` in `planTransition`, which runs before any of their commits are applied, so all of
     * them compute the same decrement. Four children take the count from 4 to 3, the parent never reaches 0, and it
     * waits forever.
     *
     * Passes with `maxParallelSteps = 1`. `childOutcomes` is accumulated the same way and is lost the same way.
     *
     * Fixing it means deciding whether the parent's wake can stay part of the child's own transaction, since the
     * count as an absolute value planned outside the lock is what cannot be made safe.
     */
    @Ignore("Known bug: concurrent siblings each lose the others' decrement of awaitedChildren")
    @Test
    fun `a parent wakes when its last child finishes, even with siblings finishing concurrently`() = runBlocking {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val store = RamStorage()
        val klerk = createKlerk(
            collections,
            store,
            jobs = JobSettings(execution = JobExecution.Automatic, maxParallelSteps = 4),
        ) {
            register(Fan)
            register(Leaf)
        }
        klerk.meta.start(installShutdownHook = false)

        val id = klerk.jobs.schedule(Fan.declare(FanCursor(children = 4)), Ctx.system())
        // If a sibling's decrement is lost, awaitedChildren never reaches zero and this times out.
        awaitStatus(klerk, id, JobStatus.Succeeded)

        val parent = store.getAllJobs().single { it.id == id }
        assertEquals(0, parent.awaitedChildren)
        assertEquals(4, parent.childOutcomes.size, "every child's outcome should have reached the parent")

        val children = klerk.jobs.getAllJobs(Ctx.system()).filter { it.parent == id }
        assertEquals(4, children.size)
        assertTrue(children.all { it.status == JobStatus.Succeeded })
        klerk.meta.stop()
    }
}

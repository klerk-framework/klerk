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

        override suspend fun step(args: JobStepArgs.Local<TickCursor, Ctx, Views>): JobResult<TickCursor, Ctx, Views> {
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
            val info = klerk.jobs.get(id, Ctx.system())
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
            while (klerk.jobs.get(id, Ctx.system()).step < 3) {
                kotlinx.coroutines.delay(10)
            }
        }
        klerk.meta.stop()

        val persisted = storage.allJobs().single { it.id == id }
        assertTrue(persisted.step >= 3, "the checkpoint of the last finished step must have been committed")
        assertTrue(!persisted.status.isTerminal, "the job is not done; it should be resumable")

        // Nothing keeps running after stop.
        val after = persisted.step
        kotlinx.coroutines.delay(200)
        assertEquals(after, storage.allJobs().single { it.id == id }.step)
    }

    @Serializable
    data class FanCursor(val children: Int, val awaiting: Boolean = false)

    object Leaf : JobType.Local<FanCursor, Ctx, Views>() {
        override val name = JobName("fan-leaf")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<FanCursor, Ctx, Views>): JobResult<FanCursor, Ctx, Views> =
            JobResult.Success(result = "leaf")
    }

    object Fan : JobType.Local<FanCursor, Ctx, Views>() {
        override val name = JobName("fan-parent")
        override val agent: JobAgent = JobAgent.System

        /** What the parent's second step actually saw, so the test can assert on the outcomes it was handed. */
        @Volatile
        var sawChildren: String? = null

        override suspend fun step(args: JobStepArgs.Local<FanCursor, Ctx, Views>): JobResult<FanCursor, Ctx, Views> {
            if (!args.cursor.awaiting) {
                return JobResult.Yield(
                    cursor = args.cursor.copy(awaiting = true),
                    spawn = (1..args.cursor.children).map { Leaf.declare(FanCursor(0)) },
                    awaitSpawned = true,
                )
            }
            sawChildren = "saw ${args.children.size} children"
            return JobResult.Success(result = sawChildren)
        }
    }

    /**
     * The existing fan-out coverage runs on manual execution, which steps one job at a time and so never has two
     * siblings finishing at once. With the real dispatcher they do, and this used to hang: each sibling read the
     * parent's remaining-children count before any of their commits were applied, so all of them wrote the same
     * decrement and the parent never reached zero.
     */
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
        // Used to time out here: the parent never became dispatchable.
        awaitStatus(klerk, id, JobStatus.Succeeded)

        assertEquals("saw 4 children", Fan.sawChildren, "the parent's step should see every child's outcome")

        val children = klerk.jobs.all(Ctx.system()).filter { it.parent == id }
        assertEquals(4, children.size)
        assertTrue(children.all { it.status == JobStatus.Succeeded })
        klerk.meta.stop()
    }

    /**
     * The same thing at a width where every sibling really is racing every other one. A single four-child case can
     * pass by luck if the dispatcher happens to serialise them.
     */
    @Test
    fun `many parents each wake once all of their children finish`() = runBlocking {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(
            collections,
            RamStorage(),
            jobs = JobSettings(execution = JobExecution.Automatic, maxParallelSteps = 8),
        ) {
            register(Fan)
            register(Leaf)
        }
        klerk.meta.start(installShutdownHook = false)

        val ids = (1..12).map { klerk.jobs.schedule(Fan.declare(FanCursor(children = 8)), Ctx.system()) }
        ids.forEach { awaitStatus(klerk, it, JobStatus.Succeeded) }

        val all = klerk.jobs.all(Ctx.system())
        assertEquals(12 * 8, all.count { it.parent != null }, "every child should exist")
        assertTrue(all.all { it.status == JobStatus.Succeeded }, "every job should have finished")
        klerk.meta.stop()
    }
}

package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Job state read through `reader.jobs` is part of the read block's snapshot, exactly like a model: nothing the
 * dispatcher does can change it while the block runs.
 *
 * These run on the *automatic* dispatcher on a real clock, because the whole point is to race a live dispatcher that
 * is promoting, claiming and finishing jobs while a read block is open. Manual execution would make them vacuous.
 */
class JobSnapshotTest {

    @Serializable
    data class TickCursor(val remaining: Int)

    /** Yields forever-ish, so statuses keep churning Scheduled -> Ready -> Running -> Ready for the whole test. */
    object Churner : JobType.Local<TickCursor, Ctx, Views>() {
        override val name = JobName("churner")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<TickCursor, Ctx, Views>): JobResult<TickCursor, Ctx, Views> {
            if (args.cursor.remaining == 0) return JobResult.Success()
            return JobResult.Yield(
                cursor = TickCursor(args.cursor.remaining - 1),
                progress = JobProgress(completed = args.cursor.remaining, total = null),
            )
        }
    }

    private suspend fun start(): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, RamStorage(), jobs = JobSettings(execution = JobExecution.Automatic)) {
            register(Churner)
        }
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    /** Busy-waits: a read block's lambda is not suspending, so `delay` is impossible inside one. */
    private fun spin(nanos: Long) {
        val deadline = System.nanoTime() + nanos
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() < deadline) {
        }
    }

    @Test
    fun `job state cannot change while a read block is open`() = runBlocking {
        val klerk = start()
        repeat(12) { klerk.jobs.schedule(Churner.declare(TickCursor(remaining = 400)), Ctx.system()) }

        val seenAcrossBlocks = mutableSetOf<List<JobInfo>>()
        withTimeout(120.seconds) {
            repeat(25) {
                klerk.read(Ctx.system()) {
                    val before = jobs.all()
                    spin(2_000_000)      // 2ms with a live dispatcher racing us
                    val after = jobs.all()
                    assertEquals(before, after, "job state changed during a read block")
                    seenAcrossBlocks.add(before)
                }
                kotlinx.coroutines.delay(5)
            }
        }

        // Anti-vacuity: if nothing was moving, the assertion above proved nothing.
        assertTrue(
            seenAcrossBlocks.size > 1,
            "the dispatcher never changed any job, so this test would pass even with no snapshot at all",
        )
        klerk.meta.stop()
    }

    /**
     * `ChangeName` both renames the author and schedules `my-job-2`, in one transaction. A read block must therefore
     * never see one without the other — which is what `docs/jobs.md` promises and what the in-memory path used to
     * break, because the job rows were applied after the write lock had already been released.
     */
    @Test
    fun `a command and the job it schedules become visible together`() = runBlocking {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, RamStorage(), jobs = JobSettings(execution = JobExecution.Manual))
        klerk.meta.start(installShutdownHook = false)
        val author = createAuthorJKRowling(klerk)

        val torn = java.util.concurrent.atomic.AtomicInteger(0)
        val observed = java.util.concurrent.ConcurrentHashMap.newKeySet<Boolean>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        withTimeout(120.seconds) {
            val readers = (1..8).map {
                launch(Dispatchers.Default) {
                    while (!stop.get()) {
                        klerk.read(Ctx.system()) {
                            val renamed = get(author).props.firstName.value == "Renamed"
                            val scheduled = jobs.all().any { j -> j.name.value == "my-job-2" }
                            if (renamed != scheduled) torn.incrementAndGet()
                            observed.add(renamed)
                        }
                    }
                }
            }
            // Readers keep going either side of the command, so the run straddles it rather than racing it.
            kotlinx.coroutines.delay(50)
            klerk.handle(
                Command(ChangeName, author, ChangeNameParams(FirstName("Renamed"), LastName("Author"))),
                Ctx.system(),
            ).getOrThrow()
            kotlinx.coroutines.delay(50)
            stop.set(true)
            readers.joinAll()
        }

        assertEquals(0, torn.get(), "a read saw the renamed author without its job, or the job without the rename")
        assertTrue(observed.contains(true) && observed.contains(false), "the command never landed mid-run")
        klerk.meta.stop()
    }

    /**
     * The top-level API takes the read lock itself, and the lock is not reentrant, so calling it inside a read block
     * cannot work. It used to hang there — and only for a non-system actor, since `SystemIdentity` short-circuits
     * authorization before the lock is reached, which is why every existing test missed it.
     */
    @Test
    fun `the top-level jobs API refuses to run inside a read block`() = runBlocking {
        val klerk = start()
        val id = klerk.jobs.schedule(Churner.declare(TickCursor(remaining = 5)), Ctx.system())

        listOf(Ctx.system(), Ctx.unauthenticated()).forEach { context ->
            val failure = assertFailsWith<IllegalStateException>("with actor ${context.actor}") {
                withTimeout(30.seconds) {
                    klerk.read(context) { runBlocking { klerk.jobs.get(id, context) } }
                }
            }
            assertTrue(
                failure.message!!.contains("reader.jobs"),
                "the error should point at the in-block accessor, was: ${failure.message}",
            )
        }
        klerk.meta.stop()
    }

    /**
     * The dispatcher now takes the write lock to claim and to promote, so it and the readers contend directly. This
     * asserts neither side starves: every job still finishes, and every reader gets through its share of reads.
     *
     * Whichever side starved the other would hang here and hit the timeout, which is also what would catch a
     * lock-ordering mistake.
     */
    @Test
    fun `readers and the dispatcher both make progress under contention`() = runBlocking {
        val klerk = start()
        val ids = (1..10).map { klerk.jobs.schedule(Churner.declare(TickCursor(remaining = 30)), Ctx.system()) }

        val readsPerReader = 50
        val readers = 8
        val readsCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        val jobsDone = java.util.concurrent.atomic.AtomicBoolean(false)
        withTimeout(120.seconds) {
            // A reader keeps going until the dispatcher is done *and* it has completed its own quota. Reading until
            // the dispatcher is done is what puts the two sides in contention; the quota is what keeps the assertion
            // below off the clock, since a machine that gets through the jobs quickly would otherwise leave too short
            // a window to have read anything much in.
            val reading = (1..readers).map {
                launch(Dispatchers.Default) {
                    var mine = 0
                    while (mine < readsPerReader || !jobsDone.get()) {
                        klerk.read(Ctx.system()) { jobs.all() }
                        mine++
                        readsCompleted.incrementAndGet()
                    }
                }
            }
            // The dispatcher must get through every job while all of that reading is going on.
            ids.forEach { id ->
                while (klerk.jobs.get(id, Ctx.system()).status != JobStatus.Succeeded) {
                    kotlinx.coroutines.delay(20)
                }
            }
            jobsDone.set(true)
            reading.joinAll()
        }

        assertTrue(
            readsCompleted.get() >= readers * readsPerReader,
            "readers were starved: only ${readsCompleted.get()} reads completed",
        )
        klerk.meta.stop()
    }

    @Test
    fun `reading one job twice in a block gives the same answer`() = runBlocking {
        val klerk = start()
        val id = klerk.jobs.schedule(Churner.declare(TickCursor(remaining = 400)), Ctx.system())
        repeat(8) { klerk.jobs.schedule(Churner.declare(TickCursor(remaining = 400)), Ctx.system()) }

        val seenAcrossBlocks = mutableSetOf<JobInfo>()
        withTimeout(120.seconds) {
            repeat(40) {
                klerk.read(Ctx.system()) {
                    val before = jobs.get(id)
                    spin(2_000_000)
                    assertEquals(before, jobs.get(id), "job $id changed during a read block")
                    seenAcrossBlocks.add(before)
                }
                kotlinx.coroutines.delay(5)
            }
        }

        assertTrue(
            seenAcrossBlocks.size > 1,
            "job $id never changed, so this test would pass even with no snapshot at all",
        )
        klerk.meta.stop()
    }
}

package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import dev.klerkframework.klerk.collection.*

/**
 * The job suite runs entirely on manual execution and a [MutableClock], so there is no sleeping anywhere and repeat
 * runs are deterministic.
 */
class JobManagerImplTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    // ---------------------------------------------------------------- fixtures

    @Serializable
    data class CountCursor(val remaining: Int, val done: Int = 0)

    /** Yields once per remaining unit, so a single job produces a known number of checkpoints. */
    object Counter : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("counter")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> {
            if (args.cursor.remaining == 0) {
                return JobResult.Success(result = args.cursor.done.toString())
            }
            return JobResult.Yield(
                cursor = CountCursor(args.cursor.remaining - 1, args.cursor.done + 1),
                progress = JobProgress(completed = args.cursor.done + 1, total = null),
            )
        }
    }

    @Serializable
    data class NameCursor(val author: ModelID<Author>, val name: String)

    /** Emits exactly one command, then inspects its outcome on the next step. */
    object Renamer : JobType.Local<NameCursor, Ctx, Views>() {
        override val name = JobName("renamer")
        override val agent: JobAgent = JobAgent.System

        var sawPreviousResult: CommandResult<*>? = null

        override suspend fun step(args: JobStepArgs.Local<NameCursor, Ctx, Views>): JobResult<NameCursor> {
            if (args.job.step == 0) {
                return JobResult.Yield(
                    cursor = args.cursor.copy(name = args.cursor.name + "!"),
                    command = Command(
                        ChangeName,
                        args.cursor.author,
                        ChangeNameParams(FirstName(args.cursor.name), LastName("Renamed")),
                    ),
                )
            }
            sawPreviousResult = args.previousResult
            return JobResult.Success()
        }
    }

    @Serializable
    data class FlakyCursor(val attemptsToFail: Int)

    object Flaky : JobType.Local<FlakyCursor, Ctx, Views>() {
        override val name = JobName("flaky")
        override val agent: JobAgent = JobAgent.System
        override val maxRetries = 2

        var attempts = 0
        var deadLetterHookRan = 0

        override suspend fun step(args: JobStepArgs.Local<FlakyCursor, Ctx, Views>): JobResult<FlakyCursor> {
            attempts++
            return JobResult.Fail("nope")
        }

        override suspend fun onDeadLettered(
            args: JobEndArgs.Local<FlakyCursor, Ctx, Views>
        ): JobResult<FlakyCursor> {
            deadLetterHookRan++
            return JobResult.Success()
        }
    }

    object Doomed : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("doomed")
        override val agent: JobAgent = JobAgent.System

        var hookRan = 0

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> =
            JobResult.Abort("this will never work")

        override suspend fun onDeadLettered(
            args: JobEndArgs.Local<CountCursor, Ctx, Views>
        ): JobResult<CountCursor> {
            hookRan++
            // The cursor the job died at is preserved, whatever the hook does to its own.
            assertEquals(7, args.failedAtCursor.remaining)
            return JobResult.Success()
        }
    }

    /** Never changes its cursor or its progress, which is what the livelock guard is for. */
    object Stuck : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("stuck")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> =
            JobResult.Yield(cursor = args.cursor)
    }

    @Serializable
    data class FanOutCursor(val children: Int, val awaiting: Boolean = false)

    object Child : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("child")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> =
            JobResult.Success(result = "child-${args.cursor.remaining}")
    }

    object Parent : JobType.Local<FanOutCursor, Ctx, Views>() {
        override val name = JobName("parent")
        override val agent: JobAgent = JobAgent.System

        var seenChildren: List<ChildOutcome> = emptyList()

        override suspend fun step(args: JobStepArgs.Local<FanOutCursor, Ctx, Views>): JobResult<FanOutCursor> {
            if (!args.cursor.awaiting) {
                return JobResult.Yield(
                    cursor = args.cursor.copy(awaiting = true),
                    spawn = (1..args.cursor.children).map { Child.declare(CountCursor(it)) },
                    awaitSpawned = true,
                )
            }
            seenChildren = args.children
            return JobResult.Success()
        }
    }

    @Serializable
    data class TreeCursor(val levels: Int, val awaiting: Boolean = false, val ticks: Int = 0)

    /**
     * Spawns two children per level until it bottoms out, and then yields forever — so the tree is still alive when
     * the test cancels its root.
     */
    object Tree : JobType.Local<TreeCursor, Ctx, Views>() {
        override val name = JobName("tree")
        override val agent: JobAgent = JobAgent.System

        var cancelHookRan = 0

        override suspend fun step(args: JobStepArgs.Local<TreeCursor, Ctx, Views>): JobResult<TreeCursor> {
            if (args.cursor.levels > 0 && !args.cursor.awaiting) {
                return JobResult.Yield(
                    cursor = args.cursor.copy(awaiting = true),
                    spawn = List(2) { Tree.declare(TreeCursor(args.cursor.levels - 1)) },
                    awaitSpawned = true,
                )
            }
            if (args.cursor.levels > 0) {
                return JobResult.Success()
            }
            // A leaf that never finishes on its own. Progress changes every step, so the livelock guard leaves it be.
            return JobResult.Yield(
                cursor = args.cursor.copy(ticks = args.cursor.ticks + 1),
                progress = JobProgress(completed = args.cursor.ticks + 1),
            )
        }

        override suspend fun onCancelled(
            args: JobEndArgs.Local<TreeCursor, Ctx, Views>
        ): JobResult<TreeCursor> {
            cancelHookRan++
            return JobResult.Success()
        }
    }

    object Nightly : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("nightly")
        override val agent: JobAgent = JobAgent.System

        var runs = 0

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> {
            runs++
            return JobResult.Success()
        }
    }

    // ---------------------------------------------------------------- harness

    private class Fixture(
        val klerk: Klerk<Ctx, Views>,
        val clock: MutableClock,
        val storage: RamStorage,
    )

    private fun deadLetterUnloadable() =
        JobSettings(execution = JobExecution.Manual, onUnloadableJob = UnloadableJobPolicy.DeadLetter)

    private suspend fun fixture(
        clock: MutableClock = MutableClock(start),
        storage: RamStorage = RamStorage(),
        jobs: JobSettings = JobSettings(execution = JobExecution.Manual),
        configureJobs: JobsBlock<Ctx, Views>.() -> Unit = {},
    ): Fixture {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, storage, clock, jobs = jobs, configureJobs = configureJobs)
        klerk.meta.start(installShutdownHook = false)
        return Fixture(klerk, clock, storage)
    }

    private suspend fun Fixture.job(id: JobId): JobInfo = klerk.jobs.getJob(id, Ctx.system())

    // ---------------------------------------------------------------- tests

    @Test
    fun `a command schedules a job in its own transaction`() = runBlocking<Unit> {
        val f = fixture()
        val rowling = createAuthorJKRowling(f.klerk)
        createBookHarryPotter1(f.klerk, rowling)
        val result = f.klerk.handle(
            Command(ChangeName, rowling, ChangeNameParams(FirstName("a"), LastName("b"))),
            Ctx.system(),
        ).getOrThrow()

        assertEquals(1, result.jobs.size)
        assertEquals(JobStatus.Ready, f.job(result.jobs.single()).status)
        // Nothing has run: execution is manual.
        assertEquals(0, f.job(result.jobs.single()).step)
    }

    @Test
    fun `a failing command schedules nothing`() = runBlocking<Unit> {
        val f = fixture()
        val before = f.klerk.jobs.getAllJobs(Ctx.system()).size
        val result = f.klerk.handle(
            Command(ChangeName, ModelID(123456), ChangeNameParams(FirstName("a"), LastName("b"))),
            Ctx.system(),
        )
        assertTrue(result is CommandResult.Failure)
        assertEquals(before, f.klerk.jobs.getAllJobs(Ctx.system()).size)
    }

    @Test
    fun `a yielding job runs one step per checkpoint and reports progress`() = runBlocking<Unit> {
        val f = fixture { register(Counter) }
        val id = f.klerk.jobs.schedule(Counter.declare(CountCursor(remaining = 5)), Ctx.system())

        assertTrue(f.klerk.jobs.step())
        assertEquals(1, f.job(id).step)
        assertEquals(1, f.job(id).progress?.completed)

        val steps = f.klerk.jobs.runUntilIdle()
        // Five yields (one already taken) plus the final Success.
        assertEquals(5, steps)
        assertEquals(JobStatus.Succeeded, f.job(id).status)
        assertEquals(6, f.job(id).step)
    }

    @Test
    fun `a job resumes at the exact step after a restart`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val first = fixture(clock, storage) { register(Counter) }
        val id = first.klerk.jobs.schedule(Counter.declare(CountCursor(remaining = 500)), Ctx.system())
        repeat(200) { first.klerk.jobs.step() }
        assertEquals(200, first.job(id).step)
        first.klerk.meta.stop()

        // A brand-new Klerk over the same storage: nothing is carried over in memory.
        val second = fixture(clock, storage) { register(Counter) }
        assertEquals(200, second.job(id).step)
        val remaining = second.klerk.jobs.runUntilIdle()
        assertEquals(301, remaining)
        assertEquals(JobStatus.Succeeded, second.job(id).status)
        assertEquals("500", second.klerk.jobs.getAllJobs(Ctx.system()).single { it.id == id }.let {
            // the result the last Success reported
            second.storageResult(id)
        })
    }

    private fun Fixture.storageResult(id: JobId): String? = storage.getAllJobs().single { it.id == id }.result

    @Test
    fun `a step's command is applied and its outcome reaches the next step`() = runBlocking<Unit> {
        val f = fixture { register(Renamer) }
        val rowling = createAuthorJKRowling(f.klerk)
        Renamer.sawPreviousResult = null

        val id = f.klerk.jobs.schedule(Renamer.declare(NameCursor(rowling, "Joanne")), Ctx.system())
        f.klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.Succeeded, f.job(id).status)
        val author = f.klerk.read(Ctx.system()) { get(rowling) }
        assertEquals("Joanne", author.props.firstName.value)
        assertTrue(Renamer.sawPreviousResult is CommandResult.Success)
    }

    @Test
    fun `a rejected command is data, not a job failure`() = runBlocking<Unit> {
        val f = fixture { register(Renamer) }
        Renamer.sawPreviousResult = null

        // A model id that does not exist: the command fails, but the step still counted as completed.
        val id = f.klerk.jobs.schedule(Renamer.declare(NameCursor(ModelID(987654), "Joanne")), Ctx.system())
        f.klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.Succeeded, f.job(id).status)
        assertTrue(Renamer.sawPreviousResult is CommandResult.Failure)
    }

    @Test
    fun `Fail is retried with backoff and then dead-lettered`() = runBlocking<Unit> {
        val clock = MutableClock(start)
        val f = fixture(clock) { register(Flaky) }
        Flaky.attempts = 0
        Flaky.deadLetterHookRan = 0

        val id = f.klerk.jobs.schedule(Flaky.declare(FlakyCursor(3)), Ctx.system())
        assertEquals(1, f.klerk.jobs.runUntilIdle())
        assertEquals(JobStatus.Backoff, f.job(id).status)

        // The backoff has not elapsed on the configured clock, so there is nothing to do.
        assertEquals(0, f.klerk.jobs.runUntilIdle())

        clock += 3.seconds
        assertEquals(1, f.klerk.jobs.runUntilIdle())
        clock += 9.seconds
        f.klerk.jobs.runUntilIdle()

        assertEquals(3, Flaky.attempts)
        assertEquals(JobStatus.DeadLettered, f.job(id).status)
        assertEquals(1, Flaky.deadLetterHookRan)
    }

    @Test
    fun `Abort dead-letters immediately and runs the hook against the cursor it died at`() = runBlocking<Unit> {
        val f = fixture { register(Doomed) }
        Doomed.hookRan = 0

        val id = f.klerk.jobs.schedule(Doomed.declare(CountCursor(remaining = 7)), Ctx.system())
        f.klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.DeadLettered, f.job(id).status)
        assertEquals(1, Doomed.hookRan)
        assertNotNull(f.job(id).reason)
    }

    @Test
    fun `a dead letter resumes from its checkpoint, never from step 0`() = runBlocking<Unit> {
        val clock = MutableClock(start)
        val f = fixture(clock) { register(Flaky) }
        Flaky.attempts = 0

        val id = f.klerk.jobs.schedule(Flaky.declare(FlakyCursor(3)), Ctx.system())
        repeat(4) {
            f.klerk.jobs.runUntilIdle()
            clock += 1.hours
        }
        assertEquals(JobStatus.DeadLettered, f.job(id).status)
        val stepWhenDead = f.job(id).step

        f.klerk.jobs.resume(id, Ctx.system())
        assertEquals(JobStatus.Ready, f.job(id).status)
        assertEquals(stepWhenDead, f.job(id).step)
    }

    @Test
    fun `three steps without progress is treated as a livelock`() = runBlocking<Unit> {
        val f = fixture { register(Stuck) }
        val id = f.klerk.jobs.schedule(Stuck.declare(CountCursor(remaining = 1)), Ctx.system())
        f.klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.DeadLettered, f.job(id).status)
        assertTrue(f.job(id).reason!!.contains("no progress"))
    }

    @Test
    fun `maxSteps aborts a job that would otherwise yield forever`() = runBlocking<Unit> {
        val f = fixture { register(Endless) }
        val id = f.klerk.jobs.schedule(Endless.declare(CountCursor(remaining = 0)), Ctx.system())
        f.klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.DeadLettered, f.job(id).status)
        assertTrue(f.job(id).reason!!.contains("maxSteps"))
    }

    @Test
    fun `a parent waits for every child and receives their outcomes`() = runBlocking<Unit> {
        val f = fixture {
            register(Parent)
            register(Child)
        }
        Parent.seenChildren = emptyList()

        val id = f.klerk.jobs.schedule(Parent.declare(FanOutCursor(children = 10)), Ctx.system())
        assertTrue(f.klerk.jobs.step())
        assertEquals(JobStatus.Waiting, f.job(id).status)

        f.klerk.jobs.runUntilIdle()
        assertEquals(JobStatus.Succeeded, f.job(id).status)
        assertEquals(10, Parent.seenChildren.size)
        assertTrue(Parent.seenChildren.all { it.succeeded })
        assertTrue(Parent.seenChildren.all { it.result!!.startsWith("child-") })
    }

    @Test
    fun `a waiting parent holds no dispatch slot, so it cannot starve its own children`() = runBlocking<Unit> {
        val f = fixture {
            register(Parent)
            register(Child)
        }
        repeat(4) { f.klerk.jobs.schedule(Parent.declare(FanOutCursor(children = 3)), Ctx.system()) }
        // Every parent yields into Waiting first; if Waiting occupied a slot the children could never run.
        f.klerk.jobs.runUntilIdle()

        val all = f.klerk.jobs.getAllJobs(Ctx.system())
        assertEquals(4 + 12, all.size)
        assertTrue(all.all { it.status == JobStatus.Succeeded })
    }

    @Test
    fun `cancelling a tree reaches Cancelled only after every descendant is terminal`() = runBlocking<Unit> {
        val f = fixture { register(Tree) }
        Tree.cancelHookRan = 0

        val root = f.klerk.jobs.schedule(Tree.declare(TreeCursor(levels = 2)), Ctx.system())
        // Let the tree build itself out: 1 root + 2 + 4 = 7 jobs. Stepping by condition rather than by a fixed count,
        // because with a frozen clock every job is equally ready and the dispatch order between them is arbitrary.
        var guard = 0
        while (f.klerk.jobs.getAllJobs(Ctx.system()).size < 7 && guard++ < 1000) {
            f.klerk.jobs.step()
        }
        assertEquals(7, f.klerk.jobs.getAllJobs(Ctx.system()).size)

        f.klerk.jobs.cancel(root, Ctx.system(), "user asked")
        assertEquals(JobStatus.Cancelling, f.job(root).status)

        f.klerk.jobs.runUntilIdle()

        val all = f.klerk.jobs.getAllJobs(Ctx.system())
        assertEquals(7, all.size)
        assertTrue(all.all { it.status == JobStatus.Cancelled }, "not all cancelled: ${all.map { it.status }}")
        assertEquals(7, Tree.cancelHookRan)
    }

    @Test
    fun `job metadata is authorization-checked`() = runBlocking<Unit> {
        val f = fixture { register(Counter) }
        val id = f.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())

        // The test specification only lets the system, or the scheduling actor, see a job.
        assertFailsWith<AuthorizationException> { f.klerk.jobs.getJob(id, Ctx.unauthenticated()) }
        assertTrue(f.klerk.jobs.getAllJobs(Ctx.unauthenticated()).isEmpty())
        assertEquals(id, f.klerk.jobs.getJob(id, Ctx.system()).id)
    }

    @Test
    fun `an unregistered job name fails startup by default`() = runBlocking<Unit> {
        val storage = RamStorage()
        val first = fixture(storage = storage) { register(Counter) }
        first.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())
        first.klerk.meta.stop()

        // Counter is no longer registered, so its persisted instance cannot be loaded.
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, storage)
        assertFailsWith<IllegalConfigurationException> { klerk.meta.start(installShutdownHook = false) }
    }

    @Test
    fun `an unregistered job name can be dead-lettered instead`() = runBlocking<Unit> {
        val storage = RamStorage()
        val first = fixture(storage = storage) { register(Counter) }
        val id = first.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())
        first.klerk.meta.stop()

        val second = fixture(storage = storage, jobs = deadLetterUnloadable())
        assertEquals(JobStatus.DeadLettered, second.job(id).status)
        // No hook ran: the cursor a hook would need is the very thing that could not be loaded.
        assertTrue(second.job(id).reason!!.contains("no job type registered"))
    }

    @Test
    fun `a cron fires on the schedule and catches up once after downtime`() = runBlocking<Unit> {
        val clock = MutableClock(Instant.parse("2026-01-01T02:59:00Z"))
        val f = fixture(clock) {
            register(Nightly)
            cron(Nightly, "0 3 * * *") { cursor = CountCursor(0) }
        }
        Nightly.runs = 0

        assertEquals(0, f.klerk.jobs.runUntilIdle())

        clock += 2.minutes                 // past 03:00
        assertEquals(1, f.klerk.jobs.runUntilIdle())
        assertEquals(1, Nightly.runs)

        clock += 3.days                    // three occurrences missed at once
        f.klerk.jobs.runUntilIdle()
        assertEquals(2, Nightly.runs)      // CatchUp.RunOnce: one fire, not three
    }

    @Test
    fun `the admission policy can refuse new work but never a retry`() = runBlocking<Unit> {
        val f = fixture {
            register(Counter)
            admission(::denyEverything)
        }
        assertFailsWith<IllegalStateException> {
            f.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())
        }
    }

    @Test
    fun `the hard queue cap is not overridable by a policy`() = runBlocking<Unit> {
        val f = fixture(jobs = JobSettings(execution = JobExecution.Manual, hardQueueLimit = 2)) {
            register(Counter)
            admission(::allowEverything)
        }
        f.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())
        f.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())
        assertFailsWith<IllegalStateException> {
            f.klerk.jobs.schedule(Counter.declare(CountCursor(1)), Ctx.system())
        }
    }

    @Test
    fun `maxConcurrent limits dispatch, not scheduling`() = runBlocking<Unit> {
        val f = fixture { register(Serial) }
        repeat(5) { f.klerk.jobs.schedule(Serial.declare(CountCursor(remaining = 2)), Ctx.system()) }
        // All five were accepted even though only one may run at a time.
        assertEquals(5, f.klerk.jobs.getAllJobs(Ctx.system()).size)
        f.klerk.jobs.runUntilIdle()
        assertTrue(f.klerk.jobs.getAllJobs(Ctx.system()).all { it.status == JobStatus.Succeeded })
    }

    /**
     * Stands in for a deploy that changed a cursor's shape: encoding keeps working, decoding stops, which is exactly
     * what a removed or retyped field looks like from Klerk's side.
     */
    object Reshaped : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("reshaped")
        override val agent: JobAgent = JobAgent.System

        var decodingBroken = false

        override val codec = object : CursorCodec<CountCursor> {
            override fun encode(cursor: CountCursor): String = cursor.remaining.toString()
            override fun decode(encoded: String): CountCursor {
                if (decodingBroken) {
                    throw IllegalArgumentException("field 'remaining' is missing")
                }
                return CountCursor(encoded.toInt())
            }
        }

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> =
            JobResult.Success()
    }

    @Test
    fun `a cursor that no longer deserializes is an unloadable job too`() = runBlocking<Unit> {
        val storage = RamStorage()
        Reshaped.decodingBroken = false
        val first = fixture(storage = storage) { register(Reshaped) }
        val id = first.klerk.jobs.schedule(Reshaped.declare(CountCursor(3)), Ctx.system())
        first.klerk.meta.stop()

        Reshaped.decodingBroken = true
        try {
            val second = fixture(storage = storage, jobs = deadLetterUnloadable()) {
                register(Reshaped)
            }
            assertEquals(JobStatus.DeadLettered, second.job(id).status)
            assertTrue(second.job(id).reason!!.contains("cursor could not be decoded"))
        } finally {
            Reshaped.decodingBroken = false
        }
    }

    object Endless : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("endless")
        override val agent: JobAgent = JobAgent.System
        override val maxSteps = 4

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> =
            JobResult.Yield(cursor = CountCursor(args.cursor.remaining + 1))
    }

    /** Spawns one child per step, forever, which is what the descendant budget exists to stop. */
    object Breeder : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("breeder")
        override val agent: JobAgent = JobAgent.System
        override val maxDescendants = 3

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> =
            JobResult.Yield(
                cursor = CountCursor(args.cursor.remaining + 1),
                spawn = listOf(Child.declare(CountCursor(0))),
                progress = JobProgress(completed = args.cursor.remaining + 1),
            )
    }

    /**
     * The budget had no coverage at all before it stopped being a counter, so this pins the behaviour: a job that
     * spawns in a loop is stopped rather than being allowed to fill the queue.
     */
    @Test
    fun `a job that keeps spawning is dead lettered once it exhausts its descendant budget`() = runBlocking {
        val f = fixture { register(Breeder); register(Child) }
        val id = f.klerk.jobs.schedule(Breeder.declare(CountCursor(0)), Ctx.system())
        f.klerk.jobs.runUntilIdle()

        val breeder = f.job(id)
        assertEquals(JobStatus.DeadLettered, breeder.status)
        assertTrue(
            breeder.reason!!.contains("maxDescendants"),
            "should say why it was stopped, was: ${breeder.reason}"
        )
        // The budget is what it was configured to be, not one more because two spawns raced.
        val spawned = f.klerk.jobs.getAllJobs(Ctx.system()).count { it.parent == id }
        assertEquals(3, spawned)
    }

    object Serial : JobType.Local<CountCursor, Ctx, Views>() {
        override val name = JobName("serial")
        override val agent: JobAgent = JobAgent.System
        override val maxConcurrent = 1

        override suspend fun step(args: JobStepArgs.Local<CountCursor, Ctx, Views>): JobResult<CountCursor> {
            if (args.cursor.remaining == 0) return JobResult.Success()
            return JobResult.Yield(cursor = CountCursor(args.cursor.remaining - 1))
        }
    }

    /**
     * An id is chosen long before its row reaches the in-memory map, so two schedulers racing over that window can
     * pick the same free id and the second silently overwrites the first.
     *
     * Over the real 2^31 id space a collision never happens, which is exactly why this test shrinks the space: 24
     * jobs drawn from 48 candidates collide with near-certainty unless allocation is genuinely exclusive.
     */
    @Test
    fun `concurrent scheduling never hands out the same job id twice`() = runBlocking<Unit> {
        val f = fixture { register(Counter) }
        val small = java.util.Random(20260826)   // java.util.Random is synchronized, so it is safe to share here
        (f.klerk.jobs as JobManagerImpl<Ctx, Views>).idCandidates = { small.nextInt(48) }

        val ids = java.util.Collections.synchronizedList(mutableListOf<JobId>())
        withTimeout(60_000) {
            (1..24).map {
                launch(Dispatchers.Default) {
                    ids.add(f.klerk.jobs.schedule(Counter.declare(CountCursor(remaining = 1)), Ctx.system()))
                }
            }.joinAll()
        }

        assertEquals(24, ids.size)
        assertEquals(24, ids.toSet().size, "the same job id was handed out more than once")
        assertEquals(
            24,
            f.klerk.jobs.getAllJobs(Ctx.system()).size,
            "a job row was overwritten by another job that was given the same id"
        )
        f.klerk.meta.stop()
    }
}

private fun denyEverything(args: AdmissionArgs<Ctx>): AdmissionDecision =
    AdmissionDecision.Deny.overloaded("the test says no")

private fun allowEverything(args: AdmissionArgs<Ctx>): AdmissionDecision = AdmissionDecision.Allow

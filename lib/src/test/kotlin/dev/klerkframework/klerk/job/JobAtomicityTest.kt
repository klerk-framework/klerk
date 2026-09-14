package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import dev.klerkframework.klerk.collection.*

/**
 * The `commitJobStep` contract says a step's command, its checkpoint and any children it spawned land together or not
 * at all. These tests kill the process at every commit boundary of a run and assert that reloading from what was
 * actually persisted always produces a consistent job graph — no command applied twice, none skipped, no duplicated
 * children, no parent waiting on a child that will never report.
 *
 * What this covers is the *scheduler's* half of the contract: that a commit which does not happen leaves the job
 * exactly resumable, and that resuming applies each command exactly once. It cannot verify a storage backend's half —
 * that its transaction really is atomic — because a backend that writes half a commit has already broken the contract
 * and no amount of scheduler logic can repair it. That is why the contract is stated as a requirement on
 * [dev.klerkframework.klerk.storage.Persistence.commitJobStep] rather than defended against here.
 */
class JobAtomicityTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Serializable
    data class WriteCursor(val remaining: Int)

    /** Emits exactly one `CreateAuthor` per step, so the model count is the ground truth for "how many committed". */
    object Writer : JobType.Local<WriteCursor, Ctx, Views>() {
        override val name = JobName("writer")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<WriteCursor, Ctx, Views>): JobResult<WriteCursor> {
            if (args.cursor.remaining == 0) {
                return JobResult.Success()
            }
            return JobResult.Yield(
                cursor = WriteCursor(args.cursor.remaining - 1),
                command = Command(
                    CreateAuthor,
                    CreateAuthorParams(
                        firstName = FirstName("Author"),
                        lastName = LastName("Number${args.cursor.remaining}"),
                        phone = PhoneNumber("+46123456"),
                        secretToken = SecretPasscode(234234902359245345),
                    )
                ),
            )
        }
    }

    @Serializable
    data class SpawnerCursor(val children: Int, val awaiting: Boolean = false)

    object Spawner : JobType.Local<SpawnerCursor, Ctx, Views>() {
        override val name = JobName("spawner")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<SpawnerCursor, Ctx, Views>): JobResult<SpawnerCursor> {
            if (!args.cursor.awaiting) {
                return JobResult.Yield(
                    cursor = args.cursor.copy(awaiting = true),
                    spawn = (1..args.cursor.children).map { Leaf.declare(WriteCursor(0)) },
                    awaitSpawned = true,
                )
            }
            return JobResult.Success(result = args.children.size.toString())
        }
    }

    object Leaf : JobType.Local<WriteCursor, Ctx, Views>() {
        override val name = JobName("leaf")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<WriteCursor, Ctx, Views>): JobResult<WriteCursor> =
            JobResult.Success(result = "done")
    }

    /**
     * A [RamStorage] that throws on the nth commit, simulating a process that dies at exactly that point. Whatever the
     * store did before the throw is what survives — which is the whole point: if `commitJobStep` were not atomic, a
     * half-written commit would be visible here.
     */
    private class CrashingStorage : RamStorage() {
        private var crashOnCommit = 0
        private var commits = 0

        override fun <T : Any, P, C : KlerkContext, V> commitJobStep(
            delta: ProcessingData<out T, C, V>?,
            command: Command<T, P>?,
            context: C?,
            attachedData: dev.klerkframework.klerk.storage.AttachedDataDelta,
            jobs: JobCommit,
            sequenceNumber: Long,
        ) {
            commits++
            if (commits == crashOnCommit) {
                throw SimulatedCrash()
            }
            super.commitJobStep(delta, command, context, attachedData, jobs, sequenceNumber)
        }

        /** Arms the crash for the nth commit from now, so that scheduling the job itself is not the one that dies. */
        fun crashAt(n: Int): CrashingStorage = apply {
            commits = 0
            crashOnCommit = n
        }

        /** Stops crashing, so the same surviving data can be reloaded and driven to completion. */
        fun recovered(): CrashingStorage = apply { crashOnCommit = 0 }
    }

    private class SimulatedCrash : RuntimeException("simulated crash")

    private suspend fun klerkOver(
        storage: RamStorage,
        clock: MutableClock,
    ): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, storage, clock) {
            register(Writer)
            register(Spawner)
            register(Leaf)
        }
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    @Test
    fun `a job killed at any commit boundary resumes without applying a command twice or skipping one`() =
        runBlocking<Unit> {
            val steps = 6
            for (crashAt in 1..steps) {
                val storage = CrashingStorage()
                val clock = MutableClock(start)

                var klerk = klerkOver(storage, clock)
                val id = klerk.jobs.schedule(Writer.declare(WriteCursor(steps - 1)), Ctx.system())
                storage.crashAt(crashAt)
                // Run until the crash. The manager treats a failed commit as "nothing was written" and stops there.
                repeat(steps + 2) { runCatching { klerk.jobs.step() } }
                klerk.meta.stop()

                // Restart over exactly what survived, with a store that no longer crashes.
                val survivor = storage.recovered()
                klerk = klerkOver(survivor, clock)
                klerk.jobs.runUntilIdle()

                val authors = klerk.read(Ctx.system()) { klerk.specification.views.authors.all.asSequence().toList() }
                assertEquals(
                    steps - 1,
                    authors.size,
                    "crashing at commit $crashAt produced ${authors.size} authors instead of ${steps - 1}",
                )
                assertEquals(
                    (steps - 1).downTo(1).map { "Number$it" }.toSet(),
                    authors.map { it.props.lastName.valueWithoutAuthorization }.toSet(),
                    "crashing at commit $crashAt applied a command twice or skipped one",
                )
                assertEquals(JobStatus.Succeeded, klerk.jobs.getJob(id, Ctx.system()).status)
                klerk.meta.stop()
            }
        }

    @Test
    fun `a crash while spawning never produces duplicate children and never strands the parent`() = runBlocking<Unit> {
        for (crashAt in 1..4) {
            val storage = CrashingStorage()
            val clock = MutableClock(start)

            var klerk = klerkOver(storage, clock)
            val id = klerk.jobs.schedule(Spawner.declare(SpawnerCursor(children = 5)), Ctx.system())
            storage.crashAt(crashAt)
            repeat(10) { runCatching { klerk.jobs.step() } }
            klerk.meta.stop()

            klerk = klerkOver(storage.recovered(), clock)
            klerk.jobs.runUntilIdle()

            val all = klerk.jobs.getAllJobs(Ctx.system())
            val children = all.filter { it.parent == id }
            assertTrue(
                children.size == 0 || children.size == 5,
                "crashing at commit $crashAt left ${children.size} children: the spawn was not atomic",
            )
            // The parent must reach a terminal status either way; it must never sit waiting on a child that is gone.
            assertTrue(
                klerk.jobs.getJob(id, Ctx.system()).status.isTerminal,
                "crashing at commit $crashAt stranded the parent in ${klerk.jobs.getJob(id, Ctx.system()).status}",
            )
            klerk.meta.stop()
        }
    }
}

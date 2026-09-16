package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.storage.spi.*
import dev.klerkframework.klerk.testing.runUntilIdle
import dev.klerkframework.klerk.testing.step
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import dev.klerkframework.klerk.view.*

/**
 * Attached data has two independent claims: a reference from a model, and a claim by a job. The orphan reaper deletes
 * a value only when *neither* holds — which is what makes a long-running job's working set safe for as long as the job
 * lives, including while it is dead-lettered and waiting for a human.
 */
class JobAttachedDataClaimTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Serializable
    data class UploadCursor(val prepared: AttachedBlobID? = null, val attach: Boolean = false)

    /**
     * Prepares a blob on its first step and only attaches it to a model on a later one — the window in which nothing
     * but the job claim keeps the value alive.
     */
    object Uploader : JobType.Local<UploadCursor, Ctx, Views>() {
        override val name = JobName("uploader")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<UploadCursor, Ctx, Views>): JobResult<UploadCursor, Ctx, Views> {
            val prepared = args.cursor.prepared
            if (prepared == null) {
                val id = klerkForTest!!.attachedData.prepare(
                    "a portrait".byteInputStream(),
                    AuthorPicture::class,
                    args.context
                )
                return JobResult.Yield(cursor = UploadCursor(prepared = id))
            }
            if (!args.cursor.attach) {
                if (abortAfterPreparing) {
                    return JobResult.Abort("cannot finish, but the prepared data must stay put", runHook = false)
                }
                // Deliberately does nothing for a step, so a test can let the unclaimed-data window elapse here.
                return JobResult.Yield(cursor = args.cursor.copy(attach = true))
            }
            return JobResult.Success(
                command = Command(
                    CreateAuthor,
                    CreateAuthorParams(
                        firstName = FirstName("Astrid"),
                        lastName = LastName("Lindgren"),
                        phone = PhoneNumber("+4699999"),
                        secretToken = SecretPasscode(1),
                        picture = AuthorPicture(prepared),
                    )
                ),
            )
        }

        /** Set by the test before the job runs; a job type is an object, so this is the simplest way in. */
        var klerkForTest: Klerk<Ctx, Views>? = null

        /** Makes the job dead-letter after it has prepared its data, which is the state retention is about. */
        var abortAfterPreparing = false
    }

    private suspend fun start(
        storage: RamStorage = RamStorage(),
        clock: MutableClock = MutableClock(start),
    ): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, storage, clock) { register(Uploader) }
        klerk.meta.start(installShutdownHook = false)
        Uploader.klerkForTest = klerk
        return klerk
    }

    @Test
    fun `data prepared inside a step is claimed by the job and survives the reaper`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val klerk = start(storage, clock)

        val id = klerk.jobs.schedule(Uploader.declare(UploadCursor()), Ctx.system())
        klerk.jobs.step()

        val claimed = storage.readAllAttachedDataMetadata().entries.single()
        assertEquals(id, claimed.value.claimedByJob)

        // Well past the one-minute unclaimed window. Without the job claim this would be reaped.
        clock += 1.hours
        storage.deleteExpiredAttachedData(clock.now())
        assertEquals(1, storage.readAllAttachedDataMetadata().size)

        klerk.jobs.runUntilIdle()
        assertEquals(JobStatus.Succeeded, klerk.jobs.get(id, Ctx.system()).status)

        // The command attached it to a model, so now it is owned as well as claimed.
        val author = klerk.read(Ctx.system()) { klerk.specification.views.authors.all.asSequence().toList() }.single()
        assertNotNull(author.props.picture)
        klerk.meta.stop()
    }

    @Test
    fun `deleting the job releases its claim but never data a model owns`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val klerk = start(storage, clock)

        val id = klerk.jobs.schedule(Uploader.declare(UploadCursor()), Ctx.system())
        klerk.jobs.runUntilIdle()
        val blobId = storage.readAllAttachedDataMetadata().keys.single()

        klerk.jobs.delete(id, Ctx.system())

        val row = storage.readAllAttachedDataMetadata().getValue(blobId)
        assertEquals(null, row.claimedByJob, "the job claim should have been released")
        assertNotNull(row.owner, "the model reference must survive the job's deletion")

        // And the reaper still leaves it alone, because a model owns it.
        clock += 1.hours
        storage.deleteExpiredAttachedData(clock.now())
        assertEquals(1, storage.readAllAttachedDataMetadata().size)
        klerk.meta.stop()
    }

    @Test
    fun `a dead-lettered job keeps its claim, so its working set is still there for a human`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val klerk = start(storage, clock)

        val id = klerk.jobs.schedule(Uploader.declare(UploadCursor()), Ctx.system())
        klerk.jobs.step()                       // prepares the blob
        klerk.jobs.cancel(id, Ctx.system()) // stops it before the data is ever attached
        klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.Cancelled, klerk.jobs.get(id, Ctx.system()).status)
        clock += 1.hours
        storage.deleteExpiredAttachedData(clock.now())
        assertEquals(
            1,
            storage.readAllAttachedDataMetadata().size,
            "a terminal-but-undeleted job still holds its claim",
        )

        // Only deleting the job releases it; then the value is a genuine orphan.
        klerk.jobs.delete(id, Ctx.system())
        storage.deleteExpiredAttachedData(clock.now())
        assertEquals(0, storage.readAllAttachedDataMetadata().size)
        klerk.meta.stop()
    }

    @Test
    fun `deadLetterRetention deletes old dead letters and releases what they claimed`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(
            collections,
            storage,
            clock,
            jobs = JobSettings(execution = JobExecution.Manual, deadLetterRetention = 24.hours)
        ) {
            register(Uploader)
        }
        klerk.meta.start(installShutdownHook = false)
        Uploader.klerkForTest = klerk
        Uploader.abortAfterPreparing = true
        try {
            val id = klerk.jobs.schedule(Uploader.declare(UploadCursor()), Ctx.system())
            klerk.jobs.runUntilIdle()
            assertEquals(JobStatus.DeadLettered, klerk.jobs.get(id, Ctx.system()).status)
            assertEquals(1, storage.allJobs().size)

            clock += 1.hours
            klerk.jobs.runUntilIdle()
            assertEquals(1, storage.allJobs().size, "retention has not elapsed yet")
            assertEquals(1, storage.readAllAttachedDataMetadata().size, "a dead letter keeps its claim")

            clock += 25.hours
            klerk.jobs.runUntilIdle()
            assertEquals(0, storage.allJobs().size, "the dead letter should have been deleted")

            storage.deleteExpiredAttachedData(clock.now())
            assertEquals(0, storage.readAllAttachedDataMetadata().size, "its claim should have been released with it")
        } finally {
            Uploader.abortAfterPreparing = false
        }
        klerk.meta.stop()
    }

    @Test
    fun `cancelledRetention deletes old cancelled jobs and releases what they claimed`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(
            collections,
            storage,
            clock,
            jobs = JobSettings(execution = JobExecution.Manual, cancelledRetention = 24.hours)
        ) {
            register(Uploader)
        }
        klerk.meta.start(installShutdownHook = false)
        Uploader.klerkForTest = klerk

        val id = klerk.jobs.schedule(Uploader.declare(UploadCursor()), Ctx.system())
        klerk.jobs.step()                       // prepares the blob
        klerk.jobs.cancel(id, Ctx.system())
        klerk.jobs.runUntilIdle()
        assertEquals(JobStatus.Cancelled, klerk.jobs.get(id, Ctx.system()).status)

        clock += 1.hours
        klerk.jobs.runUntilIdle()
        assertEquals(1, storage.allJobs().size, "retention has not elapsed yet")
        assertEquals(1, storage.readAllAttachedDataMetadata().size, "a cancelled job keeps its claim")

        clock += 25.hours
        klerk.jobs.runUntilIdle()
        assertEquals(0, storage.allJobs().size, "the cancelled job should have been deleted")

        storage.deleteExpiredAttachedData(clock.now())
        assertEquals(0, storage.readAllAttachedDataMetadata().size, "its claim should have been released with it")
        klerk.meta.stop()
    }

    @Test
    fun `succeededRetention deletes old succeeded jobs`() = runBlocking<Unit> {
        val storage = RamStorage()
        val clock = MutableClock(start)
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(
            collections,
            storage,
            clock,
            jobs = JobSettings(execution = JobExecution.Manual, succeededRetention = 24.hours)
        ) {
            register(Uploader)
        }
        klerk.meta.start(installShutdownHook = false)
        Uploader.klerkForTest = klerk

        val id = klerk.jobs.schedule(Uploader.declare(UploadCursor(attach = true)), Ctx.system())
        klerk.jobs.runUntilIdle()
        assertEquals(JobStatus.Succeeded, klerk.jobs.get(id, Ctx.system()).status)
        assertEquals(1, storage.allJobs().size)

        clock += 1.hours
        klerk.jobs.runUntilIdle()
        assertEquals(1, storage.allJobs().size, "retention has not elapsed yet")

        clock += 25.hours
        klerk.jobs.runUntilIdle()
        assertEquals(0, storage.allJobs().size, "the succeeded job should have been deleted")
        klerk.meta.stop()
    }

    @Test
    fun `deleting a job that is not terminal is refused`() = runBlocking<Unit> {
        val klerk = start()
        val id = klerk.jobs.schedule(Uploader.declare(UploadCursor()), Ctx.system())
        assertFailsWith<IllegalStateException> { klerk.jobs.delete(id, Ctx.system()) }
        klerk.meta.stop()
    }
}

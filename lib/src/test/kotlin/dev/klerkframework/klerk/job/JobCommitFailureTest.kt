package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.CreateAuthorParams
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.LastName
import dev.klerkframework.klerk.PhoneNumber
import dev.klerkframework.klerk.SecretPasscode
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.CommitBatch
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testing.runUntilIdle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class JobCommitFailureTest {

    @Serializable
    data class WriterCursor(val n: Int)

    object Invocations {
        var count = 0
    }

    object Writer : JobType.Local<WriterCursor, Ctx, Views>() {
        override val name = JobName("commit-failure-writer")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(
            args: JobStepArgs.Local<WriterCursor, Ctx, Views>,
        ): JobResult<WriterCursor, Ctx, Views> {
            Invocations.count++
            return JobResult.Yield(
                cursor = WriterCursor(args.cursor.n + 1),
                command = Command(
                    CreateAuthor,
                    CreateAuthorParams(
                        firstName = FirstName("Author"),
                        lastName = LastName("X"),
                        phone = PhoneNumber("+46123456"),
                        secretToken = SecretPasscode(234234902359245345),
                    ),
                ),
            )
        }
    }

    /** Every commit that carries a command fails, like a persistent error in the command would. */
    private class FailingStorage : RamStorage() {
        override fun commitJobStep(batch: CommitBatch) {
            if (batch.eventLogEntry != null) throw RuntimeException("db down")
            super.commitJobStep(batch)
        }
    }

    @Test
    fun `a step whose commit fails counts as a failed attempt`() = runBlocking<Unit> {
        val bookViews = BookViews()
        val klerk = createKlerk(Views(bookViews, AuthorViews(bookViews.all)), FailingStorage()) {
            register(Writer)
        }
        klerk.meta.start(installShutdownHook = false)
        val id = klerk.jobs.schedule(Writer.declare(WriterCursor(0)), Ctx.system())

        klerk.jobs.runUntilIdle(50)

        assertEquals(1, Invocations.count, "the step must wait for its backoff before it runs again")
        val job = klerk.jobs.get(id, Ctx.system())
        assertEquals(1, job.attempt)
        assertEquals(JobStatus.Backoff, job.status)
        assertEquals("Could not commit the step: RuntimeException", job.reason)
        klerk.meta.stop()
    }
}

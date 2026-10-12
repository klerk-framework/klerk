package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.CreateAuthorParams
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DeleteAuthor
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.LastName
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.PhoneNumber
import dev.klerkframework.klerk.SecretPasscode
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testing.runUntilIdle
import dev.klerkframework.klerk.view.asSequence
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class JobFinalCommandTest {

    @Serializable
    data class Target(val author: Int)

    object Deleter : JobType.Local<Target, Ctx, Views>() {
        override val name = JobName("final-command-deleter")
        override val agent: JobAgent = JobAgent.System

        override suspend fun step(args: JobStepArgs.Local<Target, Ctx, Views>): JobResult<Target, Ctx, Views> =
            JobResult.Success(command = Command(DeleteAuthor, ModelID<Author>(args.cursor.author)))
    }

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

    private suspend fun klerk(): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val klerk = createKlerk(Views(bookViews, AuthorViews(bookViews.all)), RamStorage(), clock) {
            register(Deleter)
        }
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    @Test
    fun `a rejected final command fails the attempt instead of succeeding`() = runBlocking<Unit> {
        val klerk = klerk()
        val id = klerk.jobs.schedule(Deleter.declare(Target(424242)), Ctx.system())

        klerk.jobs.runUntilIdle()

        val job = klerk.jobs.get(id, Ctx.system())
        assertEquals(JobStatus.Backoff, job.status)
        assertEquals(1, job.attempt)
        assertTrue(job.reason!!.startsWith("The final command was rejected: "), job.reason)
        klerk.meta.stop()
    }

    @Test
    fun `a final command that keeps being rejected dead-letters the job`() = runBlocking<Unit> {
        val klerk = klerk()
        val id = klerk.jobs.schedule(Deleter.declare(Target(424242)), Ctx.system())

        repeat(Deleter.maxRetries + 3) {
            klerk.jobs.runUntilIdle()
            clock.advance(10.minutes)
        }

        assertEquals(JobStatus.DeadLettered, klerk.jobs.get(id, Ctx.system()).status)
        klerk.meta.stop()
    }

    @Test
    fun `an accepted final command commits with the terminal status`() = runBlocking<Unit> {
        val klerk = klerk()
        val author = klerk.handle(
            Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Author"),
                    lastName = LastName("X"),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(234234902359245345),
                ),
            ),
            Ctx.system(),
        ).getOrThrow().primaryModel!!
        val id = klerk.jobs.schedule(Deleter.declare(Target(author.value)), Ctx.system())

        klerk.jobs.runUntilIdle()

        assertEquals(JobStatus.Succeeded, klerk.jobs.get(id, Ctx.system()).status)
        val authors = klerk.read(Ctx.system()) { klerk.specification.views.authors.all.asSequence().toList() }
        assertTrue(authors.none { it.id == author })
        klerk.meta.stop()
    }
}

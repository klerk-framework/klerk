package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testing.runUntilIdle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class JobLoggerTest {

    @Serializable
    data class Mode(val throws: Boolean)

    object Logging : JobType.Local<Mode, Ctx, Views>() {
        override val name = JobName("logging")
        override val agent: JobAgent = JobAgent.System
        override val maxRetries = 0

        override suspend fun step(args: JobStepArgs.Local<Mode, Ctx, Views>): JobResult<Mode, Ctx, Views> {
            args.logger.info("started")
            args.logger.warn("about to fail")
            if (args.cursor.throws) throw IllegalStateException("boom")
            return JobResult.Fail("failed")
        }
    }

    @Test
    fun `entries are kept when the step fails or throws`() = runBlocking {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, RamStorage(), jobs = JobSettings(execution = JobExecution.Manual)) {
            register(Logging)
        }
        klerk.meta.start(installShutdownHook = false)
        val failing = klerk.jobs.schedule(Logging.declare(Mode(throws = false)), Ctx.system())
        val throwing = klerk.jobs.schedule(Logging.declare(Mode(throws = true)), Ctx.system())
        klerk.jobs.runUntilIdle()

        for (id in listOf(failing, throwing)) {
            val log = klerk.jobs.get(id, Ctx.system()).log
            assertEquals(listOf("started", "about to fail"), log.map { it.message })
            assertEquals(listOf(JobLogLevel.Info, JobLogLevel.Warn), log.map { it.level })
        }
        klerk.meta.stop()
    }
}

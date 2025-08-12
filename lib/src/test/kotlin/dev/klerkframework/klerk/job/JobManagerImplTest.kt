package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class JobManagerImplTest {

    @Test
    fun job() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)
            val result = klerk.handle(
                Command(ChangeName, rowling, ChangeNameParams(FirstName("a"), LastName("b"))),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )
            assertEquals(1, result.orThrow().jobs.size)
        }
    }

    @Test
    @Ignore("This test takes a long time to run")
    fun `jobs can survive restart`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val ram = RamStorage()
            var klerk = Klerk.create(createConfig(collections, ram))
            klerk.meta.start()

            klerk.jobs.schedule(FutureJob())
            delay(3.seconds)
            klerk.meta.stop()
            println("Stopping klerk")

            klerk = Klerk.create(createConfig(collections, ram))
            klerk.meta.start()
            println("Klerk restarted")
            delay(10.seconds)
            assertEquals(1, counter)
            println("Done")
        }
    }

    class FutureJob : RunnableJob<Context, MyCollections>() {

        override val parameters: String = ""

        override val scheduleAt: Instant = Clock.System.now().plus(10.seconds)

        companion object {
            suspend fun myJobFunction(
                metadata: JobMetadata,
                klerk: Klerk<Context, MyCollections>
            ): JobResult {
                println("this is myJobFunction being executed")
                counter++
                return JobResult.Success()
            }
        }

        override fun getRunFunction() = Companion::myJobFunction
    }
}

private var counter = 0

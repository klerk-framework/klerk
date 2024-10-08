package dev.klerkframework.klerk


import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.lang.Thread.sleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class OnEnterTest {

    private val logger = KotlinLogging.logger {}


    @Test
    fun onEventActionIsTriggered() {

        var improvedTriggered = false
        var amateurTriggered = false

        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()

            onEnterAmateurStateActionCallback = {
                amateurTriggered = true
            }

            onEnterImprovingStateActionCallback = {
                improvedTriggered = true
            }

            val rowling = createAuthorJKRowling(klerk)

            val result = klerk.handle(
                Command(
                    ImproveAuthor, rowling, null
                ),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )
                when (result) {
                    is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> fail(result.problem.toString())
                    is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> {
                        sleep(5)
                        assertTrue(amateurTriggered)
                        assertTrue(improvedTriggered)
                        assertEquals(1, result.jobs.size)
                    }
                }
        }
    }
}

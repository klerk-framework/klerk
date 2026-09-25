package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetricsTest {

    @Test
    fun `Commands are counted per event and outcome, dry runs are not`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val bc = BookViews()
        val klerk = Klerk.create(
            createConfig(Views(bc, AuthorViews(bc.all))),
            testSettings().copy(meterRegistry = registry),
        )
        klerk.meta.start()

        val rowling = createAuthorJKRowling(klerk)
        createBookHarryPotter1(klerk, rowling)
        klerk.handle(Command(DeleteAuthor, rowling), Ctx.system())
        klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system(), ProcessingOptions(dryRun = true))

        fun count(event: EventReference, outcome: String) = registry.find("klerk.commands")
            .tags("model", event.modelName, "event", event.eventName, "outcome", outcome)
            .counter()?.count()

        assertEquals(1.0, count(CreateAuthor.id, "success"))
        assertEquals(1.0, count(DeleteAuthor.id, "failure"))
        assertNull(count(DeleteAuthor.id, "success"))
        assertNotNull(registry.find("klerk.models.load").timer())
        assertEquals(2.0, registry.find("klerk.models.count").gauge()?.value())
        klerk.meta.stop()
    }
}

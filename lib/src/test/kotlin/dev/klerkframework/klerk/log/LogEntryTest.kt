package dev.klerkframework.klerk.log

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.SystemIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class LogEntryTest {

    private class Entry(
        override val headingTemplate: String,
        override val contentTemplate: String? = null,
        override val facts: List<Fact> = emptyList(),
        override val actor: ActorIdentity? = SystemIdentity,
    ) : LogEntry {
        override val time: Instant = Clock.System.now()
        override val source: LogSource = LogSource(MajorSource.Application)
        override val kind: String = "Test"
    }

    @Test
    fun `placeholders are replaced by facts and actor`() {
        val entry = Entry(
            headingTemplate = "The model {deletedModel} was deleted by {actor}",
            contentTemplate = "{deletedModel}",
            facts = listOf(Fact(FactType.ModelID, "deletedModel", "Author(id: 123)")),
        )
        assertEquals("The model Author(id: 123) was deleted by $SystemIdentity", entry.heading)
        assertEquals("Author(id: 123)", entry.content)
    }

    @Test
    fun `unknown placeholders are left as they are`() {
        assertEquals("{unknown} and {actor}", Entry("{unknown} and {actor}", actor = null).heading)
        assertEquals(null, Entry("x").content)
    }
}

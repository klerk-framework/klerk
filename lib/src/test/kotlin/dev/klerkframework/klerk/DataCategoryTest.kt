package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataCategory
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DataCategoryTest {

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `a container that is not non-sensitive is masked by toString also when it is readable`() {
        val name = FirstName("Anna")
        val secret = SecretPasscode(1234)
        val title = BookTitle("Dune")

        assertEquals(DataCategory.PersonalData, name.category)
        assertEquals(DataCategory.Sensitive, secret.category)
        assertEquals(DataCategory.NonSensitive, title.category)
        assertEquals("[••••••]", name.toString())
        assertEquals("[••••••]", secret.toString())
        assertEquals("Dune", title.toString())
        assertEquals("Anna", name.value)
        assertEquals(1234, secret.value)
    }

    @Test
    fun `displayText shows the value of a protected container to those who may read it`() {
        assertEquals("Anna", FirstName("Anna").displayText())
        assertEquals("987654", SecretPasscode(987654).displayText())
        assertEquals("Dune", BookTitle("Dune").displayText())

        val denied = FirstName("Anna").also { it.initAuthorization(false) }
        assertEquals("[••••••]", denied.displayText())
    }

    @Test
    fun `a model does not reveal personal data or secrets when printed`() {
        val params = CreateAuthorParams(
            firstName = FirstName("Anna"),
            lastName = LastName("Andersson"),
            phone = PhoneNumber("+46123456"),
            secretToken = SecretPasscode(987654),
        )

        val printed = params.toString()

        assertFalse(printed.contains("Anna"))
        assertFalse(printed.contains("Andersson"))
        assertFalse(printed.contains("987654"))
        assertFalse(printed.contains("46123456"))
    }

    @Test
    fun `a schema field knows the category of its container`() {
        val categories = ObjectSchema.of(CreateAuthorParams::class).fields.associate { it.name to it.category }

        assertEquals(DataCategory.PersonalData, categories["firstName"])
        assertEquals(DataCategory.PersonalData, categories["phone"])
        assertEquals(DataCategory.Sensitive, categories["secretToken"])
        assertEquals(DataCategory.NonSensitive, categories["age"])
        assertEquals(null, categories["favouriteColleague"])
    }

    @Test
    fun `the event log never stores a sensitive parameter but stores personal data`() = runBlocking {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val retention = EventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
        val klerk = Klerk.create(createConfig(views, retention = retention), testSettings(RamStorage(), clock))
        klerk.meta.start()

        val author = klerk.handle(
            Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Anna"),
                    lastName = LastName("Andersson"),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(987654),
                ),
            ),
            Ctx(SystemIdentity, time = clock.now()),
        ).getOrThrow().primaryModel!!

        val params = assertNotNull(klerk.read(Ctx.system()) { eventLog(author) }.get().single().params)
        assertTrue(params.contains("\"secretToken\":\"[redacted]\""), params)
        assertFalse(params.contains("987654"), params)
        assertTrue(params.contains("\"firstName\":\"Anna\""), params)
        assertTrue(params.contains("\"lastName\":\"Andersson\""), params)
        klerk.meta.stop()
    }
}

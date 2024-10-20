package dev.klerkframework.klerk.storage


import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals


class SqlPersistenceTest {

    @Test
    fun `Store and read`() {
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val persistence = SQLiteInMemory.create()
        val config = createConfig(collections, persistence)
        var klerk = Klerk.create(config)
        runBlocking {
            klerk.meta.start()

            generateSampleData(50, 2, klerk)
            val command = Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName("Pelle"),
                    lastName = LastName("Andersson"),
                    phone = PhoneNumber("345"),
                    secretToken = SecretPasscode(99)
                )
            )
            val options = ProcessingOptions(token = CommandToken.simple())
            val result = klerk.handle(command, Context.system(), options)
            val authorRef = requireNotNull(result.orThrow().primaryModel)
            val autorFirstRun = klerk.read(Context.system()) { get(authorRef) }
            klerk.meta.stop()
            klerk = Klerk.create(config)
            klerk.meta.start()
            val authorSecondRun = klerk.read(Context.system()) { get(authorRef) }
            assertEquals(autorFirstRun, authorSecondRun)
            assertEquals(151, ModelCache.count)
            val difference = autorFirstRun.copy(props = autorFirstRun.props.copy(lastName = LastName("Other name")))
            assertNotEquals(difference, authorSecondRun)
        }
    }

}

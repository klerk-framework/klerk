package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.CreateAuthorParams
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DeleteAuthor
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.LastName
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.PhoneNumber
import dev.klerkframework.klerk.SQLiteInMemory
import dev.klerkframework.klerk.SecretPasscode
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.createConfig
import dev.klerkframework.klerk.generateSampleData
import dev.klerkframework.klerk.testSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlPersistenceTest {

    @Test
    fun `Store and read`() {
        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val persistence = SQLiteInMemory.create()
        val specification = createConfig(collections)
        var klerk = Klerk.create(specification, testSettings(persistence))
        runBlocking {
            klerk.meta.start()

            generateSampleData(50, 2, klerk)
            val command = Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Pelle"),
                    lastName = LastName("Andersson"),
                    phone = PhoneNumber("345"),
                    secretToken = SecretPasscode(99),
                ),

            )
            val options = ProcessingOptions(token = CommandToken.simple())
            val result = klerk.handle(command, Ctx.system(), options)
            val authorRef = requireNotNull(result.getOrThrow().primaryModel)
            val autorFirstRun = klerk.read(Ctx.system()) { get(authorRef) }
            klerk.meta.stop()
            klerk = Klerk.create(specification, testSettings(persistence))
            klerk.meta.start()
            val authorSecondRun = klerk.read(Ctx.system()) { get(authorRef) }
            assertEquals(autorFirstRun, authorSecondRun)
            assertEquals(151, ModelCache.count)
            val difference = autorFirstRun.copy(props = autorFirstRun.props.copy(lastName = LastName("Other name")))
            assertNotEquals(difference, authorSecondRun)
        }
    }

    @Test
    fun `readModel returns the same model that readAllModels does`() {
        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val persistence = SQLiteInMemory.create()
        val klerk = Klerk.create(createConfig(collections), testSettings(persistence))
        runBlocking {
            klerk.meta.start()
            generateSampleData(10, 2, klerk)

            val all = mutableMapOf<Int, Model<out Any>>()
            persistence.readAllModels { all[it.id.value] = it }
            assertTrue(all.size > 1)

            all.forEach { (id, expected) ->
                assertEquals(expected, persistence.readModel(id), "readModel disagreed with readAllModels for $id")
            }
            assertNull(persistence.readModel(all.keys.max() + 1_000))
            klerk.meta.stop()
        }
    }

    @Test
    fun `readModel reflects deletions`() {
        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val persistence = SQLiteInMemory.create()
        val klerk = Klerk.create(createConfig(collections), testSettings(persistence))
        runBlocking {
            klerk.meta.start()
            val command = Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Pelle"),
                    lastName = LastName("Andersson"),
                    phone = PhoneNumber("345"),
                    secretToken = SecretPasscode(99),
                ),

            )
            val authorRef = requireNotNull(
                klerk.handle(command, Ctx.system()).getOrThrow().primaryModel,
            )
            assertNotNull(persistence.readModel(authorRef.value))

            klerk.handle(
                Command(DeleteAuthor, authorRef),
                Ctx.system(),
            ).getOrThrow()
            assertNull(persistence.readModel(authorRef.value))
            klerk.meta.stop()
        }
    }
}

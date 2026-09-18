package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.Address
import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.LastName
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Street
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createConfig
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

class ReaderTest {

    @Test
    fun `Create, update, delete with force`() {
        runBlocking {
            val ramStorage = RamStorage()
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = Klerk.create(
                createConfig(collections),
                testSettings(ramStorage).copy(allowUnsafeOperations = true),
            )
            klerk.meta.start()

            val ref = ModelID<Author>(12)

            val originalAuthorProps = Author(
                FirstName("Anakin"),
                LastName("Skywalker"),
                Address(Street("Tatooine")),
                picture = null,
            )
            val originalAuthor = Model(
                id = ref,
                createdAt = Clock.System.now(),
                lastPropsUpdatedAt = Clock.System.now(),
                lastStateTransitionAt = Clock.System.now(),
                state = "test",
                timeTrigger = null,
                props = originalAuthorProps,
            )

            klerk.unsafe.create(originalAuthor, Ctx.unauthenticated())

            val storedOriginal = klerk.read(Ctx.unauthenticated()) { get(ref) }
            assertEquals(storedOriginal, originalAuthor)
            val updatedAuthorProps =
                originalAuthorProps.copy(firstName = FirstName("Darth"), lastName = LastName("Vader"))
            val updatedAuthor = originalAuthor.copy(state = "updated", props = updatedAuthorProps)
            klerk.unsafe.update(updatedAuthor, Ctx.unauthenticated())
            val storedUpdated = klerk.read(Ctx.unauthenticated()) { get(ref) }
            assertNotEquals(storedUpdated.state, storedOriginal.state)
            assertNotEquals(storedUpdated.props, storedOriginal.props)

            klerk.unsafe.delete(ref, Ctx.unauthenticated())
            val storedDeleted = klerk.read(Ctx.unauthenticated()) { getOrNull(ref) }
            assertNull(storedDeleted)
        }
    }

    @Test
    fun `Cannot bypass auth rules by reading a model as another type`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)
            klerk.read(Ctx.unauthenticated()) {
                try {
                    get(astrid)
                    fail()
                } catch (e: Exception) {
                    assertTrue { e is AuthorizationException }
                }

                try {
                    val badId = ModelID<Book>(astrid.value)
                    get(badId)
                    fail()
                } catch (e: Exception) {
                    assertTrue { e is AuthorizationException }
                }
            }
        }
    }
}

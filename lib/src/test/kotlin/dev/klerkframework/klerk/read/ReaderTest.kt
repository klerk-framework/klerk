package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.*


class ReaderTest {

    @Test
    fun `Create, update, delete with force`() {
        runBlocking {
            val ramStorage = RamStorage()
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, ramStorage), settings = KlerkSettings(allowUnsafeOperations = true))
            klerk.meta.start()

            val ref = ModelID<Author>(12)

            val originalAuthorProps = Author(FirstName("Anakin"), LastName("Skywalker"), Address(Street("Tatooine")))
            val originalAuthor = Model(
                id = ref,
                createdAt = Clock.System.now(),
                lastPropsUpdateAt = Clock.System.now(),
                lastStateTransitionAt = Clock.System.now(),
                state = "test",
                timeTrigger = null,
                props = originalAuthorProps
            )

            klerk.models.unsafeCreate(Context.unauthenticated(), originalAuthor)

            val storedOriginal = klerk.read(Context.unauthenticated()) { get(ref) }
            assertEquals(storedOriginal, originalAuthor)
            val updatedAuthorProps =
                originalAuthorProps.copy(firstName = FirstName("Darth"), lastName = LastName("Vader"))
            val updatedAuthor = originalAuthor.copy(state = "updated", props = updatedAuthorProps)
            klerk.models.unsafeUpdate(Context.unauthenticated(), updatedAuthor)
            val storedUpdated = klerk.read(Context.unauthenticated()) { get(ref) }
            assertNotEquals(storedUpdated.state, storedOriginal.state)
            assertNotEquals(storedUpdated.props, storedOriginal.props)

            klerk.models.unsafeDelete(Context.unauthenticated(), ref)
            val storedDeleted = klerk.read(Context.unauthenticated()) { getOrNull(ref) }
            assertNull(storedDeleted)
        }
    }

    @Test
    fun `Cannot bypass auth rules by reading a model as another type`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)
            klerk.read(Context.unauthenticated()) {
                try {
                    get(astrid)
                    fail()
                } catch (e: Exception) {
                    assertTrue { e is AuthorizationException }
                }

                try {
                    val badId = ModelID<Book>(astrid.toInt())
                    get(badId)
                    fail()
                } catch (e: Exception) {
                    assertTrue { e is AuthorizationException }
                }
            }
        }
    }

}

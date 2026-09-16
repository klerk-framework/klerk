package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.testing.runUntilIdle
import dev.klerkframework.klerk.testing.step
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*
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

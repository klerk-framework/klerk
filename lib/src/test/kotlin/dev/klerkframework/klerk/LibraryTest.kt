package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.webutils.generateSampleData
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlin.test.*


class LibraryTest {

    private val logger = KotlinLogging.logger {}

    @Test
    fun read() {

        runBlocking {
            val ramStorage = RamStorage()
            val bc = BookCollections()
            var views = MyCollections(bc, AuthorCollections(bc.all))
            var klerk = Klerk.create(createConfig(views, ramStorage))
            klerk.meta.start()

            val context = Context.system()

            val somethingNull: Model<Book>? = klerk.read(context) {
                firstOrNull(data.books.all) { true }
            }

            assertNull(somethingNull)

            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)

            val somethingNotNull: Model<Book>? = klerk.read(context) {
                firstOrNull(data.books.all) { true }
            }
            assertNotNull(somethingNotNull)


            generateSampleData(100, 3, klerk)
            val modelsInCache = ModelCache.count

            // restart and now read from database
            views = MyCollections(BookCollections(), AuthorCollections(bc.all))
            klerk = Klerk.create(createConfig(views, ramStorage))
            klerk.meta.start()

            val somethingNotNullAgain: Model<Book>? = klerk.read(context) {
                firstOrNull(data.books.all) { true }
            }
            assertNotNull(somethingNotNullAgain)

            assertEquals(modelsInCache, ModelCache.count)

        }
    }

    @Test
    fun updateModel() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            klerk.handle(
                Command(ChangeName, rowling, ChangeNameParams(FirstName("a"), LastName("b"))),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )
            val updatedAuthor = klerk.read(Context.system()) { get(rowling) }
            assertEquals("a", updatedAuthor.props.firstName.value)
            assertEquals("b", updatedAuthor.props.lastName.value)
        }
    }
}

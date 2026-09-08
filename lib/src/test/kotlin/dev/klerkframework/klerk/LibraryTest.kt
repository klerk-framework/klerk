package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import dev.klerkframework.klerk.collection.*


class LibraryTest {

    private val logger = KotlinLogging.logger {}

    @Test
    fun read() {

        runBlocking {
            val ramStorage = RamStorage()
            val bc = BookViews()
            var views = Views(bc, AuthorViews(bc.all))
            var klerk = createKlerk(views, ramStorage)
            klerk.meta.start()

            val context = Ctx.system()

            val somethingNull: Model<Book>? = klerk.read(context) {
                views.books.all.asSequence().firstOrNull { true }
            }

            assertNull(somethingNull)

            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)

            val somethingNotNull: Model<Book>? = klerk.read(context) {
                views.books.all.asSequence().firstOrNull { true }
            }
            assertNotNull(somethingNotNull)


            generateSampleData(100, 3, klerk)
            val modelsInCache = ModelCache.count

            // restart and now read from database
            views = Views(BookViews(), AuthorViews(bc.all))
            klerk = createKlerk(views, ramStorage)
            klerk.meta.start()

            val somethingNotNullAgain: Model<Book>? = klerk.read(context) {
                views.books.all.asSequence().firstOrNull { true }
            }
            assertNotNull(somethingNotNullAgain)

            assertEquals(modelsInCache, ModelCache.count)

        }
    }

    @Test
    fun updateModel() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            klerk.handle(
                Command(ChangeName, rowling, ChangeNameParams(FirstName("a"), LastName("b"))),
                Ctx.system(),
                ProcessingOptions(CommandToken.simple())
            )
            val updatedAuthor = klerk.read(Ctx.system()) { get(rowling) }
            assertEquals("a", updatedAuthor.props.firstName.value)
            assertEquals("b", updatedAuthor.props.lastName.value)
        }
    }
}

suspend fun generateSampleData(numberOfAuthors: Int, booksPerAuthor: Int, klerk: Klerk<Ctx, Views>) {

    val startTime = kotlin.time.Clock.System.now()
    val firstNames = setOf("Anna", "Bertil", "Janne", "Filip")
    val lastNames = setOf("Andersson", "Svensson", "Törnkrantz")
    val cities = setOf("Malmö, Göteborg, Falun", "Stockholm")

    for (i in 1..numberOfAuthors) {
        val result = klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName(firstNames.random()),
                    lastName = LastName(i.toString()),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(23290409),
                    //address = Address(Street("Lugna gatan"))
                ),
            ),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        )

        if (i % 10 == 0) {
            klerk.handle(
                Command(
                    event = ImproveAuthor,
                    model = result.orThrow().primaryModel,
                    params = null
                ),
                context = Ctx.system(),
                ProcessingOptions(
                    CommandToken.simple()
                )
            )
        }

        val authorRef = requireNotNull(result.orThrow().primaryModel)
        println("Author: $authorRef")

        val author = klerk.read(Ctx.system()) { get(authorRef) }

        for (j in 1..booksPerAuthor) {
            klerk.handle(
                Command(
                    event = CreateBook,
                    model = null,
                    params = CreateBookParams(
                        title = BookTitle("Book $j"),
                        author = authorRef,
                        coAuthors = emptySet(),
                        previousBooksInSameSeries = emptyList(),
                        tags = setOf(BookTag("Fiction"), BookTag("Children")),
                        averageScore = AverageScore(0f),
                        readingTime = ReadingTime(1.days)
                    ),
                ),
                Ctx.system(),
                ProcessingOptions(CommandToken.simple())
            )
        }
    }
    val seconds = startTime.minus(kotlin.time.Clock.System.now()).inWholeSeconds
    val eventsPerSecond = if (seconds > 0) (numberOfAuthors * (booksPerAuthor + 1)) / seconds else "?"
    logger.info { "Created $numberOfAuthors authors in $seconds s ($eventsPerSecond events/s)" }
}

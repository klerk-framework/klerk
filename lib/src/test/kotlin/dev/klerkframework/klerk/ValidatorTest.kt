package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*


class ValidatorTest {

    private var klerk: Klerk<Context, MyCollections>

    init {
        val bc = BookViews()
        val collections = MyCollections(bc, AuthorViews(bc.all))
        val config = createConfig(collections, RamStorage())
        klerk = Klerk.create(config)
    }

    @BeforeTest
    fun setup() {
        runBlocking {
            klerk.meta.start()
        }
    }

    @AfterTest
    fun tearDown() {
        klerk.meta.stop()
    }

    @Test
    fun `Validates parameters as a whole`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Mike"),
                lastName = LastName("Litoris"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(
                CreateAuthor,
                null,
                params
            )
            val options = ProcessingOptions(CommandToken.simple())
            when (val result = klerk.handle(command, Context.system(), options)) {
                is CommandResult.Failure -> assertTrue(result.problems.first().violatedRule == null)
                is CommandResult.Success -> fail()
            }
        }
    }

    @Test
    fun `Validates context`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Pelle"),
                lastName = LastName("Svensson"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(
                CreateAuthor,
                null,
                params
            )
            val options = ProcessingOptions(CommandToken.simple())
            val result = klerk.handle(command, Context.unauthenticated(), options)
            assertTrue(result is CommandResult.Failure, result.toString())
        }
    }

    @Test
    fun `Validates parameters and context`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Daniel"),
                lastName = LastName("Svensson"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(
                CreateAuthor,
                null,
                params
            )
            val options = ProcessingOptions(CommandToken.simple())
            val result = klerk.handle(command, Context.system(), options)
            assertTrue(result is CommandResult.Failure)
        }
    }

    @Test
    fun `Validates enum parameter against validEnums`() {
        runBlocking {
            val author = createAuthorJKRowling(klerk)
            val params = CreateBookParams(
                title = BookTitle("Harry Potter"),
                author = author,
                coAuthors = emptySet(),
                previousBooksInSameSeries = emptyList(),
                tags = emptySet(),
                averageScore = AverageScore(0f),
                readingTime = ReadingTime(kotlin.time.Duration.ZERO),
                genre = BookGenreContainer(BookGenre.Mystery)
            )
            val command = Command(CreateBook, null, params)
            val options = ProcessingOptions(CommandToken.simple())
            // BookStatemachine declares validEnums(CreateBookParams::genre, BookGenre.entries.toSet())
            // so Mystery should be valid (all entries allowed)
            val result = klerk.handle(command, Context.system(), options)
            assertTrue(result is CommandResult.Success, "Expected success but got: $result")
        }
    }

    @Test
    fun `Rejects enum parameter not in validEnums`() {
        runBlocking {
            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val restrictedSm: StateMachine<Book, BookStates, Context, MyCollections> = stateMachine {
                event(CreateBook) {
                    validReferences(CreateBookParams::author, collections.authors.all)
                    // Only Fiction is valid, not Mystery or Fantasy
                    validEnums(CreateBookParams::genre, setOf(BookGenre.Fiction))
                }
                event(PublishBook) {}
                event(DeleteBook) {}
                voidState {
                    onEvent(CreateBook) { createModel(BookStates.Draft, ::newBook) }
                }
                state(BookStates.Draft) {
                    onEvent(PublishBook) { transitionTo(BookStates.Published) }
                    onEvent(DeleteBook) { delete() }
                }
                state(BookStates.Published) {
                    onEvent(DeleteBook) { delete() }
                }
            }
            val config = ConfigBuilder<Context, MyCollections>(collections).build {
                managedModels {
                    model(Book::class, restrictedSm, collections.books)
                    model(Author::class, authorStateMachine(collections), collections.authors)
                }
                apply(generousAuthRules())
                persistence(RamStorage())
                systemContextProvider { systemIdentity -> Context(systemIdentity) }
            }
            val restrictedKlerk = Klerk.create(config)
            restrictedKlerk.meta.start()
            val author = createAuthorJKRowling(restrictedKlerk)

            val params = CreateBookParams(
                title = BookTitle("Harry Potter"),
                author = author,
                coAuthors = emptySet(),
                previousBooksInSameSeries = emptyList(),
                tags = emptySet(),
                averageScore = AverageScore(0f),
                readingTime = ReadingTime(kotlin.time.Duration.ZERO),
                genre = BookGenreContainer(BookGenre.Mystery)  // not in validEnums
            )
            val command = Command(CreateBook, null, params)
            val options = ProcessingOptions(CommandToken.simple())
            val result = restrictedKlerk.handle(command, Context.system(), options)
            assertTrue(result is CommandResult.Failure, "Expected failure but got: $result")
            restrictedKlerk.meta.stop()
        }
    }

    @Test
    fun mittTest() {
        val a = IntWithTwoOrFourOrThree(2)
        a.initAuthorization(false)
        println("Klar: ${a.valueOrNullIfNotAuthorized}")
    }
}


open class EvenIntContainer(value: Int) : IntContainer(value) {

    init {
        require(value % 2 == 0) { "Value must be even" }
        println("A")
    }

    override val min: Int = 0

    override val max: Int = 100

}

class IntWithTwoOrFourOrThree(value: Int) : EvenIntContainer(value) {
    init {
        require(value == 2 || value == 3 || value == 4) { "Value must be 2 or 4" }
        println("B")
    }

    override val min: Int = 0

    override val max: Int = 100

}

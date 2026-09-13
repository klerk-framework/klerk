package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.VoidEventRulesWithParameters
import dev.klerkframework.klerk.statemachine.stateMachine
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration

/** Values in collections and nested objects get the same treatment as top-level properties. */
class NestedPropsTest {

    @Test
    fun `A container in a collection parameter is validated`() = runBlocking {
        val klerk = createKlerk(Views(BookViews(), AuthorViews(BookViews().all)))
        klerk.meta.start()
        val author = createAuthorJKRowling(klerk)

        val result = createBook(klerk, bookParams(author).copy(tags = setOf(BookTag("x".repeat(101)))))

        val problem = assertIs<CommandResult.Failure<*>>(result).problems.single()
        assertEquals("tags[0]", assertIs<InvalidPropertyProblem>(problem).propertyName)
        klerk.meta.stop()
    }

    @Test
    fun `A container in a nested object is validated`() {
        val klerk = createKlerk(Views(BookViews(), AuthorViews(BookViews().all)))
        val author = Author(FirstName("Astrid"), LastName("Lindgren"), Address(Street("")), null)

        val problems = klerk.impl().validator.validateDataContainers(author, DefaultTranslation)

        assertEquals(listOf("address.street"), problems.map { it.propertyName })
    }

    @Test
    fun `Every id in a collection parameter must be in its declared view`() = runBlocking {
        val bookViews = BookViews()
        val klerk = createKlerk(Views(bookViews, AuthorViews(bookViews.all)))
        klerk.meta.start()
        val author = createAuthorJKRowling(klerk)
        val book = createBookHarryPotter1(klerk, author)

        val notAnAuthor = ModelID<Author>(book.value)
        val result = createBook(klerk, bookParams(author).copy(coAuthors = setOf(author, notAnAuthor)))

        val problem = assertIs<CommandResult.Failure<*>>(result).problems.single()
        assertEquals("coAuthors[1]", assertIs<InvalidPropertyProblem>(problem).propertyName)
        klerk.meta.stop()
    }

    @Test
    fun `A collection of ids needs validReferences`() {
        val e = assertFailsWith<IllegalConfigurationException> {
            startWithBookRules { views ->
                validReferences(CreateBookParams::author, views.authors.all)
                validReferences(CreateBookParams::previousBooksInSameSeries, views.books.all)
            }
        }
        assertTrue(e.message!!.contains("'coAuthors'"), e.message)
    }

    @Test
    fun `A rule for a property that is not in the parameters is rejected`() {
        val e = assertFailsWith<IllegalConfigurationException> {
            startWithBookRules { views ->
                validReferences(CreateBookParams::author, views.authors.all)
                validReferences(CreateBookParams::coAuthors, views.authors.all)
                validReferences(CreateBookParams::previousBooksInSameSeries, views.books.all)
                validEnums(Book::genre, BookGenre.entries.toSet())
            }
        }
        assertTrue(e.message!!.contains("validEnums(Book::genre"), e.message)
    }

    @Test
    fun `A rule that is a lambda is rejected`() {
        val e = assertFailsWith<IllegalConfigurationException> {
            startWithBookRules { views ->
                validReferences(CreateBookParams::author, views.authors.all)
                validReferences(CreateBookParams::coAuthors, views.authors.all)
                validReferences(CreateBookParams::previousBooksInSameSeries, views.books.all)
                validateWithParameters { PropertyCollectionValidity.Valid }
            }
        }
        assertEquals(KlerkErrorCode.RuleMustBeNamed, e.code)
        assertTrue(e.message!!.contains("CreateBook"), e.message)
    }

    private fun bookParams(author: ModelID<Author>) = CreateBookParams(
        title = BookTitle("Emil"),
        author = author,
        averageScore = AverageScore(0f),
        readingTime = ReadingTime(Duration.ZERO),
    )

    private suspend fun createBook(klerk: Klerk<Ctx, Views>, params: CreateBookParams) =
        klerk.handle(Command(CreateBook, null, params), Ctx.system())

    private fun startWithBookRules(
        rules: VoidEventRulesWithParameters<Book, CreateBookParams, Ctx, Views>.(Views) -> Unit
    ) = runBlocking {
        val bookViews = BookViews()
        val views = Views(bookViews, AuthorViews(bookViews.all))
        val bookStateMachine: StateMachine<Book, BookStates, Ctx, Views> = stateMachine {
            event(CreateBook) { rules(views) }
            event(DeleteBook) {}
            voidState {
                onEvent(CreateBook) { createModel(BookStates.Draft, ::newBook) }
            }
            state(BookStates.Draft) {
                onEvent(DeleteBook) { delete() }
            }
            state(BookStates.Published) {}
        }
        val specification = SpecificationBuilder<Ctx, Views>(views).build {
            systemContextProvider { Ctx(actor = SystemIdentity) }
            jobContextProvider(::myJobContextProvider)
            jobs { }
            managedModels {
                model(Book::class, bookStateMachine, views.books)
                model(Author::class, authorStateMachine(views), views.authors)
            }
            authorization {
                readModels { positive { rule(::`Everybody can read`) } }
                readProperties { positive { rule(::canReadAllProperties) } }
                commands { positive { rule(::`Everybody can do everything`) } }
                eventLog { positive { rule(::`Everybody can read event log`) } }
                readAttachedData { positive { rule(::onlyTheAuthorsOwnerCanReadThePicture) } }
                writeAttachedData { positive { rule(::everybodyCanPrepareAttachedData) } }
                jobs { positive { rule(::authorsCanSeeTheirOwnJobs) } }
            }
        }
        val klerk = Klerk.create(specification, testSettings())
        klerk.meta.start()
        klerk.meta.stop()
    }
}

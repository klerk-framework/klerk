package dev.klerkframework.klerk


import dev.klerkframework.klerk.collection.asSequence
import dev.klerkframework.klerk.collection.query
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.runBlocking
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Clock


class ReaderTest {


    @Test
    fun getTypedRelationsTest() {

        runBlocking {

            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()

            val rowling = createAuthorJKRowling(klerk)

            val astrid = createAuthorAstrid(klerk)

            val context = Ctx.system()

            val model = klerk.read(context) { get(rowling) }
            assertEquals(model.props.firstName.value, "J.K")

            val harryPotter1 = createBookHarryPotter1(klerk, rowling)

            val harryPotter2 = createBookHarryPotter2(klerk, rowling, listOf(harryPotter1), setOf(astrid))

            klerk.read(context) {
                assertEquals(2, getAllRelatedIds(rowling).size)

                val booksRelatedToRowling = getRelated(Book::class, rowling)
                assertEquals(2, booksRelatedToRowling.size)
                assertEquals(
                    "Harry Potter and the Philosopher's Stone",
                    booksRelatedToRowling.first().props.title.value
                )

                val authorsRelatedToRowling = getRelated(Author::class, rowling)
                assert(authorsRelatedToRowling.isEmpty())

                val booksWhereRowlingIsAuthor = getRelated(Book::author, rowling)
                assertEquals(2, booksWhereRowlingIsAuthor.size)

                val booksWhereLinusIsAuthor = getRelated(Book::author, astrid)
                assertEquals(0, booksWhereLinusIsAuthor.size)

                val booksWhereLinusIsCoAuthor = getRelatedInCollection(Book::coAuthors, astrid)
                assertEquals(1, booksWhereLinusIsCoAuthor.size)
                assertEquals(
                    "Harry Potter and the Chamber of Secrets",
                    booksWhereLinusIsCoAuthor.first().props.title.value
                )
            }

        }
    }

    @Test
    fun verifyThatEqualsCanBeUsed() {
        val id = ModelID<Author>(3)
        assert(Test1::author == Test1::author)
        assert(Test1::author != Test2::author)
        val test1 = Test1(id)
        val test2 = Test2(id)
        assert(test1::class.memberProperties.first() == Test1::author)
        assert(test1::class.memberProperties.first() != Test2::author)
        assert(test1::class.memberProperties.first() != test2::class.memberProperties.first())
    }

    @Test
    fun actorCanBePassedIntoTheReader() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()

            val rowling = createAuthorJKRowling(klerk)
            val context = Ctx.system()

            try {
                val model = klerk.read(context) { get(rowling) }
                assertEquals("Rowling", model.props.lastName.value)
            } catch (e: Exception) {
                fail()
            }

            try {
                val model = klerk.read(context) { get(rowling) }
                assertEquals("Rowling", model.props.lastName.value)
            } catch (e: Exception) {
                fail()
            }

        }
    }

    @Test
    fun multiStep() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            val astrid = createAuthorAstrid(klerk)
            val harryPotter1 = createBookHarryPotter1(klerk, rowling)
            createBookHarryPotter2(klerk, rowling, listOf(harryPotter1), setOf(astrid))

            // hack a model into the store
            val idaRef = ModelID<BookLover>(79)
            val ida = Model(
                id = idaRef,
                createdAt = Clock.System.now(),
                lastPropsUpdateAt = Clock.System.now(),
                lastStateTransitionAt = Clock.System.now(),
                state = AuthorStates.Improving.name,
                timeTrigger = null,
                props = BookLover(FirstName("Ida"), listOf(rowling)),
            )
            ModelCache.store(ida)

            val recommendedBooksForIda = klerk.read(Ctx.system()) {
                get(idaRef).props.favouriteAuthors.flatMap { author -> getRelated(Book::author, author) }
            }

            assertEquals(2, recommendedBooksForIda.size)
        }
    }

    @Test
    fun ergonomics() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            val astrid = createAuthorAstrid(klerk)
            klerk.handle(
                Command(ImproveAuthor, astrid, null),
                Ctx.system(),
                ProcessingOptions(
                    CommandToken.simple()
                )
            )
            val harryPotter1 = createBookHarryPotter1(klerk, rowling)
            createBookHarryPotter2(klerk, astrid, listOf(harryPotter1), setOf(astrid))

            val context = Ctx.system()

            // just lock
            klerk.read(context) {
                val bestAuthor = get(astrid)
                // do something with bestAuthor
            }

            // read a model
            val bestAuthor = klerk.read(context) { get(astrid) }

            // get a combined result
            val bestAuthorAndAnotherBook = klerk.read(context) {
                val author = get(astrid)
                val book = collections.books.all.asSequence().first { it.props.author == author.id }
                AuthorAndBook(author, book)
            }

            assertTrue(bestAuthorAndAnotherBook.author.props.firstName.value == "Astrid")

            // read a list of things
            val authors = klerk.read(context) {
                collections.authors.all.asSequence().toList()
            }

            val authorsWithFirstNameBertil = klerk.read(context) {
                collections.authors.all.asSequence().filter { it.props.firstName.value == "Bertil" }.toList()
            }


            // read something nullable
            val maybeBook = klerk.read(context) { getOrNull(harryPotter1) }
            maybeBook?.props?.title?.let { println(it.value) }

            // make a query
            val queryResult = klerk.read(context) { collections.authors.establishedGreatAuthors.query() }
            assertTrue(queryResult.items.isEmpty())

            val q2 = klerk.readSuspend(context) { collections.authors.greatAuthors.query() }
            assertTrue(q2.items.isEmpty())

        }
    }

    @Test
    fun `get and getOrNull follow their contract`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()
            val astrid = createAuthorAstrid(klerk)
            val missing = ModelID<Author>(4711)

            assertFailsWith<NoSuchElementException> { klerk.read(Ctx.system()) { get(missing) } }
            assertFailsWith<AuthorizationException> { klerk.read(Ctx.unauthenticated()) { get(astrid) } }

            assertNull(klerk.read(Ctx.system()) { getOrNull(missing) })
            assertNull(klerk.read(Ctx.unauthenticated()) { getOrNull(astrid) })
            assertEquals(astrid, klerk.read(Ctx.system()) { getOrNull(astrid) }?.id)
        }
    }


}

data class AuthorAndBook(val author: Model<Author>, val book: Model<Book>)


data class Test1(val author: ModelID<Author>)
data class Test2(val author: ModelID<Author>)
data class BookLover(val name: FirstName, val favouriteAuthors: List<ModelID<Author>>)

package dev.klerkframework.klerk


import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.reflect.full.memberProperties
import kotlin.test.*


class ReaderTest {


    @Test
    fun getTypedRelationsTest() {

        runBlocking {

            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()

            val rowling = createAuthorJKRowling(klerk)

            val astrid = createAuthorAstrid(klerk)

            val context = Context.system()

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
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()

            val rowling = createAuthorJKRowling(klerk)
            val context = Context.system()

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
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val klerk = Klerk.create(createConfig(collections))
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
                props = BookLover("Ida", listOf(rowling)),
            )
            ModelCache.store(ida)

            val recommendedBooksForIda = klerk.read(Context.system()) {
                get(idaRef).props.favouriteAuthors.flatMap { author -> getRelated(Book::author, author) }
            }

            assertEquals(2, recommendedBooksForIda.size)
        }
    }

    @Test
    fun ergonomics() {
        runBlocking {
            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            val astrid = createAuthorAstrid(klerk)
            klerk.handle(
                Command(ImproveAuthor, astrid, null),
                Context.system(),
                ProcessingOptions(
                    CommandToken.simple()
                )
            )
            val harryPotter1 = createBookHarryPotter1(klerk, rowling)
            createBookHarryPotter2(klerk, astrid, listOf(harryPotter1), setOf(astrid))

            val context = Context.system()

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
                val book = getFirstWhere(collections.books.all) { it.props.author == author.id }
                AuthorAndBook(author, book)
            }

            assertTrue(bestAuthorAndAnotherBook.author.props.firstName.value == "Astrid")

            // read a list of things
            val authors = klerk.read(context) { list(collections.authors.all) }

            val authorsWithFirstNameBertil = klerk.read(context) {
                list(collections.authors.all) { it.props.firstName.value == "Bertil" }
            }


            // read something nullable
            val maybeBook = klerk.read(context) { getOrNull(harryPotter1) }
            maybeBook?.props?.title?.let { println(it.value) }

            // make a query
            val queryResult = klerk.read(context) { query(collections.authors.establishedGreatAuthors) }
            assertTrue(queryResult.items.isEmpty())

            val q2 = klerk.readSuspend(context) { query(collections.authors.greatAuthors) }
            assertTrue(q2.items.isEmpty())

        }
    }

}

data class AuthorAndBook(val author: Model<Author>, val book: Model<Book>)


data class Test1(val author: ModelID<Author>)
data class Test2(val author: ModelID<Author>)
data class BookLover(val name: String, val favouriteAuthors: List<ModelID<Author>>) 

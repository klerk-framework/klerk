package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.AuthorStates
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookStates
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createBookHarryPotter1
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateFilterTest {

    @Test
    fun `filterStates keeps the included states and drops the excluded`() = runBlocking {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val drafts = books.all.filterStates(included = setOf(BookStates.Draft))
        val notDrafts = books.all.filterStates(excluded = setOf(BookStates.Draft))
        val klerk = createKlerk(views, RamStorage())
        klerk.meta.start()

        val author = createAuthorAstrid(klerk)
        val book = createBookHarryPotter1(klerk, author)

        klerk.read(Ctx.system()) {
            assertEquals(BookStates.Draft.name, get(book).state)
            assertTrue { drafts.contains(book) }
            assertFalse { notDrafts.contains(book) }
        }
    }

    @Test
    fun `a model created after a state view was first read is added to it`() = runBlocking {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val drafts = books.all.filterStates(included = setOf(BookStates.Draft))
        val klerk = createKlerk(views, RamStorage())
        klerk.meta.start()
        val author = createAuthorAstrid(klerk)
        klerk.read(Ctx.system()) { assertEquals(0, drafts.count()) }

        val book = createBookHarryPotter1(klerk, author)

        klerk.read(Ctx.system()) {
            assertEquals(1, drafts.count())
            assertTrue { drafts.contains(book) }
        }
    }

    @Test
    fun `a view filtering on another model's states is refused at startup`() {
        val books = BookViews()
        val authors = AuthorViews<Views>(books.all)
        authors.all.filterStates(included = setOf(BookStates.Published)).register("booksishAuthors")
        val views = Views(books, authors)

        val exception = assertFailsWith<IllegalConfigurationException> {
            createKlerk(views, RamStorage())
        }
        assertEquals(KlerkErrorCode.InvalidView, exception.code)
    }

    @Test
    fun `isIn and stateAs compare a state without its name`() = runBlocking {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val klerk = createKlerk(views, RamStorage())
        klerk.meta.start()

        val author = createAuthorAstrid(klerk)
        klerk.read(Ctx.system()) {
            val model = get(author)
            assertTrue { model.isIn(AuthorStates.Amateur) }
            assertTrue { model.isIn(AuthorStates.Established, AuthorStates.Amateur) }
            assertFalse { model.isIn(AuthorStates.Established) }
            assertEquals(AuthorStates.Amateur, model.stateAs<AuthorStates>())
            assertFailsWith<IllegalArgumentException> { model.stateAs<BookStates>() }
            Unit
        }
    }
}

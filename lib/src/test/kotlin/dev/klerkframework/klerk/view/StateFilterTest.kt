package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

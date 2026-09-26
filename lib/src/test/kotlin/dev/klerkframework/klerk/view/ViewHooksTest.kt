package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createBookHarryPotter1
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewHooksTest {

    private class TrackingBookViews : BookViews() {
        val created = mutableListOf<ModelID<Book>>()

        override fun didCreate(created: Model<Book>) {
            this.created.add(created.id)
        }
    }

    @Test
    fun `didCreate is called for persisted models when Klerk starts`() = runBlocking<Unit> {
        val storage = RamStorage()
        val firstBooks = TrackingBookViews()
        val first = createKlerk(Views(firstBooks, AuthorViews(firstBooks.all)), storage)
        first.meta.start()
        val book = createBookHarryPotter1(first, createAuthorAstrid(first))
        assertEquals(listOf(book), firstBooks.created)

        val restartedBooks = TrackingBookViews()
        val restarted = createKlerk(Views(restartedBooks, AuthorViews(restartedBooks.all)), storage)
        restarted.meta.start()

        assertEquals(listOf(book), restartedBooks.created)
    }
}

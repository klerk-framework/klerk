package dev.klerkframework.klerk.log

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorJKRowling
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.asSequence
import dev.klerkframework.klerk.view.query
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadLogTest {

    @Test
    fun `every model a read block hands out is reported`() = runBlocking {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val klerk = createKlerk(views)
        klerk.meta.start()
        val rowling = createAuthorJKRowling(klerk)
        val system = Ctx.system()

        suspend fun reported(read: suspend () -> Unit): List<String> {
            val entries = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) { klerk.activityLog.subscribeToReads(system).take(1).toList() }
            }
            delay(100)
            read()
            return entries.await().map { it.heading }
        }

        val expected = listOf("Model $rowling was read by ${system.actor}")
        assertEquals(expected, reported { klerk.readSuspend(system) { getOrNull(rowling) } })
        assertEquals(expected, reported { klerk.read(system) { views.authors.all.query(QueryOptions()) } })
        assertEquals(expected, reported { klerk.read(system) { views.authors.all.asSequence().toList() } })
        assertEquals(
            expected,
            reported {
                klerk.read(system) {
                    get(rowling)
                    getOrNull(rowling)
                }
            },
        )
    }
}

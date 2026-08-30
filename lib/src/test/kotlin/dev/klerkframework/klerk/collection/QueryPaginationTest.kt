package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.read.Reader
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.klerkframework.klerk.collection.*

class QueryPaginationTest {

    private fun start(): Pair<Klerk<Ctx, Views>, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        return Klerk.create(createConfig(views), testSettings()) to views
    }

    /** Like [start], but an unauthenticated actor may read only every other author. */
    private fun startWithHiddenAuthors(): Pair<Klerk<Ctx, Views>, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        val spec = createConfig(views, configureAuthorization = {
            readModels { negative { rule(::unauthenticatedCannotReadEvenAuthors) } }
        })
        return Klerk.create(spec, testSettings()) to views
    }

    private suspend fun createAuthor(
        klerk: Klerk<Ctx, Views>,
        firstName: String,
        lastName: String,
        context: Ctx = Ctx.system(),
    ): ModelID<Author> =
        klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName(firstName),
                    lastName = LastName(lastName),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(1),
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple()),
        ).orThrow().primaryModel!!

    private suspend fun deleteAuthor(klerk: Klerk<Ctx, Views>, id: ModelID<Author>) {
        klerk.handle(
            Command(event = DeleteAuthor, model = id, params = null),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()
    }

    /** Creates [count] authors, named so that `lastName` gives the creation order and `firstName` alternates. */
    private suspend fun createAuthors(
        klerk: Klerk<Ctx, Views>,
        count: Int,
        context: Ctx = Ctx.system(),
    ): List<ModelID<Author>> =
        (0 until count).map { createAuthor(klerk, if (it % 2 == 0) "Kalle" else "Greta", "%03d".format(it), context) }

    /** Walks `cursorNextPage` from the start and returns every page. */
    private suspend fun pagesForward(
        klerk: Klerk<Ctx, Views>,
        view: ModelView<Author, Ctx>,
        maxItems: Int,
        context: Ctx = Ctx.system(),
    ): List<List<ModelID<Author>>> {
        val pages = mutableListOf<List<ModelID<Author>>>()
        var cursor: QueryListCursor? = null
        while (true) {
            val page = klerk.read(context) { view.query(QueryOptions(maxItems = maxItems, cursor = cursor)) }
            pages.add(page.items.map { it.id })
            cursor = page.cursorNextPage ?: break
            check(pages.size < 100) { "The traversal does not terminate" }
        }
        return pages
    }

    private suspend fun order(klerk: Klerk<Ctx, Views>, view: ModelView<Author, Ctx>): List<ModelID<Author>> =
        klerk.read(Ctx.system()) { view.asList().map { it.id } }

    @Test
    fun `walking forward visits every model exactly once, in the view's order`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 25)

        val cases = mapOf(
            "all" to views.authors.all,
            "filtered" to views.authors.all.filter { it.props.firstName.value == "Kalle" },
            "sorted ascending" to views.authors.all.sorted({ it.props.lastName.value }),
            "sorted descending" to views.authors.all.sorted({ it.props.lastName.value }, ascending = false),
            "custom" to views.authors.establishedGreatWithAtLeastTwoBooks,
        )
        cases.forEach { (name, view) ->
            val expected = order(klerk, view)
            val pages = pagesForward(klerk, view, maxItems = 7)
            assertEquals(expected, pages.flatten(), "$name: paging must visit the view in its own order")
            // Every page but the last is full.
            pages.dropLast(1).forEach { assertEquals(7, it.size, "$name: pages before the last one must be full") }
        }
    }

    @Test
    fun `walking back yields the pages seen on the way out`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 23)
        val view = views.authors.all

        val forward = pagesForward(klerk, view, maxItems = 5)

        // From the last page, walk cursorPreviousPage back to the start.
        val backward = mutableListOf<List<ModelID<Author>>>()
        var cursor: QueryListCursor? = null
        var page = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5, cursor = null)) }
        while (page.cursorNextPage != null) {
            page = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5, cursor = page.cursorNextPage)) }
        }
        while (true) {
            backward.add(page.items.map { it.id })
            cursor = page.cursorPreviousPage ?: break
            page = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5, cursor = cursor)) }
        }
        assertEquals(forward, backward.reversed())
    }

    /**
     * The case the old createdAt cursors could not express: `createdAt` is the context's instant, so every model
     * created from one context shares it.
     */
    @Test
    fun `models created from one context page correctly`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        val sharedContext = Ctx.system()
        createAuthors(klerk, 20, sharedContext)

        val createdAt = klerk.read(Ctx.system()) { views.authors.all.asList().map { it.createdAt } }
        assertEquals(1, createdAt.toSet().size, "the fixture must actually produce identical timestamps")

        val expected = order(klerk, views.authors.all)
        assertEquals(expected, pagesForward(klerk, views.authors.all, maxItems = 6).flatten())
    }

    @Test
    fun `flags and cursors are null at the ends`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 12)
        val view = views.authors.all

        val first = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5, countTotal = true)) }
        assertFalse(first.hasPreviousPage)
        assertTrue(first.hasNextPage)
        assertNull(first.cursorFirstPage)
        assertNull(first.cursorPreviousPage)
        assertNotNull(first.cursorNextPage)
        assertNotNull(first.cursorLastPage)
        assertEquals(12, first.totalCount)

        val last = klerk.read(Ctx.system()) {
            view.query(QueryOptions(maxItems = 5, cursor = first.cursorLastPage, countTotal = true))
        }
        assertTrue(last.hasPreviousPage)
        assertFalse(last.hasNextPage)
        assertNotNull(last.cursorFirstPage)
        assertNotNull(last.cursorPreviousPage)
        assertNull(last.cursorNextPage)
        assertNull(last.cursorLastPage)
        assertEquals(2, last.items.size)
    }

    @Test
    fun `a page bigger than the view, a page exactly the size of the view, and an empty view`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        val view = views.authors.all

        val empty = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 10, countTotal = true)) }
        assertEquals(emptyList(), empty.items)
        assertFalse(empty.hasNextPage)
        assertFalse(empty.hasPreviousPage)
        assertNull(empty.cursorNextPage)
        assertNull(empty.cursorPreviousPage)
        assertEquals(0, empty.totalCount)

        createAuthors(klerk, 10)

        val exact = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 10)) }
        assertEquals(10, exact.items.size)
        assertFalse(exact.hasNextPage, "a page holding the whole view has no next page")
        assertNull(exact.cursorNextPage)

        val bigger = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 50)) }
        assertEquals(10, bigger.items.size)
        assertFalse(bigger.hasNextPage)
    }

    @Test
    fun `every cursor a page returns survives a round trip`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 14)

        val middle = klerk.read(Ctx.system()) {
            val first = views.authors.all.query(QueryOptions(maxItems = 5, countTotal = true))
            views.authors.all.query(QueryOptions(maxItems = 5, cursor = first.cursorNextPage, countTotal = true))
        }
        val cursors = listOfNotNull(
            middle.cursorFirstPage,
            middle.cursorPreviousPage,
            middle.cursorNextPage,
            middle.cursorLastPage,
            middle.cursorAt(0),
            middle.cursorAt(middle.items.lastIndex),
            QueryListCursor.first,
        )
        assertEquals(7, cursors.size, "the fixture must produce every kind of cursor")
        cursors.forEach { cursor ->
            val text = cursor.toString()
            assertEquals(cursor, QueryListCursor.fromString(text), "did not survive '$text'")
            assertEquals(text, text.filter { it.isLetterOrDigit() || it == '-' || it == '_' }, "must be URL-safe")
        }
    }

    @Test
    fun `a malformed cursor is rejected`() {
        listOf("", "!!!!", "Zm9v", "bzotMQ").forEach {
            assertFailsWith<IllegalArgumentException>("should have rejected '$it'") { QueryListCursor.fromString(it) }
        }
    }

    @Test
    fun `cursorAt points at the individual items of the page`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 12)
        val all = order(klerk, views.authors.all)

        val page = klerk.read(Ctx.system()) {
            val first = views.authors.all.query(QueryOptions(maxItems = 4))
            views.authors.all.query(QueryOptions(maxItems = 4, cursor = first.cursorNextPage))
        }
        page.items.indices.forEach { i ->
            val fromItem = klerk.read(Ctx.system()) {
                views.authors.all.query(QueryOptions(maxItems = 2, cursor = page.cursorAt(i)))
            }
            assertEquals(all.subList(4 + i, 6 + i), fromItem.items.map { it.id })
        }
        assertFailsWith<IndexOutOfBoundsException> { page.cursorAt(page.items.size) }
    }

    @Test
    fun `a page can be taken backwards from a cursor`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 20)
        val all = order(klerk, views.authors.all)

        // The GraphQL `last: 5, before: <cursor of item 12>` shape.
        val page = klerk.read(Ctx.system()) {
            val second = views.authors.all.query(QueryOptions(maxItems = 12, cursor = null))
            views.authors.all.query(
                QueryOptions(maxItems = 5, cursor = second.cursorNextPage, direction = PageDirection.BEFORE),
            )
        }
        assertEquals(all.subList(7, 12), page.items.map { it.id })
        assertTrue(page.hasPreviousPage)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `a model created before the page neither skips nor repeats a row`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 20)
        // Sorted by name, so a new author lands *before* the current position rather than at the end.
        val view = views.authors.all.sorted({ it.props.lastName.value })

        val first = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5)) }
        assertEquals(listOf("000", "001", "002", "003", "004"), first.items.map { it.props.lastName.value })

        createAuthor(klerk, "Kalle", "000a")   // sorts into the first page, shifting everything after it

        // Without the anchor the stored offset 5 would now point at "004", repeating it.
        val second = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5, cursor = first.cursorNextPage)) }
        assertEquals(
            listOf("005", "006", "007", "008", "009"),
            second.items.map { it.props.lastName.value },
            "the anchor should have absorbed the shift",
        )
    }

    @Test
    fun `a deleted anchor falls back to the stored position`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 20)
        val view = views.authors.all

        val first = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5)) }
        val nextCursor = requireNotNull(first.cursorNextPage)
        // Delete exactly the model the cursor is anchored to.
        deleteAuthor(klerk, order(klerk, view)[5])

        val second = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 5, cursor = nextCursor)) }
        assertEquals(order(klerk, view).subList(5, 10), second.items.map { it.id })
    }

    @Test
    fun `a stale cursor past the end of the view returns an empty page that can be navigated back from`() =
        runBlocking<Unit> {
            val (klerk, views) = start()
            klerk.meta.start()
            createAuthors(klerk, 30)
            val view = views.authors.all

            var page = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 10)) }
            while (page.cursorNextPage != null) {
                page = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 10, cursor = page.cursorNextPage)) }
            }
            val beyond = page.cursorAt(page.items.lastIndex)
            (0 until 25).forEach { _ -> deleteAuthor(klerk, order(klerk, view).last()) }

            val stale = klerk.read(Ctx.system()) { view.query(QueryOptions(maxItems = 10, cursor = beyond)) }
            assertEquals(emptyList(), stale.items)
            assertTrue(stale.hasPreviousPage)
            val back = klerk.read(Ctx.system()) {
                view.query(QueryOptions(maxItems = 10, cursor = stale.cursorPreviousPage))
            }
            assertTrue(back.items.isNotEmpty())
        }

    @Test
    fun `queryIfAuthorized returns full pages`() = runBlocking<Unit> {
        val (klerk, views) = startWithHiddenAuthors()
        klerk.meta.start()
        createAuthors(klerk, 30)
        val view = views.authors.all
        val context = Ctx.unauthenticated()

        val pages = mutableListOf<List<ModelID<Author>>>()
        var cursor: QueryListCursor? = null
        while (true) {
            val page = klerk.read(context) { view.queryIfAuthorized(QueryOptions(maxItems = 4, cursor = cursor)) }
            pages.add(page.items.map { it.id })
            cursor = page.cursorNextPage ?: break
            check(pages.size < 100)
        }
        pages.dropLast(1).forEach { assertEquals(4, it.size, "authorization must not shrink a page") }

        val readable = klerk.read(context) { view.asListIfAuthorized().map { it.id } }
        assertEquals(15, readable.size)
        assertEquals(readable, pages.flatten())
    }

    @Test
    fun `countTotal is off by default`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 12)

        val page = klerk.read(Ctx.system()) { views.authors.all.query(QueryOptions(maxItems = 5)) }
        assertNull(page.totalCount)
        assertNull(page.cursorLastPage)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `a filter is applied before the page is cut`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 30)

        val page = klerk.read(Ctx.system()) {
            views.authors.all.query(QueryOptions(maxItems = 5)) { it.props.firstName.value == "Greta" }
        }
        assertEquals(5, page.items.size)
        assertTrue(page.items.all { it.props.firstName.value == "Greta" })
    }

    /** Hides the authors whose `lastName` is an even number from an unauthenticated actor. */
    @Suppress("unused")
    private fun unauthenticatedCannotReadEvenAuthors(
        args: ArgModelContextReader<Ctx, Views>,
    ): NegativeAuthorization {
        val props = args.model.props
        if (props !is Author || args.context.actor !is Unauthenticated) {
            return NegativeAuthorization.Pass
        }
        val number = props.lastName.valueWithoutAuthorization.toIntOrNull() ?: return NegativeAuthorization.Pass
        return if (number % 2 == 0) NegativeAuthorization.Deny else NegativeAuthorization.Pass
    }

    /** A view that is not indexable and answers in ids, i.e. the shape docs/views.md recommends. */
    private class OddAuthors(private val authors: ModelView<Author, Ctx>) : ModelView<Author, Ctx>(authors) {
        override fun <V> memberIds(reader: Reader<Ctx, V>): Sequence<ModelID<Author>> =
            authors.memberIds(reader).filterIndexed { i, _ -> i % 2 == 1 }
    }

    @Test
    fun `a custom view pages without knowing anything about cursors`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        createAuthors(klerk, 21)
        val view = OddAuthors(views.authors.all)

        assertEquals(order(klerk, view), pagesForward(klerk, view, maxItems = 3).flatten())
    }
}

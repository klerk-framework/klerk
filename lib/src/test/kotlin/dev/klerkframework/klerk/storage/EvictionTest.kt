package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.read.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything here runs with a cache far smaller than the data, so practically every read is a miss that has to be
 * repaired from storage. The point is that nothing observable changes: the same reads, relations and commands must
 * produce the same answers they do when everything is resident.
 */
class EvictionTest {

    private val tiny = ModelCacheSettings(maxResidentModels = 2)

    private fun start(
        storage: Persistence,
        cache: ModelCacheSettings,
    ): Pair<Klerk<Ctx, Views>, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        val klerk = Klerk.create(createConfig(views), testSettings(storage = storage, modelCache = cache))
        return klerk to views
    }

    @Test
    fun `reads return the same models whether or not bodies are evicted`() = runBlocking {
        // One dataset, read twice: once by an instance that keeps everything resident, once by an instance whose cache
        // holds two models. Sample data is randomized, so this has to be the same storage rather than two runs of it.
        val storage = SQLiteInMemory.create()
        val (writer, writerViews) = start(storage, ModelCacheSettings())
        writer.meta.start()
        generateSampleData(12, 2, writer)
        val resident = writer.read(Ctx.system()) { readEverything(writerViews) }
        writer.meta.stop()

        val (evicting, evictingViews) = start(storage, tiny)
        evicting.meta.start()
        val afterEviction = evicting.read(Ctx.system()) { readEverything(evictingViews) }
        evicting.meta.stop()

        assertTrue(resident.size > 10, "the sample data should be much larger than the cache")
        assertEquals(resident, afterEviction)
    }

    private fun Reader<Ctx, Views>.readEverything(views: Views): Map<Int, Model<out Any>> =
        (list(views.authors.all) + list(views.books.all)).associateBy { it.id.value }

    /** A brand new author: Amateur, and with no books referring to it. */
    private suspend fun createAuthor(klerk: Klerk<Ctx, Views>): ModelID<Author> =
        klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName("Solo"),
                    lastName = LastName("Author"),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(42),
                ),
            ),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow().primaryModel!!

    @Test
    fun `modelsCount counts models that exist, not models in memory`() = runBlocking {
        val (klerk, _) = start(SQLiteInMemory.create(), tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)

        assertEquals(ModelCache.count, klerk.meta.modelsCount)
        assertTrue(klerk.meta.modelsCount > 10, "expected more models than the cache can hold")
        // The size bound is a target Caffeine enforces asynchronously, not a hard cap, so this checks that bodies are
        // actually being dropped rather than that some exact number is resident.
        assertTrue(
            ModelCache.residentCount < klerk.meta.modelsCount,
            "expected bodies to be evicted, but all ${ModelCache.residentCount} were resident"
        )
        klerk.meta.stop()
    }

    @Test
    fun `relations survive eviction of the bodies they relate`() = runBlocking {
        val (klerk, views) = start(SQLiteInMemory.create(), tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)

        val (author, expectedBooks) = klerk.read(Ctx.system()) {
            val author = list(views.authors.all).first { a -> list(views.books.all).any { it.props.author == a.id } }
            author to list(views.books.all).filter { it.props.author == author.id }.map { it.id }.toSet()
        }
        assertTrue(expectedBooks.isNotEmpty())

        // By now the author and its books are long evicted; the relation index must still find them.
        val related = klerk.read(Ctx.system()) { getRelated(Book::class, author.id).map { it.id }.toSet() }
        assertEquals(expectedBooks, related)
        klerk.meta.stop()
    }

    @Test
    fun `a command can update a model whose body has been evicted`() = runBlocking {
        val (klerk, views) = start(SQLiteInMemory.create(), tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)

        val author = createAuthor(klerk)
        // Push the author out of the cache by reading past it.
        klerk.read(Ctx.system()) { list(views.books.all).map { it.id } }
        assertTrue(ModelCache.residentCount < klerk.meta.modelsCount)

        klerk.handle(
            Command(event = ImproveAuthor, model = author, params = null),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple())
        ).orThrow()

        // Improving's onEnter transitions on immediately, so the assertion is just "it left the state it was in" --
        // which it could only do if the command found the evicted model.
        val after = klerk.read(Ctx.system()) { get(author) }
        assertNotEquals(AuthorStates.Amateur.name, after.state)
        klerk.meta.stop()
    }

    @Test
    fun `a deleted model stays gone and its id stays unavailable for reuse`() = runBlocking {
        val (klerk, views) = start(SQLiteInMemory.create(), tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)

        val author = createAuthor(klerk)
        klerk.read(Ctx.system()) { list(views.books.all).map { it.id } }   // evict it
        val countBefore = klerk.meta.modelsCount

        klerk.handle(
            Command(event = DeleteAuthor, model = author, params = null),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple())
        ).orThrow()

        assertEquals(countBefore - 1, klerk.meta.modelsCount)
        assertNull(klerk.read(Ctx.system()) { getOrNull(author) })
        // The row is gone from storage too, so a miss must not be able to resurrect it.
        assertTrue(ModelCache.isIdAvailable(author.value))
        klerk.meta.stop()
    }

    @Test
    fun `concurrent readers missing the same model all get it`() = runBlocking {
        val (klerk, views) = start(SQLiteInMemory.create(), tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)

        val ids = klerk.read(Ctx.system()) { list(views.authors.all).map { it.id } }
        val target = ids.first()
        val expected = klerk.read(Ctx.system()) { get(target) }

        val mismatches = AtomicInteger(0)
        withTimeout(60_000) {
            (1..24).map {
                launch(Dispatchers.Default) {
                    repeat(20) {
                        if (klerk.read(Ctx.system()) { get(target) } != expected) mismatches.incrementAndGet()
                    }
                }
            }.joinAll()
        }
        assertEquals(0, mismatches.get(), "concurrent hydration of the same evicted model returned wrong data")
        klerk.meta.stop()
    }

    @Test
    fun `a restart with an evicting cache reads the same data`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val (first, views) = start(storage, tiny)
        first.meta.start()
        generateSampleData(12, 2, first)
        val before = first.read(Ctx.system()) {
            (list(views.authors.all) + list(views.books.all)).associateBy { it.id.value }
        }
        first.meta.stop()

        val (second, views2) = start(storage, tiny)
        second.meta.start()
        val after = second.read(Ctx.system()) {
            (list(views2.authors.all) + list(views2.books.all)).associateBy { it.id.value }
        }
        assertEquals(before, after)
        second.meta.stop()
    }
}

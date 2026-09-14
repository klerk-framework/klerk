package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.job.JobCommit
import dev.klerkframework.klerk.read.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.klerkframework.klerk.collection.*

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
        val klerk = Klerk.create(
            createConfig(views),
            testSettings(storage = storage, modelCache = cache),
        )
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
        (views.authors.all.asSequence().toList() + views.books.all.asSequence().toList()).associateBy { it.id.value }

    /** A brand new author: Amateur, and with no books referring to it. */
    private suspend fun createAuthor(klerk: Klerk<Ctx, Views>): ModelID<Author> =
        klerk.handle(
            Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Solo"),
                    lastName = LastName("Author"),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(42),
                )
            ),
            Ctx.system(),
        ).getOrThrow().primaryModel!!

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
            val author = views.authors.all.asSequence().toList().first { a -> views.books.all.asSequence().toList().any { it.props.author == a.id } }
            author to views.books.all.asSequence().toList().filter { it.props.author == author.id }.map { it.id }.toSet()
        }
        assertTrue(expectedBooks.isNotEmpty())

        // By now the author and its books are long evicted; the relation index must still find them.
        val related = klerk.read(Ctx.system()) { referencing(Book::class, author.id).map { it.id }.toSet() }
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
        klerk.read(Ctx.system()) { views.books.all.asSequence().toList().map { it.id } }
        assertTrue(ModelCache.residentCount < klerk.meta.modelsCount)

        klerk.handle(
            Command(ImproveAuthor, author),
            Ctx.system(),
        ).getOrThrow()

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
        klerk.read(Ctx.system()) { views.books.all.asSequence().toList().map { it.id } }   // evict it
        val countBefore = klerk.meta.modelsCount

        klerk.handle(
            Command(DeleteAuthor, author),
            Ctx.system(),
        ).getOrThrow()

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

        val ids = klerk.read(Ctx.system()) { views.authors.all.asSequence().toList().map { it.id } }
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

    /**
     * Persistence that holds [store] open until released, so a test can have a read run at the one moment that
     * matters: after the new state is in storage, before the cache knows about it.
     */
    private class BlockingStore(private val delegate: Persistence) : Persistence by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile
        var block = false
        private val reads = ConcurrentHashMap<Int, AtomicInteger>()

        fun readsOf(id: ModelID<*>): Int = reads[id.value]?.get() ?: 0

        override fun readModel(id: Int): Model<out Any>? {
            reads.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            return delegate.readModel(id)
        }

        override fun <T : Any, P, C : KlerkContext, V> store(
            delta: ProcessingData<out T, C, V>,
            command: Command<T, P>?,
            context: C?,
            attachedData: AttachedDataDelta,
            jobs: JobCommit,
            sequenceNumber: Long,
        ) {
            delegate.store(delta, command, context, attachedData, jobs, sequenceNumber)
            if (block) {
                entered.countDown()
                release.await()
            }
        }
    }

    @Test
    fun `a read during a commit never sees the new state early`() = runBlocking {
        val storage = BlockingStore(SQLiteInMemory.create())
        val (klerk, _) = start(storage, tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)

        val author = createAuthor(klerk)
        val before = klerk.read(Ctx.system()) { get(author) }.props.firstName

        storage.block = true
        val committing = launch(Dispatchers.Default) {
            klerk.handle(
                Command(
                    ChangeName,
                    author,
                    ChangeNameParams(FirstName("Renamed"), LastName("Author"))
                ),
                Ctx.system(),
            ).getOrThrow()
        }

        try {
            withTimeout(60_000) {
                // Wait until the new name is durably in storage but the cache has not been updated yet.
                withContext(Dispatchers.IO) { storage.entered.await() }
                // Evict the author, so the read below has to repair a miss -- the exact case that would otherwise go
                // to storage and come back with the not-yet-applied new name. Dropped outright rather than churned
                // out: what is under test is the miss, not when Caffeine decides to evict.
                ModelCache.evictBody(author.value)
                assertFalse(ModelCache.isResident(author.value), "the author should not be resident at this point")
                val during = klerk.read(Ctx.system()) { get(author) }.props.firstName
                assertEquals(before, during, "a read saw the new state before the commit was applied to the cache")
            }
        } finally {
            // Unconditionally, or a failed assertion leaves the commit parked on the latch and runBlocking never
            // returns -- turning a clean failure into a hung build.
            storage.release.countDown()
            committing.join()
        }

        val after = klerk.read(Ctx.system()) { get(author) }.props.firstName
        assertEquals(FirstName("Renamed"), after)
        klerk.meta.stop()
    }

    /**
     * A view's membership and the bodies behind it must flip together. `greatAuthors` selects on `firstName`, so a
     * `ChangeName` moves the author in and out of it, and any moment where the view already lists the author but the
     * body still has the old name is a torn read.
     *
     * The window this targets is the one right after a commit releases the write lock, so the readers here are
     * deliberately numerous: the lock hands over to every queued reader at once, putting a whole batch into that
     * window each time a commit finishes.
     */
    @Test
    fun `a view and the bodies behind it flip together`() = runBlocking {
        val (klerk, views) = start(SQLiteInMemory.create(), ModelCacheSettings())
        klerk.meta.start()
        val author = createAuthor(klerk)

        val greatNames = setOf("Linus", "Bertil")
        val violations = ConcurrentHashMap.newKeySet<String>()
        val done = AtomicInteger(0)

        suspend fun rename(to: String) = klerk.handle(
            Command(
                ChangeName,
                author,
                ChangeNameParams(FirstName(to), LastName("Author"))
            ),
            Ctx.system(),
        ).getOrThrow()

        withTimeout(120_000) {
            val readers = (1..12).map {
                launch(Dispatchers.Default) {
                    while (done.get() == 0) {
                        klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList() }.forEach { listed ->
                            if (listed.props.firstName.value !in greatNames) {
                                violations.add(
                                    "greatAuthors listed ${listed.id} whose name is '${listed.props.firstName.value}'"
                                )
                            }
                        }
                    }
                }
            }
            val writer = launch(Dispatchers.Default) {
                repeat(60) {
                    rename("Linus")
                    rename("Solo")
                }
                done.set(1)
            }
            (readers + listOf(writer)).joinAll()
        }

        assertTrue(violations.isEmpty(), "torn read between a view and its bodies: ${violations.take(3)}")
        klerk.meta.stop()
    }

    @Test
    fun `concurrent reads and commits do not deadlock or corrupt models`() = runBlocking {
        val (klerk, views) = start(SQLiteInMemory.create(), tiny)
        klerk.meta.start()
        generateSampleData(12, 2, klerk)
        val target = createAuthor(klerk)

        withTimeout(60_000) {
            val readers = (1..8).map {
                launch(Dispatchers.Default) {
                    repeat(50) {
                        klerk.read(Ctx.system()) { get(target) }
                        // churns the tiny cache so the target keeps getting evicted between reads
                        klerk.read(Ctx.system()) { views.books.all.asSequence().toList().map { it.id } }
                    }
                }
            }
            val writer = launch(Dispatchers.Default) {
                repeat(20) {
                    klerk.handle(
                        Command(ImproveAuthor, target),
                        Ctx.system(),
                    )
                }
            }
            (readers + listOf(writer)).joinAll()
        }
        klerk.meta.stop()
    }

    @Test
    fun `a restart with an evicting cache reads the same data`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val (first, views) = start(storage, tiny)
        first.meta.start()
        generateSampleData(12, 2, first)
        val before = first.read(Ctx.system()) {
            (views.authors.all.asSequence().toList() + views.books.all.asSequence().toList()).associateBy { it.id.value }
        }
        first.meta.stop()

        val (second, views2) = start(storage, tiny)
        second.meta.start()
        val after = second.read(Ctx.system()) {
            (views2.authors.all.asSequence().toList() + views2.books.all.asSequence().toList()).associateBy { it.id.value }
        }
        assertEquals(before, after)
        second.meta.stop()
    }
}

package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.ModelCacheSettings
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ViewOperationsTest {

    /** Counts how many model bodies actually had to be read back from storage. */
    private class CountingStorage : RamStorage() {
        val reads = AtomicInteger(0)
        override fun readModel(id: Int): Model<out Any>? {
            reads.incrementAndGet()
            return super.readModel(id)
        }
    }

    private fun start(
        storage: RamStorage = RamStorage(),
        cache: ModelCacheSettings = ModelCacheSettings(),
    ): Pair<Klerk<Ctx, Views>, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        return Klerk.create(createConfig(views), testSettings(storage = storage, modelCache = cache)) to views
    }

    private suspend fun createAuthor(klerk: Klerk<Ctx, Views>, firstName: String, lastName: String): ModelID<Author> =
        klerk.handle(
            Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName(firstName),
                    lastName = LastName(lastName),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(1),
                )
            ),
            Ctx.system(),
        ).getOrThrow().primaryModel!!

    /**
     * The bug this design rests on fixing: `ensureIndex` only built an index for `ReaderWithoutAuth`, but inside a
     * `klerk.read { }` block the receiver is `ReaderWithAuth`. So `count()` never built the index and read every
     * model, every time — unless an earlier `list`/`query` happened to warm it. Note there is no such warm-up here.
     */
    @Test
    fun `count on an indexed view stops reading models once the index is built`() = runBlocking<Unit> {
        val storage = CountingStorage()
        // A cache of one, so every model actually read shows up as a read from storage.
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        repeat(3) { createAuthor(klerk, "Linus", "great$it") }
        repeat(30) { createAuthor(klerk, "Kalle", "ordinary$it") }

        // First call builds the index, which does read the parent's models once.
        assertEquals(3, klerk.read(Ctx.system()) { views.authors.greatAuthors.count() })

        storage.reads.set(0)
        assertEquals(3, klerk.read(Ctx.system()) { views.authors.greatAuthors.count() })
        assertEquals(0, storage.reads.get(), "a counted indexed view must not read a single model")

        storage.reads.set(0)
        assertFalse(klerk.read(Ctx.system()) { views.authors.greatAuthors.isEmpty() })
        assertEquals(0, storage.reads.get(), "isEmpty must not read a single model")
    }

    @Test
    fun `contains is answered without reading models`() = runBlocking<Unit> {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        val linus = createAuthor(klerk, "Linus", "great0")
        val kalle = createAuthor(klerk, "Kalle", "ordinary0")

        storage.reads.set(0)
        klerk.read(Ctx.system()) {
            assertTrue(linus in views.authors.all)
            assertTrue(kalle in views.authors.all)
        }
        assertEquals(0, storage.reads.get(), "`in` on the all view is a set lookup")
    }

    /** Sorting cannot change cardinality, so counting one must not read and sort every model. */
    @Test
    fun `count on a sorted view reads no models`() = runBlocking<Unit> {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        repeat(12) { createAuthor(klerk, "Kalle", "%03d".format(it)) }

        storage.reads.set(0)
        val n = klerk.read(Ctx.system()) { views.authors.all.sorted({ it.props.lastName.value }).count() }
        assertEquals(12, n)
        assertEquals(0, storage.reads.get(), "counting a sorted view must not sort it")
    }

    @Test
    fun `asSequence reads only the models that are consumed`() = runBlocking<Unit> {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        repeat(40) { createAuthor(klerk, "Kalle", "%03d".format(it)) }

        storage.reads.set(0)
        val firstThree = klerk.read(Ctx.system()) {
            views.authors.all.asSequence().take(3).map { it.props.lastName.value }.toList()
        }
        assertEquals(listOf("000", "001", "002"), firstThree)
        assertTrue(storage.reads.get() < 40, "taking 3 of 40 should not read all of them, read ${storage.reads.get()}")
    }

    @Test
    fun `ids reads no models at all`() = runBlocking<Unit> {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        repeat(10) { createAuthor(klerk, "Kalle", "%03d".format(it)) }

        storage.reads.set(0)
        val ids = klerk.read(Ctx.system()) { views.authors.all.ids().toList() }
        assertEquals(10, ids.size)
        assertEquals(0, storage.reads.get())
    }

    /**
     * The cheap operations must agree with the expensive one. `asSequence().toList()` is the reference: it reads every
     * model of the view, so anything answered from ids instead has to come out the same.
     */
    @Test
    fun `the cheap operations agree with a full read`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        repeat(9) { createAuthor(klerk, if (it % 3 == 0) "Linus" else "Kalle", "%03d".format(it)) }

        val cases = listOf<Pair<String, (Views) -> ModelView<Author, Ctx>>>(
            "all" to { v -> v.authors.all },
            "filtered" to { v -> v.authors.greatAuthors },
            "sorted" to { v -> v.authors.all.sorted({ m -> m.props.lastName.value }, ascending = false) },
            "custom" to { v -> v.authors.establishedGreatWithAtLeastTwoBooks },
            "empty" to { v -> v.authors.all.filter { false } },
        )
        klerk.read(Ctx.system()) {
            cases.forEach { (name, of) ->
                val view = of(views)
                val reference = view.asSequence().toList()

                assertEquals(reference.size, view.count(), "$name: count")
                assertEquals(reference.isEmpty(), view.isEmpty(), "$name: isEmpty")
                assertEquals(reference.isNotEmpty(), view.isNotEmpty(), "$name: isNotEmpty")
                assertEquals(reference, view.asSequence().toList(), "$name: asSequence")
                assertEquals(reference.map { it.id }, view.ids().toList(), "$name: ids")
                assertEquals(reference, view.asSequenceOrThrow().toList(), "$name: asSequenceOrThrow")
                assertEquals(reference.take(4), view.query(QueryOptions(maxItems = 4)).items, "$name: query")
                assertEquals(
                    reference.take(4),
                    view.queryOrThrow(QueryOptions(maxItems = 4)).items,
                    "$name: queryOrThrow",
                )
                assertEquals(
                    reference.firstOrNull { it.props.firstName.value == "Linus" },
                    view.asSequence().firstOrNull { it.props.firstName.value == "Linus" },
                    "$name: asSequence().firstOrNull",
                )
                reference.forEach { assertTrue(it.id in view, "$name: contains ${it.id}") }
                // Every id the view does not hold must be reported absent.
                views.authors.all.asSequence().toList().map { it.id }.filter { it !in reference.map { m -> m.id } }
                    .forEach { assertFalse(it in view, "$name: must not contain $it") }
            }
        }
    }

    @Test
    fun `asSequence composes with the stdlib sequence operators`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        repeat(6) { createAuthor(klerk, if (it % 2 == 0) "Linus" else "Kalle", "%03d".format(it)) }

        klerk.read(Ctx.system()) {
            val linuses = views.authors.all.asSequence().filter { it.props.firstName.value == "Linus" }.toList()
            assertEquals(3, linuses.size)
            assertEquals(
                "Linus",
                views.authors.all.asSequence().first { it.props.firstName.value == "Linus" }.props.firstName.value,
            )
            assertFailsWith<NoSuchElementException> {
                views.authors.all.asSequence().first { it.props.firstName.value == "Nobody" }
            }
        }
    }

    /** The shape docs/reading.md teaches for DSL functions, where the reader is a value rather than the receiver. */
    @Test
    fun `the operations work through with(reader)`() = runBlocking<Unit> {
        val (klerk, views) = start()
        klerk.meta.start()
        repeat(4) { createAuthor(klerk, "Kalle", "%03d".format(it)) }

        val n = klerk.read(Ctx.system()) {
            val readerAsValue = this
            with(readerAsValue) { views.authors.all.count() }
        }
        assertEquals(4, n)
    }

    /** Like [start], but an unauthenticated actor may read only the authors whose `lastName` is odd. */
    private fun startWithHiddenAuthors(
        storage: RamStorage = RamStorage(),
        cache: ModelCacheSettings = ModelCacheSettings(),
    ): Pair<Klerk<Ctx, Views>, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        val spec = createConfig(views, configureAuthorization = {
            readModels { negative { rule(::unauthenticatedCannotReadEvenAuthors) } }
        })
        return Klerk.create(spec, testSettings(storage = storage, modelCache = cache)) to views
    }

    @Suppress("unused")
    private fun unauthenticatedCannotReadEvenAuthors(args: ArgModelContextReader<Ctx, Views>): NegativeAuthorization {
        val props = args.model.props
        if (props !is Author || args.context.actor !is Unauthenticated) {
            return NegativeAuthorization.Pass
        }
        val number = props.lastName.valueWithoutAuthorization.toIntOrNull() ?: return NegativeAuthorization.Pass
        return if (number % 2 == 0) NegativeAuthorization.Deny else NegativeAuthorization.Pass
    }

    @Test
    fun `asSequence skips what the actor may not read, and asSequenceOrThrow throws`() = runBlocking<Unit> {
        val (klerk, views) = startWithHiddenAuthors()
        klerk.meta.start()
        repeat(10) { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        val context = Ctx.unauthenticated()

        val readable = klerk.read(context) { views.authors.all.asSequence().toList() }
        assertEquals(listOf("001", "003", "005", "007", "009"), readable.map { it.props.lastName.value })

        assertFailsWith<AuthorizationException> {
            klerk.read(context) { views.authors.all.asSequenceOrThrow().toList() }
        }
    }

    @Test
    fun `query skips what the actor may not read, and queryOrThrow throws`() = runBlocking<Unit> {
        val (klerk, views) = startWithHiddenAuthors()
        klerk.meta.start()
        repeat(10) { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        val context = Ctx.unauthenticated()

        val page = klerk.read(context) { views.authors.all.query(QueryOptions(maxItems = 10)).items }
        assertEquals(listOf("001", "003", "005", "007", "009"), page.map { it.props.lastName.value })

        assertFailsWith<AuthorizationException> {
            klerk.read(context) { views.authors.all.queryOrThrow(QueryOptions(maxItems = 10)) }
        }
    }

    @Test
    fun `asSequence is lazy`() = runBlocking<Unit> {
        val storage = CountingStorage()
        val (klerk, views) = startWithHiddenAuthors(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        repeat(60) { createAuthor(klerk, "Kalle", "%03d".format(it)) }

        storage.reads.set(0)
        val firstTwo = klerk.read(Ctx.unauthenticated()) {
            views.authors.all.asSequence().take(2).toList()
        }
        assertEquals(listOf("001", "003"), firstTwo.map { it.props.lastName.value })
        assertTrue(storage.reads.get() <= 10, "taking 2 of 60 read ${storage.reads.get()} models")
    }

    /** `asSequence()` is lazy, so `firstOrNull` on it stops at the first match rather than materializing everything. */
    @Test
    fun `asSequence firstOrNull stops at the first match`() = runBlocking<Unit> {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()
        repeat(60) { createAuthor(klerk, "Kalle", "%03d".format(it)) }

        storage.reads.set(0)
        val first = klerk.read(Ctx.system()) {
            views.authors.all.asSequence().firstOrNull { it.props.lastName.value == "002" }
        }
        assertEquals("002", first?.props?.lastName?.value)
        assertTrue(storage.reads.get() <= 10, "stopping at item 3 of 60 read ${storage.reads.get()} models")
    }
}

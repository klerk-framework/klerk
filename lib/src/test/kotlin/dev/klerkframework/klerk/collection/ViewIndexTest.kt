package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.ModelCacheSettings
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.klerkframework.klerk.collection.*

class ViewIndexTest {

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
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName(firstName),
                    lastName = LastName(lastName),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(1),
                ),
            ),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow().primaryModel!!

    /**
     * The point of the whole phase: once a narrow view has been queried, querying it again reads only the models it
     * contains -- not every model of the type.
     */
    @Test
    fun `a narrow view does not read the models it excludes`() = runBlocking {
        val storage = CountingStorage()
        // A cache of one, so that every model actually read shows up as a read from storage.
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()

        repeat(3) { createAuthor(klerk, "Linus", "great$it") }
        repeat(30) { createAuthor(klerk, "Kalle", "ordinary$it") }

        // First query builds the index and does pay for a full pass.
        val warm = klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList() }
        assertEquals(3, warm.size)

        storage.reads.set(0)
        val again = klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList() }
        assertEquals(3, again.size)
        assertTrue(
            storage.reads.get() < 10,
            "expected roughly one read per member, but read ${storage.reads.get()} models for a 3-model view"
        )
        klerk.meta.stop()
    }

    @Test
    fun `count and contains do not read models at all`() = runBlocking {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()

        repeat(3) { createAuthor(klerk, "Linus", "great$it") }
        repeat(30) { createAuthor(klerk, "Kalle", "ordinary$it") }
        val members = klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList().map { it.id } }

        storage.reads.set(0)
        klerk.read(Ctx.system()) {
            assertEquals(3, views.authors.greatAuthors.count(this))
            assertTrue(views.authors.greatAuthors.contains(members.first(), this))
            assertFalse(views.authors.greatAuthors.isEmpty(this))
        }
        assertEquals(0, storage.reads.get(), "membership questions should be answered from the index alone")
        klerk.meta.stop()
    }

    @Test
    fun `the index follows creates, updates and deletes`() = runBlocking {
        val (klerk, views) = start()
        klerk.meta.start()
        val great = createAuthor(klerk, "Linus", "1")
        createAuthor(klerk, "Kalle", "2")

        fun members() = runBlocking { klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList().map { it.id } } }
        assertEquals(listOf(great), members())   // builds the index

        // A create after the index exists must land in it.
        val alsoGreat = createAuthor(klerk, "Bertil", "3")
        assertEquals(listOf(great, alsoGreat), members())

        // An update that changes whether the predicate matches must move it in or out.
        klerk.handle(
            Command(event = ChangeName, model = great, params = ChangeNameParams(FirstName("Kalle"), LastName("1"))),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple())
        ).orThrow()
        assertEquals(listOf(alsoGreat), members())

        klerk.handle(
            Command(event = ChangeName, model = great, params = ChangeNameParams(FirstName("Linus"), LastName("1"))),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple())
        ).orThrow()
        assertEquals(listOf(great, alsoGreat), members())

        klerk.handle(
            Command(event = DeleteAuthor, model = great, params = null),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple())
        ).orThrow()
        assertEquals(listOf(alsoGreat), members())
        klerk.meta.stop()
    }

    /**
     * `establishedGreatAuthors` is `all.filter{great}.filter{established}`, so it only stays correct if a change
     * propagates through the chain rather than stopping at the first view.
     */
    @Test
    fun `a view derived from another view follows the chain`() = runBlocking {
        val (klerk, views) = start()
        klerk.meta.start()
        val author = createAuthor(klerk, "Linus", "1")

        fun established() =
            runBlocking { klerk.read(Ctx.system()) { views.authors.establishedGreatAuthors.asSequence().toList().map { it.id } } }
        fun great() =
            runBlocking { klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList().map { it.id } } }

        assertEquals(listOf(author), great())
        assertEquals(emptyList(), established())

        // ImproveAuthor moves the author on towards Established.
        klerk.handle(
            Command(event = ImproveAuthor, model = author, params = null),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple())
        ).orThrow()

        val state = klerk.read(Ctx.system()) { get(author).state }
        assertEquals(
            if (state == AuthorStates.Established.name) listOf(author) else emptyList(),
            established(),
            "the derived view disagreed with the model's actual state"
        )
        klerk.meta.stop()
    }

    /** `midrangeAuthors` is never registered, so it only works if unregistered views are maintained too. */
    @Test
    fun `an unregistered view is indexed like any other`() = runBlocking {
        val (klerk, views) = start()
        klerk.meta.start()
        val inRange = createAuthor(klerk, "Kalle", "20")
        createAuthor(klerk, "Kalle", "5")

        fun members() = runBlocking { klerk.read(Ctx.system()) { views.authors.midrangeAuthors.asSequence().toList().map { it.id } } }
        assertEquals(listOf(inRange), members())

        val alsoInRange = createAuthor(klerk, "Kalle", "18")
        assertEquals(listOf(inRange, alsoInRange), members())
        klerk.meta.stop()
    }

    /** A custom ModelView can depend on anything, so it must keep being evaluated rather than indexed. */
    @Test
    fun `a custom view is not indexed and stays correct`() = runBlocking {
        val (klerk, views) = start()
        klerk.meta.start()
        generateSampleData(6, 2, klerk)

        val view = views.authors.establishedGreatWithAtLeastTwoBooks
        val first = klerk.read(Ctx.system()) { view.asSequence().toList().map { it.id }.toSet() }
        val second = klerk.read(Ctx.system()) { view.asSequence().toList().map { it.id }.toSet() }
        assertEquals(first, second)

        // Its membership depends on Book, not Author, so an index keyed off Author changes would go stale here.
        val expected = klerk.read(Ctx.system()) {
            views.authors.all.asSequence().toList()
                .filter { a -> views.books.all.asSequence().toList().count { it.props.author == a.id } >= 2 }
                .map { it.id }.toSet()
        }
        assertEquals(expected, first)
        klerk.meta.stop()
    }

    /**
     * A custom view that keeps its own ids -- the shape the docs recommend -- answering in ids rather than models.
     * Klerk cannot index it (its membership is decided by whatever put ids in the set), but it can still answer every
     * membership question without reading a single model.
     */
    private class HandMaintainedView(
        private val authors: ModelView<Author, Ctx>,
        val ids: MutableSet<Int>,
    ) : ModelView<Author, Ctx>(authors) {
        override fun <V> memberIds(reader: Reader<Ctx, V>): Sequence<ModelID<Author>> =
            authors.memberIds(reader).filter { ids.contains(it.value) }

        override fun <V> contains(value: ModelID<*>, reader: Reader<Ctx, V>): Boolean = ids.contains(value.value)
    }

    @Test
    fun `a custom view can answer membership without reading any model`() = runBlocking {
        val storage = CountingStorage()
        val (klerk, views) = start(storage, ModelCacheSettings(maxResidentModels = 1))
        klerk.meta.start()

        val chosen = (1..3).map { createAuthor(klerk, "Linus", "chosen$it") }
        repeat(30) { createAuthor(klerk, "Kalle", "ordinary$it") }
        val view = HandMaintainedView(views.authors.all, chosen.map { it.value }.toMutableSet())

        storage.reads.set(0)
        klerk.read(Ctx.system()) {
            assertEquals(3, view.count(this))
            assertTrue(view.contains(chosen.first(), this))
            assertFalse(view.isEmpty(this))
        }
        assertEquals(0, storage.reads.get(), "a custom view answering in ids should not have to read any model")

        // And listing it reads only what it contains, not the 33 models of the parent.
        storage.reads.set(0)
        val listed = klerk.read(Ctx.system()) { view.asSequence().toList().map { it.id } }
        assertEquals(chosen, listed)
        assertTrue(storage.reads.get() < 10, "listing read ${storage.reads.get()} models for a 3-model view")
        klerk.meta.stop()
    }

    /** Restarting against the same views used to append to `all` a second time, duplicating every model. */
    @Test
    fun `restarting against the same views does not duplicate their contents`() = runBlocking {
        val storage = RamStorage()
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        val specification = createConfig(views)

        val first = Klerk.create(specification, testSettings(storage = storage))
        first.meta.start()
        val great = createAuthor(first, "Linus", "1")
        createAuthor(first, "Kalle", "2")
        assertEquals(2, first.read(Ctx.system()) { views.authors.all.asSequence().toList() }.size)
        assertEquals(listOf(great), first.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList().map { it.id } })
        first.meta.stop()

        val second = Klerk.create(specification, testSettings(storage = storage))
        second.meta.start()
        assertEquals(2, second.read(Ctx.system()) { views.authors.all.asSequence().toList() }.size)
        assertEquals(listOf(great), second.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList().map { it.id } })
        second.meta.stop()
    }

    @Test
    fun `concurrent first queries of the same view agree`() = runBlocking {
        val (klerk, views) = start()
        klerk.meta.start()
        repeat(5) { createAuthor(klerk, "Linus", "great$it") }
        repeat(20) { createAuthor(klerk, "Kalle", "ordinary$it") }

        val mismatches = AtomicInteger(0)
        withTimeout(60_000) {
            (1..24).map {
                launch(Dispatchers.Default) {
                    val members = klerk.read(Ctx.system()) { views.authors.greatAuthors.asSequence().toList().map { it.id } }
                    if (members.size != 5) mismatches.incrementAndGet()
                }
            }.joinAll()
        }
        assertEquals(0, mismatches.get(), "a concurrent cold build produced the wrong contents")
        klerk.meta.stop()
    }
}

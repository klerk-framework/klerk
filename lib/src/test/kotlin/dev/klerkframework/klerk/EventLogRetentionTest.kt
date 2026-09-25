package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EventLogRetentionTest {

    private val storages: List<() -> Persistence> = listOf(::RamStorage, SQLiteInMemory::create)

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

    private fun start(storage: Persistence, retention: EventLogRetention): Klerk<Ctx, Views> {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val klerk = Klerk.create(createConfig(views, retention = retention), testSettings(storage, clock))
        runBlocking { klerk.meta.start() }
        return klerk
    }

    private fun context(): Ctx = Ctx(SystemIdentity, eventLogExtra = "192.0.2.1", time = clock.now())

    private suspend fun createAuthor(klerk: Klerk<Ctx, Views>, firstName: String): ModelID<Author> = klerk.handle(
        Command(
            CreateAuthor,
            CreateAuthorParams(
                firstName = FirstName(firstName),
                lastName = LastName("Author"),
                phone = PhoneNumber("+46123456"),
                secretToken = SecretPasscode(42),
            ),
        ),
        context(),
    ).getOrThrow().primaryModel!!

    private suspend fun delete(klerk: Klerk<Ctx, Views>, author: ModelID<Author>) {
        klerk.handle(Command(DeleteAuthor, author), context()).getOrThrow()
    }

    private suspend fun log(klerk: Klerk<Ctx, Views>, model: ModelID<Author>): List<EventLogEntry> =
        klerk.read(Ctx.system()) { eventLog(model) }.get()

    private fun Klerk<Ctx, Views>.sweep() = impl().eventLogRetention.sweep()

    @Test
    fun `the log of a deleted model is erased once the retention has passed`() = runBlocking {
        for (storage in storages) {
            val klerk = start(storage(), EventLogRetention(afterModelDeletion = 30.days, paramsAndExtra = null))
            val deleted = createAuthor(klerk, "Deleted")
            val kept = createAuthor(klerk, "Kept")
            delete(klerk, deleted)

            clock.advance(29.days)
            klerk.sweep()
            assertEquals(2, log(klerk, deleted).size, "erased before the retention had passed")

            clock.advance(1.days)
            klerk.sweep()
            assertEquals(emptyList(), log(klerk, deleted))
            assertEquals(1, log(klerk, kept).size, "a model that still exists must keep its log")
            klerk.meta.stop()
        }
    }

    @Test
    fun `zero erases the log in the same command that deletes the model`() = runBlocking {
        for (storage in storages) {
            val klerk = start(storage(), EventLogRetention(afterModelDeletion = Duration.ZERO, paramsAndExtra = null))
            val author = createAuthor(klerk, "Gone")
            delete(klerk, author)
            assertEquals(emptyList(), log(klerk, author))
            klerk.meta.stop()
        }
    }

    @Test
    fun `null keeps the log of a deleted model forever`() = runBlocking {
        for (storage in storages) {
            val klerk = start(storage(), EventLogRetention(afterModelDeletion = null, paramsAndExtra = null))
            val author = createAuthor(klerk, "Remembered")
            delete(klerk, author)
            clock.advance(3650.days)
            klerk.sweep()
            assertEquals(2, log(klerk, author).size)
            klerk.meta.stop()
        }
    }

    @Test
    fun `a pending erasure survives a restart`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val retention = EventLogRetention(afterModelDeletion = 1.days, paramsAndExtra = null)
        val first = start(storage, retention)
        val author = createAuthor(first, "Restarted")
        delete(first, author)
        first.meta.stop()

        clock.advance(2.days)
        val second = start(storage, retention)
        second.sweep()
        assertEquals(emptyList(), log(second, author))
        second.meta.stop()
    }

    @Test
    fun `params and extra are erased once the retention has passed, the rest of the entry is kept`() = runBlocking {
        for (storage in storages) {
            val klerk = start(storage(), EventLogRetention(afterModelDeletion = null, paramsAndExtra = 7.days))
            val old = createAuthor(klerk, "Old")
            clock.advance(7.days)
            val recent = createAuthor(klerk, "Recent")
            clock.advance(1.hours)
            klerk.sweep()

            val erased = log(klerk, old).single()
            assertNull(erased.params)
            assertNull(erased.extra)
            assertEquals(CreateAuthor.id, erased.eventReference)
            assertEquals(ActorType.System, erased.actorType)

            val untouched = log(klerk, recent).single()
            assertTrue(untouched.params!!.contains("Recent"))
            assertEquals("192.0.2.1", untouched.extra)
            klerk.meta.stop()
        }
    }

    @Test
    fun `zero never stores params and extra`() = runBlocking {
        for (storage in storages) {
            val klerk = start(storage(), EventLogRetention(afterModelDeletion = null, paramsAndExtra = Duration.ZERO))
            val author = createAuthor(klerk, "Private")
            val entry = log(klerk, author).single()
            assertNull(entry.params)
            assertNull(entry.extra)
            klerk.meta.stop()
        }
    }

    @Test
    fun `an event without parameters is logged as json null, not as erased`() = runBlocking {
        val klerk = start(RamStorage(), EventLogRetention(afterModelDeletion = null, paramsAndExtra = null))
        val author = createAuthor(klerk, "Parameterless")
        delete(klerk, author)
        assertEquals("null", assertNotNull(log(klerk, author).last().params))
        klerk.meta.stop()
    }

    @Test
    fun `a retention shorter than an hour is rejected`() {
        for (invalid in listOf(30.minutes, (-1).days)) {
            val e = assertFailsWith<IllegalConfigurationException> {
                EventLogRetention(afterModelDeletion = invalid, paramsAndExtra = null)
            }
            assertEquals(KlerkErrorCode.InvalidEventLogRetention, e.code)
        }
    }

    @Test
    fun `the retention must be declared`() {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val e = assertFailsWith<IllegalConfigurationException> {
            SpecificationBuilder<Ctx, Views>(views).build {
                managedModels {
                    model(Book::class, bookStateMachine(views), views.books)
                    model(Author::class, authorStateMachine(views), views.authors)
                }
                authorization { }
                systemContextProvider(::myContextProvider)
            }
        }
        assertEquals(KlerkErrorCode.MissingEventLogRetention, e.code)
    }
}

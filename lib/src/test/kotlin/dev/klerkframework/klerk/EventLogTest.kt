package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.job.JobCommit
import dev.klerkframework.klerk.storage.AttachedDataDelta
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.SqlPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The event log is read from storage rather than from memory, so it gets its consistency from the sequence number the
 * query captures inside the read block: an entry is only visible if the command it describes was visible there.
 */
class EventLogTest {

    /** Holds [store] open until released, i.e. after the event log entry is durable but before the cache knows about it. */
    private class BlockingStore(private val delegate: Persistence) : Persistence by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var block = false

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

    private fun start(storage: Persistence, configureAuthorization: SpecificationBuilder.AuthorizationRulesBlock<Ctx, Views>.() -> Unit = {}):
            Pair<Klerk<Ctx, Views>, Views> {
        val books = BookViews()
        val views = Views(books, AuthorViews(books.all))
        val klerk = Klerk.create(
            createConfig(views, configureAuthorization = configureAuthorization),
            testSettings(storage = storage),
        )
        return klerk to views
    }

    private suspend fun createAuthor(klerk: Klerk<Ctx, Views>, context: Ctx = Ctx.system()): ModelID<Author> =
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
            context,
        ).getOrThrow().primaryModel!!

    private suspend fun rename(klerk: Klerk<Ctx, Views>, author: ModelID<Author>, to: String, context: Ctx = Ctx.system()) =
        klerk.handle(
            Command(
                event = ChangeName,
                model = author,
                params = ChangeNameParams(FirstName(to), LastName("Author")),
            ),
            context,
        ).getOrThrow()

    @Test
    fun `an event is not in the log until its command is visible`() = runBlocking {
        val storage = BlockingStore(SQLiteInMemory.create())
        val (klerk, _) = start(storage)
        klerk.meta.start()
        val author = createAuthor(klerk)
        val before = klerk.read(Ctx.system()) { eventLog(author) }.get()

        storage.block = true
        val committing = launch(Dispatchers.Default) { rename(klerk, author, "Renamed") }

        try {
            withTimeout(60_000) {
                // The rename is durable in storage, but no read can see it yet.
                withContext(Dispatchers.IO) { storage.entered.await() }
                assertEquals(FirstName("Solo"), klerk.read(Ctx.system()) { get(author) }.props.firstName)

                // Reading the log must neither show the rename nor wait for it.
                val during = klerk.read(Ctx.system()) { eventLog(author) }.get()
                assertEquals(before, during, "the log showed an event whose command was not visible yet")
            }
        } finally {
            // Unconditionally, or a failed assertion leaves the commit parked on the latch and runBlocking hangs.
            storage.release.countDown()
            committing.join()
        }

        val after = klerk.read(Ctx.system()) { eventLog(author) }.get()
        assertEquals(before.size + 1, after.size)
        assertEquals(ChangeName.id, after.last().eventReference)
        klerk.meta.stop()
    }

    @Test
    fun `the query is a snapshot of the read block that created it`() = runBlocking {
        val (klerk, _) = start(SQLiteInMemory.create())
        klerk.meta.start()
        val author = createAuthor(klerk)

        val (snapshot, nameWhenTaken) = klerk.read(Ctx.system()) { eventLog(author) to get(author).props.firstName }
        rename(klerk, author, "Renamed")

        val entries = snapshot.get()
        assertEquals(FirstName("Solo"), nameWhenTaken)
        assertTrue(entries.none { it.eventReference == ChangeName.id }, "the snapshot must not grow after its block")
        assertEquals(1, klerk.read(Ctx.system()) { eventLog(author) }.get().count { it.eventReference == ChangeName.id })
        klerk.meta.stop()
    }

    @Test
    fun `entries are ordered by sequence number, whatever the context says the time is`() = runBlocking {
        val (klerk, _) = start(SQLiteInMemory.create())
        klerk.meta.start()

        // Two commands with the exact same context time, and a third backdated: the timestamp is application-supplied,
        // so it orders nothing and cannot identify an entry.
        val sameTime = Ctx(SystemIdentity, time = Clock.System.now())
        val author = createAuthor(klerk, sameTime)
        rename(klerk, author, "Second", sameTime)
        rename(klerk, author, "Third", Ctx(SystemIdentity, time = sameTime.time.minus(30.days)))

        val entries = klerk.read(Ctx.system()) { eventLog(author) }.get()
        assertEquals(3, entries.size, "every command must be logged, even ones sharing a timestamp")
        assertEquals(entries.map { it.sequenceNumber }.sorted(), entries.map { it.sequenceNumber })
        assertEquals(listOf(CreateAuthor.id, ChangeName.id, ChangeName.id), entries.map { it.eventReference })
        klerk.meta.stop()
    }

    @Test
    fun `a single entry can be looked up by its sequence number`() = runBlocking {
        val (klerk, _) = start(SQLiteInMemory.create())
        klerk.meta.start()
        val author = createAuthor(klerk)
        rename(klerk, author, "Renamed")

        val all = klerk.read(Ctx.system()) { eventLog() }.get()
        val wanted = all.last()
        val found = klerk.read(Ctx.system()) { eventLog(sequenceNumber = wanted.sequenceNumber) }.get()
        assertEquals(listOf(wanted), found)
        klerk.meta.stop()
    }

    @Test
    fun `sequence numbers keep increasing after a restart`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val (first, _) = start(storage)
        first.meta.start()
        val author = createAuthor(first)
        rename(first, author, "Renamed")
        val beforeRestart = first.read(Ctx.system()) { eventLog() }.get()
        first.meta.stop()

        val (second, _) = start(storage)
        second.meta.start()
        rename(second, author, "AfterRestart")
        val afterRestart = second.read(Ctx.system()) { eventLog() }.get()

        assertEquals(beforeRestart.size + 1, afterRestart.size)
        assertEquals(beforeRestart.map { it.sequenceNumber }, afterRestart.dropLast(1).map { it.sequenceNumber })
        assertTrue(
            afterRestart.last().sequenceNumber > beforeRestart.last().sequenceNumber,
            "a restart must not hand out a number that is already used"
        )
        second.meta.stop()
    }

    @Test
    fun `an unauthorized actor cannot read the log`() = runBlocking {
        val (klerk, _) = start(SQLiteInMemory.create()) {
            // Only a negative block: declaring one half of a category must not require declaring the other.
            eventLog {
                negative { rule(::unauthenticatedCannotReadTheEventLog) }
            }
        }
        klerk.meta.start()
        createAuthor(klerk)

        assertFailsWith<AuthorizationException> {
            klerk.read(Ctx.unauthenticated()) { eventLog() }
        }
        klerk.meta.stop()
    }

    @Test
    fun `get must not be called inside a read block`() = runBlocking {
        val (klerk, _) = start(SQLiteInMemory.create())
        klerk.meta.start()
        createAuthor(klerk)

        val e = assertFailsWith<IllegalStateException> {
            klerk.readSuspend(Ctx.system()) { eventLog().get() }
        }
        assertTrue(e.message!!.contains("must not be called inside a read block"))
        klerk.meta.stop()
    }
}

fun unauthenticatedCannotReadTheEventLog(args: ArgContextReader<Ctx, Views>): NegativeAuthorization =
    if (args.context.actor is Unauthenticated) NegativeAuthorization.Deny else NegativeAuthorization.Pass

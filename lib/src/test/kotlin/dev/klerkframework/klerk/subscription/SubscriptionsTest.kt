package dev.klerkframework.klerk.subscription

import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DeleteAuthor
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkSettings
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.MyJob
import dev.klerkframework.klerk.MyJobCursor
import dev.klerkframework.klerk.PhoneNumber
import dev.klerkframework.klerk.UpdateAuthor
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.createAstridParameters
import dev.klerkframework.klerk.createConfig
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class SubscriptionsTest {

    private val pippiParams = createAstridParameters.copy(
        firstName = FirstName("Pippi"),
        phone = PhoneNumber("+4611111"),
    )
    private val linusParams = createAstridParameters.copy(
        firstName = FirstName("Linus"),
        phone = PhoneNumber("+4622222"),
    )

    @Test
    fun `a model subscription gets the changes of that model only`() = runBlocking {
        val (klerk, _) = startedKlerk()
        val astrid = create(klerk, createAstridParameters)
        val pippi = create(klerk, pippiParams)
        val received = collecting(klerk.subscriptions.model(pippi, Ctx.system()), 2)

        rename(klerk, astrid, "Anna")
        rename(klerk, pippi, "Pippilotta")
        klerk.handle(Command(DeleteAuthor, pippi), Ctx.system()).getOrThrow()

        val changes = withTimeout(10.seconds) { received.await() }
        assertIs<ModelChange.Changed>(changes[0])
        assertIs<ModelChange.Deleted>(changes[1])
        klerk.meta.stop()
    }

    @Test
    fun `a models subscription gets the changes and deletions of those models only`() = runBlocking {
        val (klerk, _) = startedKlerk()
        val astrid = create(klerk, createAstridParameters)
        val pippi = create(klerk, pippiParams)
        val linus = create(klerk, linusParams)
        val received = collecting(klerk.subscriptions.models(setOf(pippi, linus), Ctx.system()), 2)

        rename(klerk, astrid, "Anna")
        rename(klerk, pippi, "Pippilotta")
        klerk.handle(Command(DeleteAuthor, linus), Ctx.system()).getOrThrow()

        val changes = withTimeout(10.seconds) { received.await() }.map { it as ModelsChange.Changed }
        assertEquals(setOf<ModelID<*>>(pippi), changes[0].changed)
        assertEquals(setOf<ModelID<*>>(linus), changes[1].deleted)
        assertTrue(changes[1].changed.isEmpty())
        klerk.meta.stop()
    }

    @Test
    fun `a view subscription gets the commits that change a model that was or is in the view`() = runBlocking {
        val (klerk, views) = startedKlerk()
        val received = collecting(klerk.subscriptions.view(views.authors.greatAuthors, Ctx.system()), 2)

        val pippi = create(klerk, pippiParams)
        val linus = create(klerk, linusParams)
        rename(klerk, pippi, "Pippilotta")
        rename(klerk, linus, "Lars")

        val changes = withTimeout(10.seconds) { received.await() }
        assertEquals(listOf(setOf(linus), setOf(linus)), changes.map { (it as ViewChange.Changed).ids })
        klerk.meta.stop()
    }

    @Test
    fun `a model that becomes unreadable is reported, one that is never readable is not`() = runBlocking {
        val (klerk, views) = startedKlerk()
        val astrid = create(klerk, createAstridParameters)
        val pippi = create(klerk, pippiParams)
        val received = collecting(klerk.subscriptions.view(views.authors.all, Ctx.unauthenticated()), 1)

        rename(klerk, astrid, "Astrid")
        rename(klerk, pippi, "Astrid")

        val changes = withTimeout(10.seconds) { received.await() }
        assertEquals(setOf(pippi), (changes.single() as ViewChange.Changed).ids)
        klerk.meta.stop()
    }

    @Test
    fun `a subscription after the sequence number of a read gets exactly what the read did not see`() = runBlocking {
        val (klerk, views) = startedKlerk()
        create(klerk, createAstridParameters)
        val mark = klerk.read(Ctx.system()) { sequenceNumber }
        val linus = create(klerk, linusParams)

        val change = withTimeout(10.seconds) {
            klerk.subscriptions.view(views.authors.all, Ctx.system(), after = mark).first()
        }

        assertEquals(setOf(linus), (change as ViewChange.Changed).ids)
        klerk.meta.stop()
    }

    @Test
    fun `a sequence number from before a restart gives missed changes`() = runBlocking {
        val storage = RamStorage()
        val (first, _) = startedKlerk(testSettings(storage = storage))
        create(first, createAstridParameters)
        val seen = first.read(Ctx.system()) { sequenceNumber }
        create(first, linusParams)
        first.meta.stop()
        val (klerk, views) = startedKlerk(testSettings(storage = storage))

        val change = withTimeout(10.seconds) {
            klerk.subscriptions.view(views.authors.all, Ctx.system(), after = seen).first()
        }

        assertIs<MissedChanges>(change)
        klerk.meta.stop()
    }

    @Test
    fun `a sequence number ahead of the latest commit gives missed changes`() = runBlocking {
        val (klerk, views) = startedKlerk()

        val change = withTimeout(10.seconds) {
            klerk.subscriptions.view(views.authors.all, Ctx.system(), after = klerk.meta.sequenceNumber + 10).first()
        }

        assertIs<MissedChanges>(change)
        klerk.meta.stop()
    }

    @Test
    fun `a sequence number older than the history gives missed changes`() = runBlocking {
        val (klerk, _) = startedKlerk(testSettings().copy(subscriptionHistory = 2))
        val mark = klerk.read(Ctx.system()) { sequenceNumber }
        repeat(3) { create(klerk, pippiParams.copy(phone = PhoneNumber("+46333$it"))) }

        val change = withTimeout(10.seconds) { klerk.subscriptions.events(Ctx.system(), after = mark).first() }

        assertIs<MissedChanges>(change)
        klerk.meta.stop()
    }

    @Test
    fun `an event subscription can be limited to some events`() = runBlocking {
        val (klerk, _) = startedKlerk()
        val received = collecting(klerk.subscriptions.events(Ctx.system(), events = setOf(DeleteAuthor.id)), 1)

        val astrid = create(klerk, createAstridParameters)
        klerk.handle(Command(DeleteAuthor, astrid), Ctx.system()).getOrThrow()

        val entry = (withTimeout(10.seconds) { received.await() }.single() as EventChange.Committed).entry
        assertEquals(DeleteAuthor.id, entry.eventReference)
        assertEquals(astrid, entry.model)
        klerk.meta.stop()
    }

    @Test
    fun `a job subscription starts with the job as it is now`() = runBlocking {
        val (klerk, _) = startedKlerk()
        val job = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), Ctx.system())

        val first = withTimeout(10.seconds) { klerk.subscriptions.job(job, Ctx.system()).first() }

        assertEquals(job, first.id)
        klerk.meta.stop()
    }

    @Test
    fun `a subscriber may issue a command when it is told about a change`() = runBlocking {
        val (klerk, views) = startedKlerk()
        val reacted = CompletableDeferred<Unit>()
        val subscriber = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            klerk.subscriptions.view(views.authors.all, Ctx.system())
                .filterIsInstance<ViewChange.Changed<Author>>()
                .collect {
                    klerk.handle(Command(DeleteAuthor, it.ids.single()), Ctx.system())
                    reacted.complete(Unit)
                }
        }

        create(klerk, createAstridParameters)

        assertNotNull(withTimeoutOrNull(10.seconds) { reacted.await() }, "the subscriber's command never completed")
        subscriber.cancel()
        klerk.meta.stop()
    }

    @Test
    fun `a slow subscriber does not hold up commands, and is told what it missed`() = runBlocking {
        val (klerk, views) = startedKlerk(testSettings().copy(subscriptionHistory = 2))
        val received = mutableListOf<ViewChange<Author>>()
        val subscriber = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            klerk.subscriptions.view(views.authors.all, Ctx.system()).collect {
                received += it
                delay(500.milliseconds)
            }
        }

        val took = measureTime {
            repeat(5) { create(klerk, pippiParams.copy(phone = PhoneNumber("+46444$it"))) }
        }

        assertTrue(took < 5.seconds, "commands took $took")
        withTimeout(10.seconds) { while (received.none { it is MissedChanges }) delay(50.milliseconds) }
        subscriber.cancel()
        klerk.meta.stop()
    }

    private fun <E> kotlinx.coroutines.CoroutineScope.collecting(flow: Flow<E>, count: Int): Deferred<List<E>> =
        async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { flow.take(count).toList() }

    private suspend fun create(klerk: Klerk<Ctx, Views>, params: dev.klerkframework.klerk.CreateAuthorParams) =
        klerk.handle(Command(CreateAuthor, params), Ctx.system()).getOrThrow().primaryModel!!

    private suspend fun rename(klerk: Klerk<Ctx, Views>, id: ModelID<Author>, firstName: String) {
        val props = klerk.read(Ctx.system()) { get(id).props }
        klerk.handle(Command(UpdateAuthor, id, props.copy(firstName = FirstName(firstName))), Ctx.system())
            .getOrThrow()
    }

    private suspend fun startedKlerk(settings: KlerkSettings = testSettings()): Pair<Klerk<Ctx, Views>, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        val klerk = Klerk.create(createConfig(views), settings)
        klerk.meta.start()
        return klerk to views
    }
}

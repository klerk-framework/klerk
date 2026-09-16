package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ModelModificationTest {

    private val pippiParameters =
        createAstridParameters.copy(firstName = FirstName("Pippi"), phone = PhoneNumber("+4611111"))

    @Test
    fun `subscribers are told which class of model changed`() = runBlocking {
        val klerk = startedKlerk()
        val got = mutableListOf<ModelModification>()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            klerk.modelChanges.subscribe(null, Ctx.system()).onEach { got += it }.take(3).toList()
        }

        val id = klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system()).getOrThrow().primaryModel!!
        klerk.handle(Command(DeleteAuthor, id), Ctx.system()).getOrThrow()

        assertEquals(
            listOf(
                ModelModification.Created(id, Author::class),
                ModelModification.Transitioned(id, Author::class),
                ModelModification.Deleted(id, Author::class),
            ),
            withTimeoutOrNull(10.seconds) { received.await() } ?: got,
        )
        klerk.meta.stop()
    }

    @Test
    fun `only changes of the subscribed model are sent`() = runBlocking {
        val klerk = startedKlerk()
        val astrid =
            klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system()).getOrThrow().primaryModel!!
        val pippi = klerk.handle(Command(CreateAuthor, pippiParameters), Ctx.system()).getOrThrow().primaryModel!!
        val got = mutableListOf<ModelModification>()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            klerk.modelChanges.subscribe(pippi, Ctx.system()).onEach { got += it }.take(1).toList()
        }

        klerk.handle(Command(DeleteAuthor, astrid), Ctx.system()).getOrThrow()
        klerk.handle(Command(DeleteAuthor, pippi), Ctx.system()).getOrThrow()

        assertEquals(
            listOf(ModelModification.Deleted(pippi, Author::class)),
            withTimeoutOrNull(10.seconds) { received.await() } ?: got,
        )
        klerk.meta.stop()
    }

    @Test
    fun `changes of unreadable models are left out, but deletions are sent`() = runBlocking {
        val klerk = startedKlerk()
        val got = mutableListOf<ModelModification>()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            klerk.modelChanges.subscribe(null, Ctx.unauthenticated()).onEach { got += it }.take(3).toList()
        }

        val astrid =
            klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system()).getOrThrow().primaryModel!!
        val pippi = klerk.handle(Command(CreateAuthor, pippiParameters), Ctx.system()).getOrThrow().primaryModel!!
        klerk.handle(Command(DeleteAuthor, astrid), Ctx.system()).getOrThrow()

        assertEquals(
            listOf(
                ModelModification.Created(pippi, Author::class),
                ModelModification.Transitioned(pippi, Author::class),
                ModelModification.Deleted(astrid, Author::class),
            ),
            withTimeoutOrNull(10.seconds) { received.await() } ?: got,
        )
        klerk.meta.stop()
    }

    private suspend fun startedKlerk(): Klerk<Ctx, Views> {
        val bc = BookViews()
        val klerk = Klerk.create(createConfig(Views(bc, AuthorViews(bc.all))), testSettings())
        klerk.meta.start()
        return klerk
    }
}

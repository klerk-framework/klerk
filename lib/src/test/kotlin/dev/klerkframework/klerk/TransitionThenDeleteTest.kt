package dev.klerkframework.klerk

import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.view.contains
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class TransitionThenDeleteTest {

    @Test
    fun `a model that is updated and transitioned into a state that deletes it is deleted`() = runBlocking<Unit> {
        val klerk = startTicketKlerk()
        val ticket = klerk.handle(Command(CreateTicket, null, CreateTicketParams(TicketTitle("a"))), Ctx.system())
            .getOrThrow().primaryModel!!

        val result = assertIs<CommandResult.Success<Ticket>>(klerk.handle(Command(ArchiveTicket, ticket), Ctx.system()))

        assertEquals(setOf(ticket), result.deletedModels)
        assertFalse(ticket in result.updatedModels)
        assertFalse(ticket in result.transitionedModels)
        klerk.read(Ctx.system()) {
            assertNull(getOrNull(ticket))
            assertFalse(ticket in views.tickets.all)
        }
    }
}

suspend fun startTicketKlerk(): Klerk<Ctx, TicketAppViews> {
    val views = TicketAppViews(TicketViews())
    val specification = SpecificationBuilder<Ctx, TicketAppViews>(views).build {
        eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
        managedModels {
            model(Ticket::class, ticketStateMachine(), views.tickets)
        }
        authorization {
            readModels { positive(::anyoneMayReadTickets) }
            commands { positive(::anyoneMayArchiveTickets) }
        }
        systemContextProvider { Ctx.system() }
    }
    return Klerk.create(specification, testSettings()).also { it.meta.start() }
}

data class TicketAppViews(val tickets: TicketViews)

class TicketViews : ModelViews<Ticket, Ctx>()

data class Ticket(val title: TicketTitle)

class TicketTitle(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 100
    override val maxLines = 1
}

data class CreateTicketParams(val title: TicketTitle)

object CreateTicket : VoidEventWithParameters<Ticket, CreateTicketParams>(External)

object ArchiveTicket : InstanceEventNoParameters<Ticket>(External)

enum class TicketStates { Open, Archived }

private fun ticketStateMachine() = stateMachine<Ticket, TicketStates, Ctx, TicketAppViews> {
    event(CreateTicket) {}
    event(ArchiveTicket) {}

    voidState {
        onEvent(CreateTicket) {
            createModel(TicketStates.Open, ::newTicket)
        }
    }

    state(TicketStates.Open) {
        onEvent(ArchiveTicket) {
            update(::archivedTitle)
            transitionTo(TicketStates.Archived)
        }
    }

    state(TicketStates.Archived) {
        onEnter {
            delete()
        }
        after(1.hours) {
            delete()
        }
    }
}

private fun newTicket(args: VoidEventArgs<Ticket, CreateTicketParams, Ctx, TicketAppViews>): Ticket =
    Ticket(args.command.params.title)

private fun archivedTitle(args: InstanceEventArgs<Ticket, Nothing?, Ctx, TicketAppViews>): Ticket =
    Ticket(TicketTitle("archived"))

private fun anyoneMayReadTickets(args: ModelReadRuleArgs<Ctx, TicketAppViews>) = PositiveAuthorization.Allow

private fun anyoneMayArchiveTickets(args: CommandRuleArgs<*, Ctx, TicketAppViews>) = PositiveAuthorization.Allow

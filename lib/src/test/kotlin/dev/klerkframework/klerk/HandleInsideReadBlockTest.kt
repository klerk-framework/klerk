package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HandleInsideReadBlockTest {

    private val create = Command(CreateTicket, null, CreateTicketParams(TicketTitle("a")))

    @Test
    fun `handle inside a read block throws instead of deadlocking`() = runBlocking<Unit> {
        val klerk = startTicketKlerk()
        withTimeout(10.seconds) {
            val fromNonSuspendingRead = assertFailsWith<IllegalStateException> {
                klerk.read(Ctx.system()) { runBlocking { klerk.handle(create, Ctx.system()) } }
            }
            assertTrue(fromNonSuspendingRead.message!!.contains("must not be called inside a read block"))

            assertFailsWith<IllegalStateException> {
                klerk.readSuspend(Ctx.system()) { klerk.handle(create, Ctx.system()) }
            }
            assertFailsWith<IllegalStateException> {
                klerk.readSuspend(Ctx.system()) {
                    klerk.handle(create, Ctx.system(), ProcessingOptions(CommandToken.simple(), dryRun = true))
                }
            }

            // commands still work afterwards
            assertIs<CommandResult.Success<*>>(klerk.handle(create, Ctx.system()))
        }
    }
}

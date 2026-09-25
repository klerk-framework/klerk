package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DeleteAuthor
import dev.klerkframework.klerk.IdempotenceProblem
import dev.klerkframework.klerk.ImproveAuthor
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.KlerkSettings
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.SQLiteInMemory
import dev.klerkframework.klerk.StateProblem
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAstridParameters
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.misc.encodeBase64
import dev.klerkframework.klerk.misc.getCurrentInstant
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.to64bitMicroseconds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CommandTokenTest {

    @Test
    fun `Create and parse command tokens`() {
        fun testToken(token: CommandToken) {
            val str = token.toString()
            val parsed = CommandToken.parse(str)
            assertEquals(token, parsed)
        }

        testToken(CommandToken.simple())

        testToken(CommandToken.requireUnmodifiedModel(ModelID<Book>(12345)))

        val modelIds = setOf(ModelID<Author>(453456435), ModelID<Book>(2346136))
        testToken(CommandToken.requireUnmodifiedModels(modelIds))
    }

    @Test
    fun `A token can only be used once`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val token = CommandToken.simple()

            klerk.handle(
                Command(
                    CreateAuthor,
                    createAstridParameters,
                ),
                Ctx.system(),
                ProcessingOptions(token),
            ).getOrThrow()

            val result = klerk.handle(
                Command(
                    CreateAuthor,
                    createAstridParameters,
                ),
                Ctx.system(),
                ProcessingOptions(token),
            )

            when (result) {
                is CommandResult.Failure -> assertIs<IdempotenceProblem>(result.problems.first())
                is CommandResult.Success -> fail()
            }
        }
    }

    @Test
    fun `Prevent command if model has been changed`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)

            val token = CommandToken.requireUnmodifiedModel(astrid)

            klerk.handle(
                Command(
                    ImproveAuthor,
                    astrid,
                ),
                Ctx.system(),
            ).getOrThrow()

            val result = klerk.handle(
                Command(
                    DeleteAuthor,
                    astrid,
                ),
                Ctx.system(),
                ProcessingOptions(token),
            )

            when (result) {
                is CommandResult.Failure -> assertIs<StateProblem>(result.problems.first())
                is CommandResult.Success -> fail()
            }
        }
    }

    @Test
    fun `Two tokens created at the same time are different`() {
        val tokens = (1..1000).map { CommandToken.simple() }
        assertEquals(1000, tokens.toSet().size)
    }

    @Test
    fun `A token that is too old or claims to be from the future is rejected`() {
        runBlocking {
            val klerk = start(RamStorage())
            val validity = KlerkSettings(RamStorage()).commandTokenValidity
            val now = getCurrentInstant()

            for (time in listOf(now - validity - 1.minutes, now + 2.minutes)) {
                val problem = klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system(), tokenAt(time))
                    .let { assertIs<CommandResult.Failure<*>>(it).problems.single() }
                assertEquals(KlerkErrorCode.CommandTokenExpired, problem.code)
            }
            assertIs<CommandResult.Success<*>>(
                klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system(), tokenAt(now - 1.hours)),
            )
        }
    }

    @Test
    fun `A token used before a restart cannot be used after it`() {
        runBlocking {
            val storage = SQLiteInMemory.create()
            val token = ProcessingOptions(CommandToken.simple())
            val first = start(storage)
            first.handle(Command(CreateAuthor, createAstridParameters), Ctx.system(), token).getOrThrow()
            first.meta.stop()

            val second = start(storage)
            val problem = second.handle(Command(CreateAuthor, createAstridParameters), Ctx.system(), token)
                .let { assertIs<CommandResult.Failure<*>>(it).problems.single() }
            assertEquals(KlerkErrorCode.CommandTokenAlreadyUsed, problem.code)
        }
    }

    @Test
    fun `Tokens are forgotten once they have expired`() {
        runBlocking {
            val storage = SQLiteInMemory.create()
            val klerk = start(storage)
            val token = CommandToken.simple()
            klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system(), ProcessingOptions(token))
                .getOrThrow()
            assertEquals(1, storage.readCommandTokens(Instant.DISTANT_PAST).size)

            val validity = KlerkSettings(storage).commandTokenValidity
            (klerk as KlerkImpl).eventsManager.pruneCommandTokens(token.time + validity + 1.minutes, force = true)
            assertEquals(0, storage.readCommandTokens(Instant.DISTANT_PAST).size)
        }
    }

    private suspend fun start(storage: Persistence): Klerk<Ctx, Views> {
        val bc = BookViews()
        val klerk = createKlerk(Views(bc, AuthorViews(bc.all)), storage)
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    /** A token created at [time], which the factory functions cannot make. */
    private fun tokenAt(time: Instant): ProcessingOptions {
        val nonce = kotlin.random.Random.nextLong()
        return ProcessingOptions(CommandToken.parse("n=$nonce:t=${time.to64bitMicroseconds()}:m=".encodeBase64()))
    }
}

package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import dev.klerkframework.klerk.view.*

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

}

package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class CommandTokenTest {

    @Test
    fun `Create and parse command tokens`() {

        fun testToken(token: CommandToken) {
            val str = token.toString()
            val parsed = CommandToken.from(str)
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
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val token = CommandToken.simple()

            klerk.handle(
                Command(
                    event = CreateAuthor,
                    model = null,
                    params = createAstridParameters,
                ),
                Context.system(),
                ProcessingOptions(token),
            ).orThrow()

            val result = klerk.handle(
                Command(
                    event = CreateAuthor,
                    model = null,
                    params = createAstridParameters,
                ),
                Context.system(),
                ProcessingOptions(token),
            )

            when (result) {
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> assertIs<IdempotenceProblem>(result.problem)
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> fail()
            }

        }
    }

    @Test
    fun `Prevent command if model has been changed`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)

            val token = CommandToken.requireUnmodifiedModel(astrid)

            klerk.handle(
                Command(
                    event = ImproveAuthor,
                    model = astrid,
                    null
                ),
                Context.system(),
                ProcessingOptions(CommandToken.simple()),
            ).orThrow()

            val result = klerk.handle(
                Command(
                    event = DeleteAuthor,
                    model = astrid,
                    null
                ),
                Context.system(),
                ProcessingOptions(token),
            )

            when (result) {
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> assertIs<StateProblem>(result.problem)
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> fail()
            }

        }
    }

}

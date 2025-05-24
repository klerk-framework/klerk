package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.CommandResult.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.fail

class ValidatableTest {

    @Test
    fun `Validates created model`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val result = klerk.handle(
                Command(
                    event = CreateAuthor,
                    model = null,
                    params = CreateAuthorParams(
                        FirstName("James"),
                        LastName("Clavell"),
                        PhoneNumber("123"),
                        age = PositiveEvenIntContainer(44),
                        secretToken = SecretPasscode(345),
                        //favouriteColleague = null
                    )
                ),
                context = Context.system(),
                options = ProcessingOptions(CommandToken.simple())
            )

            when (result) {
                is Failure -> assertIs<StateProblem>(result.problem)
                is Success -> fail()
            }
        }
    }

    @Test
    fun `Validates updated model`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)

            val result = klerk.handle(
                Command(
                    event = UpdateAuthor,
                    model = astrid,
                    params = Author(
                        FirstName("James"),
                        LastName("Clavell"),
                        Address(Street("Some street"))
                    )
                ),
                context = Context.system(),
                options = ProcessingOptions(CommandToken.simple())
            )

            when (result) {
                is Failure -> assertIs<StateProblem>(result.problem)
                is Success -> fail()
            }
        }
    }

    @Test
    fun `Validates parameters together`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val result = klerk.handle(
                Command(
                    event = CreateAuthor,
                    model = null,
                    params = CreateAuthorParams(
                        FirstName("August"),
                        LastName("Strindberg"),
                        PhoneNumber("123456"),
                        age = PositiveEvenIntContainer(44),
                        secretToken = SecretPasscode(345),
                        //favouriteColleague = null
                    )
                ),
                context = Context.system(),
                options = ProcessingOptions(CommandToken.simple())
            )

            when (result) {
                is Failure -> {
                    assertIs<StateProblem>(result.problem)
                    result.problem.violatedRule?.let { println("Violated rule: $it") }
                }
                is Success -> fail()
            }
        }
    }
}

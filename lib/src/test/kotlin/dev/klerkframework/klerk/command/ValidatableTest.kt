package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.Address
import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CommandResult.Failure
import dev.klerkframework.klerk.CommandResult.Success
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.CreateAuthorParams
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.InvalidPropertyCollectionProblem
import dev.klerkframework.klerk.LastName
import dev.klerkframework.klerk.PhoneNumber
import dev.klerkframework.klerk.PositiveEvenIntContainer
import dev.klerkframework.klerk.SecretPasscode
import dev.klerkframework.klerk.StateProblem
import dev.klerkframework.klerk.Street
import dev.klerkframework.klerk.UpdateAuthor
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.fail

class ValidatableTest {

    @Test
    fun `Validates created model`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val result = klerk.handle(
                Command(
                    CreateAuthor,
                    CreateAuthorParams(
                        FirstName("James"),
                        LastName("Clavell"),
                        PhoneNumber("123"),
                        age = PositiveEvenIntContainer(44),
                        secretToken = SecretPasscode(345),
                        // favouriteColleague = null
                    ),

                ),
                context = Ctx.system(),
                options = ProcessingOptions(),
            )

            when (result) {
                is Failure -> assertIs<StateProblem>(result.problems.first())
                is Success -> fail()
            }
        }
    }

    @Test
    fun `Validates updated model`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)

            val result = klerk.handle(
                Command(
                    UpdateAuthor,
                    astrid,
                    Author(
                        FirstName("James"),
                        LastName("Clavell"),
                        Address(Street("Some street")),
                        picture = null,
                    ),

                ),
                context = Ctx.system(),
                options = ProcessingOptions(),
            )

            when (result) {
                is Failure -> assertIs<InvalidPropertyCollectionProblem>(result.problems.first())
                is Success -> fail()
            }
        }
    }

    @Test
    fun `Validates parameters together`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val result = klerk.handle(
                Command(
                    CreateAuthor,
                    CreateAuthorParams(
                        FirstName("August"),
                        LastName("Strindberg"),
                        PhoneNumber("123456"),
                        age = PositiveEvenIntContainer(44),
                        secretToken = SecretPasscode(345),
                        // favouriteColleague = null
                    ),

                ),
                context = Ctx.system(),
                options = ProcessingOptions(),
            )

            when (result) {
                is Failure -> {
                    assertIs<InvalidPropertyCollectionProblem>(result.problems.first())
                    result.problems.first().violatedRule?.let { println("Violated rule: $it") }
                }

                is Success -> fail()
            }
        }
    }
}

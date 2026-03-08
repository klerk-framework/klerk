package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*


class ValidatorTest {

    private var klerk: Klerk<Context, MyCollections>

    init {
        val bc = BookViews()
        val collections = MyCollections(bc, AuthorViews(bc.all))
        val config = createConfig(collections, RamStorage())
        klerk = Klerk.create(config)
    }

    @BeforeTest
    fun setup() {
        runBlocking {
            klerk.meta.start()
        }
    }

    @AfterTest
    fun tearDown() {
        klerk.meta.stop()
    }

    @Test
    fun `Validates parameters as a whole`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Mike"),
                lastName = LastName("Litoris"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(CreateAuthor,
                null,
                params)
            val options = ProcessingOptions(CommandToken.simple())
            when (val result = klerk.handle(command, Context.system(), options)) {
                is CommandResult.Failure -> assertTrue(result.problems.first().violatedRule == null)
                is CommandResult.Success -> fail()
            }
        }
    }

    @Test
    fun `Validates context`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Pelle"),
                lastName = LastName("Svensson"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(CreateAuthor,
                null,
                params)
            val options = ProcessingOptions(CommandToken.simple())
            val result = klerk.handle(command, Context.unauthenticated(), options)
            assertTrue(result is CommandResult.Failure, result.toString())
        }
    }

    @Test
    fun `Validates parameters and context`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Daniel"),
                lastName = LastName("Svensson"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(CreateAuthor,
                null,
                params)
            val options = ProcessingOptions(CommandToken.simple())
            val result = klerk.handle(command, Context.system(), options)
            assertTrue(result is CommandResult.Failure)
        }
    }

    @Test
    fun mittTest() {
        val a = IntWithTwoOrFourOrThree(2)
        a.initAuthorization(false)
        println("Klar: ${a.valueOrNullIfNotAuthorized}")
    }
}


open class EvenIntContainer(value: Int) : IntContainer(value) {

    init {
        require(value % 2 == 0) { "Value must be even" }
        println("A")
    }

    override val min: Int = 0

    override val max: Int = 100

}

class IntWithTwoOrFourOrThree(value: Int) : EvenIntContainer(value) {
    init {
        require(value == 2 || value == 3 || value == 4) { "Value must be 2 or 4" }
        println("B")
    }

    override val min: Int = 0

    override val max: Int = 100

}

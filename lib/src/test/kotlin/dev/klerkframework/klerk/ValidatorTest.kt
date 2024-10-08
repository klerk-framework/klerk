package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*


class ValidatorTest {

    private var klerk: Klerk<Context, MyCollections>

    init {
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
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
                age = EvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(CreateAuthor,
                null,
                params)
            val options = ProcessingOptions(CommandToken.simple())
            when (val result = klerk.handle(command, Context.system(), options)) {
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> assertTrue(result.problem.violatedRule == null)
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> fail()
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
                age = EvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(CreateAuthor,
                null,
                params)
            val options = ProcessingOptions(CommandToken.simple())
            val result = klerk.handle(command, Context.unauthenticated(), options)
            assertTrue(result is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure, result.toString())
        }
    }

    @Test
    fun `Validates parameters and context`() {
        runBlocking {
            val params = CreateAuthorParams(
                firstName = FirstName("Daniel"),
                lastName = LastName("Svensson"),
                phone = PhoneNumber("234"),
                age = EvenIntContainer(44),
                secretToken = SecretPasscode(234)
            )
            val command = Command(CreateAuthor,
                null,
                params)
            val options = ProcessingOptions(CommandToken.simple())
            val result = klerk.handle(command, Context.system(), options)
            assertTrue(result is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure)
        }
    }
}

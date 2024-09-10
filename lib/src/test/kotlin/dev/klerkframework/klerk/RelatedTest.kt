package dev.klerkframework.klerk


import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.fail

class RelatedTest {

    @Test
    fun cannotCreateReferenceToNonExistingModel() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()

            val command = Command(
                event = CreateBook,
                model = null,
                params = CreateBookParams(
                    title = BookTitle("Pelle"),
                    author = ModelID<Author>(99),
                    coAuthors = emptySet(),
                    previousBooksInSameSeries = emptyList(),
                    tags = emptySet(),
                    averageScore = AverageScore(0f)
                ),
            )

            when (val result = klerk.handle(
                command,
                Context.system(),
                ProcessingOptions(CommandToken.simple()),
            )) {
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> assert(result.problem.asException().message!!.contains("Did not find"))
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> fail("Should not succeed")
            }

        }

    }
}

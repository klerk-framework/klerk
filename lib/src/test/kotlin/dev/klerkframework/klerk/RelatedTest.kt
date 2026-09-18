package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.days

class RelatedTest {

    @Test
    fun cannotCreateReferenceToNonExistingModel() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()

            val command = Command(
                CreateBook,
                CreateBookParams(
                    title = BookTitle("Pelle"),
                    author = ModelID<Author>(99),
                    coAuthors = emptySet(),
                    previousBooksInSameSeries = emptyList(),
                    tags = emptySet(),
                    averageScore = AverageScore(0f),
                    readingTime = ReadingTime(1.days),
                ),
            )

            when (
                val result = klerk.handle(
                    command,
                    Ctx.system(),
                )
            ) {
                is CommandResult.Failure -> assert(
                    result.problems.first().asException().message!!.contains("Did not find"),
                )

                is CommandResult.Success -> fail("Should not succeed")
            }
        }
    }
}

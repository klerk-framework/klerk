package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.GeoPosition
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes


class StorageTest {

    @Test
    fun `Can store and retrieve models`() {
        val storage = RamStorage()
        val context = Context.unauthenticated()

        val authorProps = Author(
            firstName = FirstName("Pelle"),
            lastName = LastName("Andersson"),
            address = Address(Street("Storgatan 12"))
        )

        val now = Clock.System.now()

        val command1 = Command(
            event = ImproveAuthor,
            model = null,
            params = null
        )
        val author = Model(
            id = ModelID(234),
            createdAt = now,
            lastPropsUpdateAt = now,
            lastStateTransitionAt = now,
            state = AuthorStates.Amateur.name,
            timeTrigger = null,
            props = authorProps
        )
        val result1 = ProcessingData<Author, Context, MyCollections>(
            createdModels = listOf(author.id),
            aggregatedModelState = mapOf(author.id to author)
        )

        storage.store(result1, command1, context)

        val command2 = Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(title = BookTitle("The Hobbit"),
                author = author.id,
                averageScore = AverageScore(0f),)
        )
        val bookProps = Book(title = BookTitle("The Hobbit"),
            author = author.id,
            coAuthors = emptySet(),
            previousBooksInSameSeries = emptyList(),
            tags = emptySet(),
            averageScore = AverageScore(0f),
            salesPerYear = setOf<Quantity>(),
            writtenAt = BookWrittenAt(Clock.System.now()),
            readingTime = ReadingTime(3.minutes),
            publishedAt = null,
            releasePartyPosition = ReleasePartyPosition(GeoPosition(0.0, 0.0)),
            )
        val book = Model(
            id = ModelID(123),
            createdAt = now,
            lastPropsUpdateAt = now,
            state = BookStates.Draft.name,
            timeTrigger = null,
            props = bookProps,
            lastStateTransitionAt = now)

        val result2 = ProcessingData<Book, Context, MyCollections>(
            createdModels = listOf(ModelID(123)),
            aggregatedModelState = mapOf(book.id to book)
        )
        storage.store(result2, command2, context)

        var modelsRead = 0
        storage.readAllModels { modelsRead++ }
        assertEquals(2, modelsRead)
    }

}

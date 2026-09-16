package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.GeoPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes


class StorageTest {

    @Test
    fun `Can store and retrieve models`() {
        val storage = RamStorage()

        val authorProps = Author(
            firstName = FirstName("Pelle"),
            lastName = LastName("Andersson"),
            address = Address(Street("Storgatan 12")),
            picture = null,
        )

        val now = Clock.System.now()

        val author = Model(
            id = ModelID(234),
            createdAt = now,
            lastPropsUpdatedAt = now,
            lastStateTransitionAt = now,
            state = AuthorStates.Amateur.name,
            timeTrigger = null,
            props = authorProps,
        )
        storage.store(CommitBatch(createdModels = listOf(author)))

        val bookProps = Book(
            title = BookTitle("The Hobbit"),
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
            genre = BookGenreContainer(BookGenre.Fiction),
        )
        val book = Model(
            id = ModelID(123),
            createdAt = now,
            lastPropsUpdatedAt = now,
            state = BookStates.Draft.name,
            timeTrigger = null,
            props = bookProps,
            lastStateTransitionAt = now,
        )

        storage.store(CommitBatch(createdModels = listOf(book)))

        var modelsRead = 0
        storage.readAllModels { modelsRead++ }
        assertEquals(2, modelsRead)

        assertEquals(author, storage.readModel(author.id.value))
        assertEquals(book, storage.readModel(book.id.value))
        assertNull(storage.readModel(999), "readModel must return null for an id that does not exist")

        storage.store(CommitBatch(deletedModels = listOf(book.id)))
        assertNull(storage.readModel(book.id.value), "readModel must not return a deleted model")
    }

}

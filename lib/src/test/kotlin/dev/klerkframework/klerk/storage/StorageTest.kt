package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals


class StorageTest {

    @Test
    fun `Can store and retrieve models`() {
        val storage = RamStorage()

        val authorProps = Author(
            firstName = FirstName("Pelle"),
            lastName = LastName("Andersson"),
            address = Address(Street("Storgatan 12"))
        )

        val now = Clock.System.now()

        val command = Command(
            event = ImproveAuthor,
            model = null,
            params = null
        )
        val model = Model(
            id = ModelID(234),
            createdAt = now,
            lastPropsUpdateAt = now,
            lastStateTransitionAt = now,
            state = AuthorStates.Amateur.name,
            timeTrigger = null,
            props = authorProps
        )
        val result = ProcessingData<Author, Context, MyCollections>(
            createdModels = listOf(model.id),
            aggregatedModelState = mapOf(model.id to model)
        )
        val context = Context.unauthenticated()

        storage.store(result, command, context)

        var modelsRead = 0
        storage.readAllModels { modelsRead++ }
        assertEquals(1, modelsRead)
    }

}

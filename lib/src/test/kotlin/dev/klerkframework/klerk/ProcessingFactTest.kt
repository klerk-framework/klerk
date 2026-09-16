package dev.klerkframework.klerk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ProcessingFactTest {

    @Test
    fun `Transition plus update in exit block`() {
        val transitionTime = Instant.fromEpochMilliseconds(1)
        val updateTime = Instant.fromEpochMilliseconds(2)

        val authorId = ModelID<Author>(123)
        val author = Author(
            firstName = FirstName("Astrid"),
            lastName = LastName("Lindgren"),
            address = Address(Street("Storgatan 1")),
            picture = null,
        )
        val authorModel = Model(
            id = authorId,
            createdAt = Instant.DISTANT_PAST,
            lastStateTransitionAt = Instant.DISTANT_PAST,
            lastPropsUpdatedAt = Instant.DISTANT_PAST,
            state = "first",
            timeTrigger = null,
            props = author,
        )

        val withTransition = ProcessingData<Author, Ctx, Views>(
            currentModel = authorId,
            unFinalizedTransition = Triple("second", transitionTime, authorModel),
        )

        val withUpdate = ProcessingData<Author, Ctx, Views>(
            updatedModels = listOf(authorId),
            aggregatedModelState = mapOf(
                authorId to authorModel.copy(
                    lastPropsUpdatedAt = updateTime,
                    props = author.copy(firstName = FirstName("Another")),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val modifiedModel = withTransition.merge(withUpdate, true).aggregatedModelState[authorId] as Model<Author>
        assertEquals("second", modifiedModel.state)
        assertEquals("Another", modifiedModel.props.firstName.value)
        assertEquals(transitionTime, modifiedModel.lastStateTransitionAt)
        assertEquals(updateTime, modifiedModel.lastPropsUpdatedAt)
        assertEquals(updateTime, modifiedModel.lastModifiedAt)
    }
}

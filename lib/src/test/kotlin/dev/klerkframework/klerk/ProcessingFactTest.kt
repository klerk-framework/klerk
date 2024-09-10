package dev.klerkframework.klerk

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingFactTest {

    @Test
    fun `Transition plus update in exit block`() {
        val transitionTime = Instant.fromEpochMilliseconds(1)
        val updateTime = Instant.fromEpochMilliseconds(2)

        val authorId = ModelID<Author>(123)
        val author = Author(
            firstName = FirstName("Astrid"),
            lastName = LastName("Lindgren"),
            address = Address(Street("Storgatan 1"))
        )
        val authorModel = Model(
            id = authorId,
            createdAt = Instant.DISTANT_PAST,
            lastStateTransitionAt = Instant.DISTANT_PAST,
            lastPropsUpdateAt = Instant.DISTANT_PAST,
            state = "first",
            timeTrigger = null,
            props = author
        )

        val withTransition = ProcessingData<Author, Context, MyCollections>(
            currentModel = authorId,
            unFinalizedTransition = Triple("second", transitionTime, authorModel)
        )

        val withUpdate = ProcessingData<Author, Context, MyCollections>(
            updatedModels = listOf(authorId),
            aggregatedModelState = mapOf(authorId to authorModel.copy(lastPropsUpdateAt = updateTime, props = author.copy(firstName = FirstName("Another"))))
        )

        @Suppress("UNCHECKED_CAST")
        val modifiedModel = withTransition.merge(withUpdate, true).aggregatedModelState[authorId] as Model<Author>
        assertEquals("second", modifiedModel.state)
        assertEquals("Another", modifiedModel.props.firstName.value)
        assertEquals(transitionTime, modifiedModel.lastStateTransitionAt)
        assertEquals(updateTime, modifiedModel.lastPropsUpdateAt)
        assertEquals(updateTime, modifiedModel.lastModifiedAt)
    }
}

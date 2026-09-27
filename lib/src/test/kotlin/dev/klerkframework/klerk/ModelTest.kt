package dev.klerkframework.klerk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ModelTest {

    private val props = Author(
        firstName = FirstName("Astrid"),
        lastName = LastName("Lindgren"),
        address = Address(Street("Storgatan 1")),
        picture = null,
    )

    private fun model(
        createdAt: Instant = Instant.fromEpochMilliseconds(1),
        lastPropsUpdatedAt: Instant = Instant.fromEpochMilliseconds(2),
        lastStateTransitionAt: Instant = Instant.fromEpochMilliseconds(3),
        timeTrigger: Instant? = null,
    ) = Model(
        id = ModelID<Author>(1),
        createdAt = createdAt,
        lastPropsUpdatedAt = lastPropsUpdatedAt,
        lastStateTransitionAt = lastStateTransitionAt,
        state = "first",
        timeTrigger = timeTrigger,
        props = props,
    )

    @Test
    fun `timestamps round-trip at microsecond precision`() {
        val instant = Instant.fromEpochSeconds(1_700_000_000, 123_456_000)
        val m = model(createdAt = instant)
        assertEquals(instant, m.createdAt)
    }

    @Test
    fun `sub-microsecond precision is truncated, same as after a trip through Persistence`() {
        val instant = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
        val truncated = Instant.fromEpochSeconds(1_700_000_000, 123_456_000)
        val m = model(createdAt = instant)
        assertEquals(truncated, m.createdAt)
    }

    @Test
    fun `equals and hashCode agree for models built from equal timestamps`() {
        val a = model()
        val b = model()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy can change timeTrigger without touching the other timestamps`() {
        val original = model()
        val withTrigger = original.copy(timeTriggerMicros = Instant.fromEpochMilliseconds(99).to64bitMicroseconds())
        assertEquals(Instant.fromEpochMilliseconds(99), withTrigger.timeTrigger)
        assertEquals(original.createdAt, withTrigger.createdAt)
        assertNotEquals(original, withTrigger)

        val cleared = withTrigger.copy(timeTriggerMicros = null)
        assertNull(cleared.timeTrigger)
    }

    @Test
    fun `copy can change lastStateTransitionAt and state together`() {
        val original = model()
        val newTime = Instant.fromEpochMilliseconds(42)
        val transitioned = original.copy(state = "second", lastStateTransitionAtMicros = newTime.to64bitMicroseconds())
        assertEquals("second", transitioned.state)
        assertEquals(newTime, transitioned.lastStateTransitionAt)
    }
}

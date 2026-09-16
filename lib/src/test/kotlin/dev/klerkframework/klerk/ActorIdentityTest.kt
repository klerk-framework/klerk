package dev.klerkframework.klerk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActorIdentityTest {

    @Test
    fun `identities of the same form compare by value`() {
        assertEquals(ModelReferenceIdentity(ModelID<Any>(1)), ModelReferenceIdentity(ModelID<Any>(1)))
        assertEquals(
            ModelReferenceIdentity(ModelID<Any>(1)).hashCode(),
            ModelReferenceIdentity(ModelID<Any>(1)).hashCode(),
        )
        assertNotEquals(ModelReferenceIdentity(ModelID<Any>(1)), ModelReferenceIdentity(ModelID<Any>(2)))
        assertEquals(CustomIdentity(null, 5), CustomIdentity(null, 5))
        assertNotEquals(CustomIdentity(null, 5), CustomIdentity(null, 6))
    }

    @Test
    fun `isSameAs treats a model and its reference as the same actor`() {
        val reference = ModelReferenceIdentity(ModelID<Any>(1))
        val custom = CustomIdentity(ModelID(1), null)
        assertNotEquals<ActorIdentity>(reference, custom)
        assertTrue(reference.isSameAs(custom))
        assertFalse(reference.isSameAs(ModelReferenceIdentity(ModelID<Any>(2))))
        assertFalse(reference.isSameAs(SystemIdentity))
        assertTrue(SystemIdentity.isSameAs(SystemIdentity))
        assertFalse(SystemIdentity.isSameAs(Unauthenticated))
    }
}

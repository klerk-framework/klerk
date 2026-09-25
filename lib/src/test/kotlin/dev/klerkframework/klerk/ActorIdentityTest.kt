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

    @Test
    fun `an actor without id or externalId is never the same as anyone`() {
        assertFalse(Unauthenticated.isSameAs(Unauthenticated))
        assertFalse(CustomIdentity(null, null).isSameAs(CustomIdentity(null, null)))
        assertTrue(AuthenticationIdentity.isSameAs(AuthenticationIdentity))
        assertFalse(AuthenticationIdentity.isSameAs(SystemIdentity))
        assertTrue(CustomIdentity(null, 5).isSameAs(CustomIdentity(null, 5)))
        assertFalse(CustomIdentity(null, 5).isSameAs(CustomIdentity(null, 6)))
    }

    @Test
    fun `plugins are the same actor when they have the same name`() {
        assertTrue(PluginIdentity("images").isSameAs(PluginIdentity("images")))
        assertFalse(PluginIdentity("images").isSameAs(PluginIdentity("assets")))
        assertFalse(PluginIdentity("images").isSameAs(SystemIdentity))
        assertFalse(SystemIdentity.isSameAs(PluginIdentity("images")))
    }
}

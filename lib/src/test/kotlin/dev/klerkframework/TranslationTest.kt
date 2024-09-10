package dev.klerkframework

import dev.klerkframework.klerk.CreateAuthorParams
import dev.klerkframework.klerk.SwedishTranslation
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationTest {

    @Test
    fun `Can translate properties`() {
        assertEquals("Förnamn på den nya författaren", SwedishTranslation().property(CreateAuthorParams::firstName))
    }

}

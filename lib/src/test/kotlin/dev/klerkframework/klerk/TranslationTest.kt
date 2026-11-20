package dev.klerkframework.klerk

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationTest {

    @Test
    fun `Can translate properties`() {
        assertEquals("Förnamn på den nya författaren", SwedishTranslation.klerk.property(CreateAuthorParams::firstName))
    }

    @Test
    fun `Can use custom annotations`() {
        assertEquals(FUNCTION_NAME, SwedishTranslation.klerk.function(::myGreatFunction))
    }

    @Test
    fun `A rule is named after its function`() {
        assertEquals("Must be even", DefaultKlerkTranslation.function(::mustBeEven))
    }

    private fun mustBeEven(): Int = 2

    @Test
    fun `Swedish translates all texts except those that are the same`() {
        assertEquals(listOf(KlerkText.Id), SwedishKlerkTranslation.untranslatedTexts())
    }

    @Test
    fun `untranslatedTexts lists the texts that are still English`() {
        assertEquals(KlerkText.entries, DefaultKlerkTranslation.untranslatedTexts())
        val partly = object : KlerkTranslation by DefaultKlerkTranslation {
            override fun text(key: KlerkText) = if (key == KlerkText.Unauthorized) "Obehörig" else key.english
        }
        assertEquals(KlerkText.entries - KlerkText.Unauthorized, partly.untranslatedTexts())
    }

    @Test
    fun test() {
        val evenInt = PositiveEvenIntContainer(3)
        val validation = evenInt.validate("myEvenIntField", SwedishTranslation)
        if (validation?.endUserTranslatedMessage != null) {
            println(validation.endUserTranslatedMessage)
        }
    }
}

annotation class MyFunctionAnnotation(val name: String)

const val FUNCTION_NAME = "Detta är en annoterad översättning"

@MyFunctionAnnotation(FUNCTION_NAME)
fun myGreatFunction() = "Hello World!"

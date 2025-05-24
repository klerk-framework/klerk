package dev.klerkframework.klerk

import dev.klerkframework.klerk.validation.PropertyValidation
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

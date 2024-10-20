package dev.klerkframework.klerk.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UtilTest {

    @Test
    fun prettyNameTest() {
        assertEquals("My car with a round wheel", camelCaseToPretty("myCarWithARoundWheel"))
    }

    @Test
    fun base64() {
        val original = "I am testing"
        val encoded = original.encodeBase64()
        val decoded = encoded.decodeBase64String()
        assertNotEquals(original, encoded)
        assertEquals(original, decoded)
    }

}

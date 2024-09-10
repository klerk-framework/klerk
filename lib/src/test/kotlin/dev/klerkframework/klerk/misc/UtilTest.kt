package dev.klerkframework.klerk.misc

import kotlin.test.Test
import kotlin.test.assertEquals

class UtilTest {

    @Test
    fun prettyNameTest() {
        assertEquals("My car with a round wheel", camelCaseToPretty("myCarWithARoundWheel"))
    }

}

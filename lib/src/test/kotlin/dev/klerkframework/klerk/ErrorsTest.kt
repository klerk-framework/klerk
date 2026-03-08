package dev.klerkframework.klerk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorsTest {

    @Test
    fun `error codes are unique`() {
        val codes = KlerkErrorCode.entries.map { it.code }
        val uniqueCodes = codes.toSet()
        assertEquals(codes.size, uniqueCodes.size, "Error codes must be unique")
    }

    @Test
    fun `error codes start with ERROR-`() {
        KlerkErrorCode.entries.forEach { code ->
            assertTrue(code.code.startsWith("ERROR-"), "Bad error code '${code.code}'")
        }
    }
}

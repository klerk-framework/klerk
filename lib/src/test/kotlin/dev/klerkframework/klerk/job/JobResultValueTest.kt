package dev.klerkframework.klerk.job

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobResultValueTest {

    @Serializable
    data class ImportSummary(val imported: Int, val skipped: Int = 0)

    private fun outcome(result: String?) = ChildOutcome(
        id = JobId(1),
        name = JobName("child"),
        status = JobStatus.Succeeded,
        result = result,
    )

    @Test
    fun `a result survives the round trip`() {
        val encoded = encodeJobResult(ImportSummary(imported = 3, skipped = 1))
        assertEquals(ImportSummary(imported = 3, skipped = 1), outcome(encoded).resultAs<ImportSummary>())
    }

    @Test
    fun `a plain value works too`() {
        assertEquals(42, outcome(encodeJobResult(42)).resultAs<Int>())
        assertEquals("done", outcome(encodeJobResult("done")).resultAs<String>())
    }

    @Test
    fun `no result gives null`() {
        assertNull(outcome(null).resultAs<ImportSummary>())
    }
}

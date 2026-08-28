package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.Quantity
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReflectedKtTest {

    @Test
    fun extractValueTypes() {
        val pelle: Set<Quantity> = setOf()
        val extracted = extractValueClasses(pelle::class)
        println(extracted.size)
    }

    private class OnlyNullableInstant(value: Instant) : InstantContainer(value)
    private class OnlyNullableName(value: String) : StringContainer(value) {
        override val minLength = 0
        override val maxLength = 100
        override val maxLines = 1
    }

    private data class ModelWithNullableOnlyContainers(
        val required: Quantity,
        val optionalInstant: OnlyNullableInstant?,
        val optionalName: OnlyNullableName?,
    )

    @Test
    fun `nullable-only container properties are extracted`() {
        val extracted = extractValueClasses(ModelWithNullableOnlyContainers::class)
        assertTrue(OnlyNullableInstant::class in extracted, "nullable InstantContainer property must be collected")
        assertTrue(OnlyNullableName::class in extracted, "nullable StringContainer property must be collected")
        assertTrue(Quantity::class in extracted)
    }
}

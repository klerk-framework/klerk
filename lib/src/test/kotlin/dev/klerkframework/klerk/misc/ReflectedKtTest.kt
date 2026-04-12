package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.Quantity
import kotlin.test.Test

class ReflectedKtTest {

    @Test
    fun extractValueTypes() {
        val pelle: Set<Quantity> = setOf()
        val extracted = extractValueClasses(pelle::class)
        println(extracted.size)
    }

}
package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.Quantity
import kotlin.reflect.full.starProjectedType
import kotlin.test.Test

class ReflectedKtTest {

    @Test
    fun extractValueTypes() {
        val pelle: Set<Quantity> = setOf()
        val extracted = extractValueClasses(pelle::class)
        println(extracted.size)
    }

}
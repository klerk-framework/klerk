package dev.klerkframework.klerk

import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.reflect.KParameter
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelIDTest {

    @Test
    fun basicStuff() {

        fun testInt(l: Int) {
            val original = ModelID<Any>(l)
            assertEquals(original.toInt(), ModelID.from<Any>(original.toString()).toInt())
        }

        listOf(
            Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE - 1, 1, 0, -1, 1234, 23642645, -1254287
        ).forEach { testInt(it) }

        val rand = Random(System.currentTimeMillis())
        for (i in 0..1000) {
            val ulong = rand.nextInt(Int.MIN_VALUE, Int.MAX_VALUE)
            testInt(ulong)
        }
    }

    /**
     * There was a problem when ModelID was an inline value class.
     */
    @Test
    fun `@JvmInline and value class problem`() {
        val raw = "3f"  // 123u
        val param = ModelID.from<Any>(raw)
        val parameters = mutableMapOf<KParameter, Any?>()
        val constructor = MyClassWithModelID::class.constructors.first()
        val idParam = constructor.parameters.first()
        parameters[idParam] = param
        val instance = constructor.callBy(parameters)
        assertEquals(instance.id?.toInt(), 123)
    }

}

data class MyClassWithModelID(val id: ModelID<Author>?)

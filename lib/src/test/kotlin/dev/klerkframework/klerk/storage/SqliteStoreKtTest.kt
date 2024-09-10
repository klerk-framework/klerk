package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.decode64bitMicroseconds
import dev.klerkframework.klerk.to64bitMicroseconds
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds


class SqliteStoreKtTest {

    @Test
    fun instant() {
        val epoch = Instant.fromEpochMilliseconds(0)
        val instants = mapOf<Instant, Long>(
            epoch to 0,
            epoch.plus(1.seconds) to 1000000,
            epoch.plus(1.seconds).plus(1.milliseconds) to 1001000,
            epoch.plus(400.seconds).plus(999999000.nanoseconds) to 400999999,
            epoch.plus(401.seconds) to 401000000,
        )
        instants.forEach { (i, l) -> assertEquals(l, i.to64bitMicroseconds()) }
        instants.keys.forEach { assertEquals(it, decode64bitMicroseconds(it.to64bitMicroseconds())) }

        val randomLong = Random(System.currentTimeMillis()).nextLong(1, Int.MAX_VALUE.toLong())
        val randomInstant = Instant.fromEpochMilliseconds(randomLong)
        assertEquals(randomInstant, decode64bitMicroseconds(randomInstant.to64bitMicroseconds()))

        val instantMin = java.time.Instant.MIN.toKotlinInstant()
        assertEquals(instantMin.to64bitMicroseconds(), decode64bitMicroseconds(Long.MIN_VALUE).to64bitMicroseconds())

        val instantMax = java.time.Instant.MAX.toKotlinInstant()
        assertEquals(instantMax.to64bitMicroseconds(), decode64bitMicroseconds(Long.MAX_VALUE).to64bitMicroseconds())
    }

}

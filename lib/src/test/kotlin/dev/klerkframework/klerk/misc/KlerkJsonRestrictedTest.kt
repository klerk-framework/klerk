package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.BookGenre
import dev.klerkframework.klerk.BookGenreContainer
import dev.klerkframework.klerk.ReleasePartyPosition
import dev.klerkframework.klerk.TestDate
import dev.klerkframework.klerk.TestDuration
import dev.klerkframework.klerk.TestInstant
import dev.klerkframework.klerk.datatypes.GeoPosition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class RestrictedHolder(
    val instant: TestInstant,
    val date: TestDate,
    val duration: TestDuration,
    val genre: BookGenreContainer,
    val position: ReleasePartyPosition,
)

class KlerkJsonRestrictedTest {

    private fun holder() = RestrictedHolder(
        TestInstant(Instant.parse("2026-01-01T00:00:00Z")),
        TestDate(LocalDate(2026, 1, 1)),
        TestDuration(90.minutes),
        BookGenreContainer(BookGenre.Fiction),
        ReleasePartyPosition(GeoPosition(59.33, 18.06)),
    )

    @Test
    fun `a container the actor may not read is encoded like any other`() {
        val restricted = holder().also {
            listOf(it.instant, it.date, it.duration, it.genre, it.position).forEach { c -> c.initAuthorization(false) }
        }

        assertEquals(KlerkJson.encode(holder()), KlerkJson.encode(restricted))
        assertEquals(KlerkJson.encodeForLog(holder()), KlerkJson.encodeForLog(restricted))
    }
}

package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.instantToStringFormat
import dev.klerkframework.klerk.misc.KlerkJson
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.LocalDate
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class TestInstant(value: Instant) : InstantContainer(value)
private class TestDate(value: LocalDate) : DateContainer(value)
private class TestDuration(value: Duration) : DurationContainer(value)
private data class GenreHolder(val genre: BookGenreContainer)

class DataContainersTest {

    @Test
    fun getValueType() {

        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val specification = createConfig(collections)

        println(specification)

    }

    @Test
    fun geoPosition() {

        try {
            GeoPosition(latitude = 95.0, longitude = 34.2)
            fail()
        } catch (e: IllegalArgumentException) {
            //
        }

        listOf(
            GeoPosition(latitude = 0.0, longitude = 0.0),

            GeoPosition(latitude = 0.0, longitude = -1.0),
            GeoPosition(latitude = -1.0, longitude = 0.0),

            GeoPosition(latitude = 0.0, longitude = 1.0),
            GeoPosition(latitude = 1.0, longitude = 0.0),

            GeoPosition(latitude = 0.0, longitude = 0.1),
            GeoPosition(latitude = 0.1, longitude = 0.0),

            GeoPosition(latitude = 0.0, longitude = -0.1),
            GeoPosition(latitude = -0.1, longitude = 0.0),

            GeoPosition(latitude = 1.1234567, longitude = 0.9876543),
            GeoPosition(latitude = -1.1234567, longitude = -0.9876543),

            GeoPosition(latitude = 0.0000001, longitude = 0.0000001),

            GeoPosition(latitude = 90.0, longitude = 180.0),
            GeoPosition(latitude = -90.0, longitude = -180.0),
        )
            .forEach { original ->
                assertEquals(original, GeoPosition(original.uLongEncoded))
            }

        val random = Random.Default
        for (i in 1..10) {
            val pos =
                GeoPosition(latitude = random.nextDouble(-90.0, 90.0), longitude = random.nextDouble(-180.0, 180.0))
            val decoded = GeoPosition(pos.uLongEncoded)
            // doesn't have to be equal but should be really close
            assertTrue((pos.latitude - decoded.latitude).absoluteValue < 0.0000001)
            assertTrue((pos.longitude - decoded.longitude).absoluteValue < 0.0000001)
        }

    }

    @Test
    fun geoPositionISO6709() {
        val positions = listOf(
            GeoPosition(latitude = 48.8577, longitude = 2.295),
            GeoPosition(latitude = 0.0, longitude = 0.0),
            GeoPosition(latitude = -90.0, longitude = -180.0),
            GeoPosition(latitude = 90.0, longitude = 180.0),
            GeoPosition(latitude = -1.1234567, longitude = -0.9876543),
            GeoPosition(latitude = 40.6894, longitude = -74.0447),
        )

        positions.forEach { original ->
            val iso = original.toISO6709()
            assertTrue(iso.endsWith("/"), "ISO 6709 string should end with /")
            val decoded = GeoPosition.fromISO6709(iso)
            assertEquals(original.latitude, decoded.latitude, 0.000001)
            assertEquals(original.longitude, decoded.longitude, 0.000001)
        }

        // Check format: +DD.DDDDDD+DDD.DDDDDD/
        assertEquals("+48.857700+002.295000/", GeoPosition(48.8577, 2.295).toISO6709())
        assertEquals("+00.000000+000.000000/", GeoPosition(0.0, 0.0).toISO6709())
        assertEquals("-90.000000-180.000000/", GeoPosition(-90.0, -180.0).toISO6709())
        assertEquals("+40.689400-074.044700/", GeoPosition(40.6894, -74.0447).toISO6709())
    }

    @Test
    fun enumContainerValidation() {
        val container = BookGenreContainer(BookGenre.Fiction)
        assertNull(container.validate("genre", DefaultTranslation))
        assertEquals(BookGenre.Fiction, container.enum)
        assertEquals("Fiction", container.valueWithoutAuthorization)
    }

    @Test
    fun timeContainerToString() {
        assertEquals("2026-01-05", TestDate(LocalDate.of(2026, 1, 5)).toString())

        assertEquals("1h 30m", TestDuration(90.minutes).toString())
        assertEquals("0s", TestDuration(Duration.ZERO).toString())

        val instant = Instant.parse("2026-01-01T00:00:00Z")
        val s = TestInstant(instant).toString()
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(s), "unexpected format: $s")
        assertEquals(
            instantToStringFormat.format(instant.toLocalDateTime(TimeZone.currentSystemDefault())),
            s,
        )
    }

    @Test
    fun timeContainerToStringMasked() {
        val c = TestDuration(5.minutes)
        c.initAuthorization(false)
        assertEquals("[••••••]", c.toString())
    }

    @Test
    fun enumContainerSerialization() {
        val original = GenreHolder(BookGenreContainer(BookGenre.Mystery))
        val deserialized = KlerkJson.decode(GenreHolder::class, KlerkJson.encode(original))
        assertEquals(original, deserialized)
        assertEquals(BookGenre.Mystery, deserialized.genre.enum)
    }

}

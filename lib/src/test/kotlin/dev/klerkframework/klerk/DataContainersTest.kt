package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.ByteContainer
import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DoubleContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.ShortContainer
import dev.klerkframework.klerk.datatypes.UByteContainer
import dev.klerkframework.klerk.datatypes.UIntContainer
import dev.klerkframework.klerk.datatypes.ULongContainer
import dev.klerkframework.klerk.datatypes.UShortContainer
import dev.klerkframework.klerk.datatypes.instantToStringFormat
import dev.klerkframework.klerk.misc.KlerkJson
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDate
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TestInstant(value: Instant) : InstantContainer(value)
class TestDate(value: LocalDate) : DateContainer(value)
class TestDuration(value: Duration) : DurationContainer(value)
data class GenreHolder(val genre: BookGenreContainer)

class TestShort(value: Short) : ShortContainer(value) {
    override val min: Short = -100
    override val max: Short = 100
}

class TestByte(value: Byte) : ByteContainer(value) {
    override val min: Byte = -10
    override val max: Byte = 10
}

class TestULong(value: ULong) : ULongContainer(value) {
    override val min: ULong = 0uL
    override val max: ULong = ULong.MAX_VALUE
}

class TestUInt(value: UInt) : UIntContainer(value) {
    override val min: UInt = 0u
    override val max: UInt = 100u
}

class TestUShort(value: UShort) : UShortContainer(value) {
    override val min: UShort = 0u
    override val max: UShort = 100u
}

class TestUByte(value: UByte) : UByteContainer(value) {
    override val min: UByte = 0u
    override val max: UByte = 100u
}

class TestDouble(value: Double) : DoubleContainer(value) {
    override val min: Double = -100.0
    override val max: Double = 100.0
}

data class NumberHolder(
    val short: TestShort,
    val byte: TestByte,
    val uLong: TestULong,
    val uInt: TestUInt,
    val uShort: TestUShort,
    val uByte: TestUByte,
    val double: TestDouble,
)

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
            val decoded = GeoPosition.parse(iso)
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
        assertEquals(BookGenre.Fiction, container.value)
        assertEquals(BookGenre.Fiction, container.valueWithoutAuthorization)
    }

    @Test
    fun timeContainerToString() {
        assertEquals("2026-01-05", TestDate(LocalDate(2026, 1, 5)).toString())

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
    fun numberContainersValidation() {
        assertNull(TestShort(50).validate("short", DefaultTranslation))
        assertNotNull(TestShort(-101).validate("short", DefaultTranslation))
        assertNotNull(TestShort(101).validate("short", DefaultTranslation))

        assertNull(TestByte(5).validate("byte", DefaultTranslation))
        assertNotNull(TestByte(-11).validate("byte", DefaultTranslation))

        assertNull(TestULong(1000uL).validate("uLong", DefaultTranslation))

        assertNull(TestUInt(50u).validate("uInt", DefaultTranslation))
        assertNotNull(TestUInt(101u).validate("uInt", DefaultTranslation))

        assertNull(TestUShort(50u).validate("uShort", DefaultTranslation))
        assertNotNull(TestUShort(101u).validate("uShort", DefaultTranslation))

        assertNull(TestUByte(50u).validate("uByte", DefaultTranslation))
        assertNotNull(TestUByte(101u).validate("uByte", DefaultTranslation))

        assertNull(TestDouble(3.14).validate("double", DefaultTranslation))
        assertNotNull(TestDouble(101.0).validate("double", DefaultTranslation))
    }

    @Test
    fun numberContainersSerialization() {
        val original = NumberHolder(
            short = TestShort(-99),
            byte = TestByte(-9),
            uLong = TestULong(ULong.MAX_VALUE),
            uInt = TestUInt(99u),
            uShort = TestUShort(99u),
            uByte = TestUByte(99u),
            double = TestDouble(3.14159),
        )
        val deserialized = KlerkJson.decode(NumberHolder::class, KlerkJson.encode(original))
        assertEquals(original, deserialized)
        assertEquals(ULong.MAX_VALUE, deserialized.uLong.value)
    }

    @Test
    fun enumContainerSerialization() {
        val original = GenreHolder(BookGenreContainer(BookGenre.Mystery))
        val deserialized = KlerkJson.decode(GenreHolder::class, KlerkJson.encode(original))
        assertEquals(original, deserialized)
        assertEquals(BookGenre.Mystery, deserialized.genre.value)
    }

}

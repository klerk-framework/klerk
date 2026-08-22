package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.misc.createGson
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.test.*

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
    fun enumContainerSerialization() {
        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val specification = createConfig(collections)
        val gson = createGson(specification)

        val original = BookGenreContainer(BookGenre.Mystery)
        val json = gson.toJson(original, BookGenreContainer::class.java)
        val deserialized = gson.fromJson(json, BookGenreContainer::class.java)
        assertEquals(original, deserialized)
        assertEquals(BookGenre.Mystery, deserialized.enum)
    }

}

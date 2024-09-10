package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.GeoPosition
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DataContainersTest {

    @Test
    fun getValueType() {

        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val config = createConfig(collections)

        println(config)

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
}

package dev.klerkframework.klerk.attacheddata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a value is, as opposed to what somebody said it is. The cases that matter are the ones an application would
 * declare in a container: images it wants, and the things an attacker would dress up as an image.
 */
class ContentTypeDetectionTest {

    private fun png(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20)

    @Test
    fun `recognises the common image formats`() {
        assertEquals("image/png", DefaultContentTypeDetector.detect(png()))
        assertEquals(
            "image/jpeg",
            DefaultContentTypeDetector.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        )
        assertEquals("image/gif", DefaultContentTypeDetector.detect("GIF89a...".toByteArray()))
        assertEquals("image/webp", DefaultContentTypeDetector.detect("RIFF____WEBPVP8 ".toByteArray()))
    }

    @Test
    fun `recognises what an image field must be able to refuse`() {
        assertEquals(
            "image/svg+xml",
            DefaultContentTypeDetector.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray())
        )
        assertEquals("image/svg+xml", DefaultContentTypeDetector.detect("<?xml version=\"1.0\"?><svg></svg>".toByteArray()))
        assertEquals("text/html", DefaultContentTypeDetector.detect("<!DOCTYPE html><html><script>alert(1)</script>".toByteArray()))
        assertEquals("application/zip", DefaultContentTypeDetector.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14)))
        assertEquals("application/x-elf", DefaultContentTypeDetector.detect(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02)))
    }

    @Test
    fun `a claimed extension changes nothing`() {
        // the bytes are what decides; the name never reaches this function in the first place
        assertEquals("text/html", DefaultContentTypeDetector.detect("<html>pretending to be a png</html>".toByteArray()))
    }

    @Test
    fun `text without a signature is still recognised as text`() {
        assertEquals("text/plain", DefaultContentTypeDetector.detect("name,quantity\nrose,3\n".toByteArray()))
    }

    @Test
    fun `unrecognised bytes are not a verdict`() {
        assertNull(DefaultContentTypeDetector.detect(byteArrayOf(0x07, 0x03, 0x99.toByte(), 0x42)))
        assertNull(DefaultContentTypeDetector.detect(ByteArray(0)))
    }

    @Test
    fun `only the first bytes are needed`() {
        val large = png() + ByteArray(100_000) { it.toByte() }
        assertEquals("image/png", DefaultContentTypeDetector.detect(large.copyOf(SNIFF_LENGTH)))
    }
}

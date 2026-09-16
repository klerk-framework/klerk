package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.storage.spi.*
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Measures and hashes a stream as it is read, so that a blob can be described without ever being held in memory.
 *
 * Both read overloads must be implemented: [java.io.FilterInputStream.read] with a buffer delegates straight to the
 * wrapped stream, so relying on the inherited one would silently miss almost every byte.
 */
internal class HashingInputStream(
    private val source: InputStream,
    private val contentTypeDetector: ContentTypeDetector,
) : InputStream() {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0L
    private var result: AttachedDataDigest? = null

    // The first bytes, kept so that the value's type can be recognised without reading it a second time.
    private val head = ByteArrayOutputStream(ContentTypeDetector.SNIFF_LENGTH)

    override fun read(): Int {
        val b = source.read()
        if (b != -1) {
            digest.update(b.toByte())
            size++
            if (head.size() < ContentTypeDetector.SNIFF_LENGTH) {
                head.write(b)
            }
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = source.read(b, off, len)
        if (read > 0) {
            digest.update(b, off, read)
            size += read
            val wanted = minOf(read, ContentTypeDetector.SNIFF_LENGTH - head.size())
            if (wanted > 0) {
                head.write(b, off, wanted)
            }
        }
        return read
    }


    override fun available(): Int = source.available()

    override fun close() {
        source.close()
    }

    /**
     * The size, hash and detected type of everything read so far. Idempotent: [MessageDigest.digest] resets the
     * digest, so the answer is computed once and remembered — callers may well ask more than once.
     */
    fun digest(): AttachedDataDigest = result ?: AttachedDataDigest(
        size = size,
        hash = digest.digest().toHex(),
        contentType = contentTypeDetector.detect(head.toByteArray()),
    ).also { result = it }
}

/**
 * A stream that opens the underlying one on the first read, and closes nothing if it never did.
 *
 * This is what makes a [dev.klerkframework.klerk.datatypes.BlobPreAttachStep] that decides from the metadata alone
 * free: the value is fetched from wherever it lives only if the step actually asks for a byte.
 */
internal class LazyInputStream(private val open: () -> InputStream) : InputStream() {

    private var source: InputStream? = null

    private fun source(): InputStream = source ?: open().also { source = it }

    override fun read(): Int = source().read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = source().read(b, off, len)

    override fun available(): Int = source?.available() ?: 0

    override fun close() {
        source?.close()
    }
}

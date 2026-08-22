package dev.klerkframework.klerk.attacheddata

/**
 * How many bytes from the start of a value Klerk keeps for detection, and therefore the most a
 * [ContentTypeDetector] will ever be given. [DefaultContentTypeDetector]'s signatures all fit well inside this.
 */
public const val SNIFF_LENGTH: Int = 512

/**
 * Works out what a value actually is, from its first bytes. Klerk calls this for every attached value as it is
 * written and reports the result as `metadata.contentType` — the only statement about a value's type Klerk itself
 * will make. What a client *said* it was uploading is kept separately, as application metadata, and is never
 * treated as fact.
 *
 * **Detection is not a security boundary.** A file can satisfy two formats at once (a valid PNG that is also valid
 * JavaScript), so a recognised type means "this is plausibly a PNG", never "this is safe to serve as one". What makes
 * serving safe is the response headers and the origin it is served from — see the serving section of the attached-data
 * documentation.
 *
 * Configure via [dev.klerkframework.klerk.KlerkSettings.contentTypeDetector]; the default is
 * [DefaultContentTypeDetector], a small set of magic-byte signatures with no external dependencies. Swap it for
 * something like Apache Tika if an application needs to recognise more formats and can afford the extra dependency
 * weight.
 */
public fun interface ContentTypeDetector {

    /**
     * @param head up to [SNIFF_LENGTH] bytes from the start of the value.
     * @return an IANA media type, or null when the bytes match nothing known. Null is not a verdict: plenty of
     * legitimate values (CSV, an unknown binary format) have no signature at all, so a caller deciding whether to
     * reject has to say what "unrecognised" means for it.
     */
    public fun detect(head: ByteArray): String?
}

/**
 * The default [ContentTypeDetector]: a short list of magic-byte signatures for the formats an application is likely
 * to declare in a [dev.klerkframework.klerk.datatypes.AttachedBlobContainer], not an attempt at a complete format database.
 * Has no dependencies beyond the JDK.
 */
public object DefaultContentTypeDetector : ContentTypeDetector {
    override fun detect(head: ByteArray): String? = detectContentType(head)
}

private fun detectContentType(head: ByteArray): String? {
    if (head.isEmpty()) {
        return null
    }

    signatures.forEach { (signature, contentType) ->
        if (head.startsWith(signature.offset, signature.bytes)) {
            // A few formats share a prefix and need a second look further in.
            if (signature.confirm?.invoke(head) != false) {
                return contentType
            }
        }
    }

    return detectText(head)
}

private class Signature(
    val bytes: ByteArray,
    val offset: Int = 0,
    val confirm: ((ByteArray) -> Boolean)? = null,
)

private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun asciiOf(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

/**
 * Ordered, because a later entry may be a prefix of an earlier one. Deliberately short: these are the formats an
 * application is likely to declare in a [dev.klerkframework.klerk.datatypes.AttachedBlobContainer], not an attempt at a
 * complete format database.
 */
private val signatures: List<Pair<Signature, String>> = listOf(
    Signature(bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) to "image/png",
    Signature(bytesOf(0xFF, 0xD8, 0xFF)) to "image/jpeg",
    Signature(asciiOf("GIF87a")) to "image/gif",
    Signature(asciiOf("GIF89a")) to "image/gif",
    // RIFF....WEBP — the size sits between the two markers, so the second one is checked separately.
    Signature(asciiOf("RIFF"), confirm = { it.startsWith(8, asciiOf("WEBP")) }) to "image/webp",
    Signature(asciiOf("BM")) to "image/bmp",
    Signature(asciiOf("%PDF-")) to "application/pdf",
    // Office documents and jars are zips; only the container is recognised here.
    Signature(bytesOf(0x50, 0x4B, 0x03, 0x04)) to "application/zip",
    Signature(bytesOf(0x50, 0x4B, 0x05, 0x06)) to "application/zip",
    Signature(bytesOf(0x1F, 0x8B)) to "application/gzip",
    Signature(bytesOf(0x42, 0x5A, 0x68)) to "application/x-bzip2",
    Signature(bytesOf(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) to "application/x-xz",
    Signature(
        asciiOf("ftyp"),
        offset = 4,
        confirm = { it.startsWith(8, asciiOf("isom")) || it.startsWith(8, asciiOf("mp4")) },
    ) to "video/mp4",
    Signature(asciiOf("ftyp"), offset = 4) to "video/quicktime",
    Signature(bytesOf(0x1A, 0x45, 0xDF, 0xA3)) to "video/webm",
    Signature(asciiOf("OggS")) to "audio/ogg",
    Signature(asciiOf("ID3")) to "audio/mpeg",
    Signature(bytesOf(0x7F, 0x45, 0x4C, 0x46)) to "application/x-elf",
    Signature(bytesOf(0x4D, 0x5A)) to "application/x-msdownload",
)

private fun ByteArray.startsWith(offset: Int, prefix: ByteArray): Boolean {
    if (size < offset + prefix.size) {
        return false
    }
    prefix.indices.forEach { i ->
        if (this[offset + i] != prefix[i]) {
            return false
        }
    }
    return true
}

private const val NUL = '\u0000'

/** What `decodeToString` puts in place of bytes that are not valid UTF-8. Text would not contain it. */
private const val REPLACEMENT = '\uFFFD'

/**
 * Text has no signature, so it is recognised by what it is *not*: a NUL, a stray control character or an invalid
 * UTF-8 sequence means the value is binary. Two text formats are worth telling apart, because both are dangerous to
 * serve inline and an application declaring "images only" must be able to reject them.
 */
private fun detectText(head: ByteArray): String? {
    val text = head.decodeToString()
    val binary = text.any {
        it == NUL || it == REPLACEMENT || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t')
    }
    if (binary) {
        return null
    }
    val start = text.trimStart().take(200).lowercase()
    return when {
        start.startsWith("<?xml") && start.contains("<svg") -> "image/svg+xml"
        start.startsWith("<svg") -> "image/svg+xml"
        start.startsWith("<!doctype html") || start.startsWith("<html") -> "text/html"
        else -> "text/plain"
    }
}

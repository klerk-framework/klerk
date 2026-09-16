package dev.klerkframework.klerk

import kotlin.reflect.KClass
import kotlin.time.Instant

/**
 * Whether a piece of attached data is a blob or a string.
 *
 * The two are stored the same way (a string is its UTF-8 bytes) and share one id space; the kind is what decides
 * whether an id may be used as an [AttachedBlobID] or an [AttachedStringID]. Reading through the wrong one throws.
 *
 * It is reported by [AttachedDataMetadata.kind] so that a handler which is given nothing but an id — an HTTP route
 * such as `/attached/{id}/{hash}`, say — can tell what it is about to serve.
 */
public enum class AttachedDataKind {
    Blob,
    String,
}

/**
 * Who may read a piece of attached data. Declared by the [dev.klerkframework.klerk.datatypes.AttachedDataContainer]
 * the value is prepared for, and fixed for the life of the value.
 *
 * The point of [Public] is that it is a *static* property of the data. Authorization rules answer "may this actor read
 * this right now", which says nothing about the next request, so a rule-based decision can never be cached. A value
 * that is public at upload time stays public for its whole life, which is what makes it safe to hand to a CDN.
 */
public enum class AttachedDataVisibility {
    /** Only actors allowed by the `readAttachedData` rules may read the data. */
    Private,

    /** Anyone may read the data. No read rule is evaluated, not even a negative one. */
    Public,
}

/**
 * What is known about a piece of attached data apart from the value itself (see [KlerkAttachedData.getMetadata]).
 *
 * All of it is fixed when the data is uploaded and never changes.
 *
 * @property id what this describes. A URL needs it together with [hash], so it is carried here rather than having to
 * be threaded alongside.
 * @property kind whether the value is a blob or a string.
 * @property hash SHA-256 of the value, as lowercase hex. Put it in URLs: ids are recycled after the data they refer to
 * has been deleted, hashes are not, so an id alone is not a safe cache key.
 * @property size the size in bytes (for a string, the length of its UTF-8 encoding).
 * @property custom whatever was provided as metadata to [KlerkAttachedData.prepare], e.g. a content type.
 */
public data class AttachedDataMetadata(
    val id: AttachedDataID,
    val kind: AttachedDataKind,
    val visibility: AttachedDataVisibility,
    val createdAt: Instant,
    val size: Long,
    val hash: String,
    val custom: Map<String, String>,

    /**
     * What the value actually is, as recognised from its first bytes by Klerk — never what a client claimed it was
     * uploading. Null when the bytes match no known format, which is the normal state of affairs for CSV and for
     * anything Klerk does not have a signature for.
     *
     * Recognising a format is not the same as vouching for it: a file can satisfy two formats at once, so this says
     * "plausibly a PNG", never "safe to serve as one". Serve user-supplied values as a download unless you have a
     * specific reason not to.
     */
    val contentType: String? = null,

    /**
     * The names of the [dev.klerkframework.klerk.datatypes.AttachedBlobContainer.preAttachSteps] of [preparedFor]
     * that have run against this value, in the order they ran.
     */
    val completedSteps: List<String> = emptyList(),

    /**
     * The qualified name of the [dev.klerkframework.klerk.datatypes.AttachedBlobContainer] the value was prepared
     * for, or null if it was prepared without one.
     *
     * A name rather than a `KClass`, because it is read back from storage: a value prepared for a container the
     * application has since renamed or removed must still be readable, not a `ClassNotFoundException`.
     */
    val preparedFor: String? = null,
)

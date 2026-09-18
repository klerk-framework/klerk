package dev.klerkframework.klerk.storage.spi

import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.storage.Persistence
import kotlin.time.Instant

/**
 * A row in the attached-data table.
 *
 * @property owner the id of the owning model, or null while the data is still unclaimed.
 * @property metadata everything about the data except the value itself, including its
 * [dev.klerkframework.klerk.AttachedDataKind]. Immutable, written once on insert.
 * @property expires when an unclaimed row is reaped. Null once the data has been claimed by a model.
 * @property claimedByJob the job that prepared this data and has not finished with it yet, or null. A row with a job
 * claim is never reaped, even though it has no owning model — see [Persistence.deleteExpiredAttachedData].
 */
public data class AttachedDataRow<T>(
    val value: T,
    val owner: Int?,
    val metadata: AttachedDataMetadata,
    val expires: Instant?,
    val claimedByJob: JobID? = null,
)

/**
 * Everything about a value that is only known once it has been written: it is streamed rather than held in memory, so
 * none of this can be measured in advance.
 *
 * @property contentType what the bytes were recognised as, or null when they match no known format.
 */
public data class AttachedDataDigest(val size: Long, val hash: String, val contentType: String?)

/**
 * The changes to attached data that a command implies (see [dev.klerkframework.klerk.KlerkAttachedData]). Applied in
 * the same transaction as the models, so a failing command leaves the data untouched.
 *
 * Blobs and strings share one id space, so neither map needs to distinguish between them.
 *
 * @property claimed ids that got an owner, mapped to the owning model id
 * @property deleted ids that no property refers to any more
 */
public data class AttachedDataDelta(
    val claimed: Map<Int, AttachedDataClaim> = emptyMap(),
    val deleted: Set<Int> = emptySet(),
) {
    /** True if nothing changes. */
    public fun isEmpty(): Boolean = claimed.isEmpty() && deleted.isEmpty()
}

/**
 * What happens to a value when a model claims it: it gets an owner, it stops expiring, and its visibility is settled.
 *
 * @property visibility declared by the property the value was attached to. Written once, here, and never changed
 * afterwards — which is what makes [AttachedDataVisibility.Public] safe to cache.
 */
public data class AttachedDataClaim(val owner: Int, val visibility: AttachedDataVisibility)

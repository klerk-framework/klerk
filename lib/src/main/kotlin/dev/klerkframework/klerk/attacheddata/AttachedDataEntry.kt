package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.storage.spi.AttachedDataDelta
import kotlin.time.Instant

/**
 * What is known about one piece of attached data, apart from the value itself.
 *
 * Keeping this in memory is not extra overhead: an id→owner map is needed anyway, both to reject a second model
 * claiming an owned id and to let [KlerkAttachedData.get]'s authorization rule reach the owning model. Id allocation
 * probes the same map. Holding the rest here too means serving attached data needs no database round-trip for
 * anything but the value.
 *
 * @property owner the id of the owning model, or null while the data is unclaimed
 * @property metadata what [KlerkAttachedData.getMetadata] reports. Null only between the moment an id is reserved and
 * the moment the value has been written — a window in which the data is unclaimed, and therefore unreadable anyway.
 * @property expires when an unclaimed value is reaped. Null once claimed by a model.
 * @property claimedByJob the job that prepared this value and has not finished with it, or null. This is the second,
 * independent claim: the reaper deletes only when there is neither a model reference nor a job claim, so a
 * long-running job's working set is safe for as long as the job lives — including while it is dead-lettered and
 * awaiting a human.
 * @property preparedBy the actor that prepared the value, the only one besides the system that may claim it. Null for
 * a placeholder, and for a row whose preparer storage does not know.
 */
internal data class AttachedDataEntry(
    val owner: Int?,
    val metadata: AttachedDataMetadata?,
    val expires: Instant?,
    val claimedByJob: JobID? = null,
    val preparedBy: ActorIdentity? = null,
)

/**
 * A value a job has claimed never expires while the claim lasts, even though no model owns it yet. The two claims are
 * independent: the reaper takes a value only when neither holds.
 */
internal fun AttachedDataEntry.isExpired(now: Instant): Boolean =
    claimedByJob == null && expires?.let { it < now } ?: false

internal sealed class AttachedDataPlan {
    internal data class Ok(val delta: AttachedDataDelta) : AttachedDataPlan()
    internal data class Rejected(val problems: List<Problem>) : AttachedDataPlan()
}

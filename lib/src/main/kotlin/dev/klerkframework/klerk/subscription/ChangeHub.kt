package dev.klerkframework.klerk.subscription

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.storage.EventLogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass
import kotlin.time.Instant

/** One model in a commit: [before] is null if it was created, [after] if it was deleted. */
internal class ModelDelta(
    val id: ModelID<out Any>,
    val kClass: KClass<out Any>,
    val before: Model<out Any>?,
    val after: Model<out Any>?,
)

internal class CommitRecord(
    val sequenceNumber: Long,
    val models: List<ModelDelta>,
    eventLogEntry: EventLogEntry?,
    val eventModelClass: KClass<*>?,
) {
    /** Follows what the event log erases, so that a replay never hands out more than the log still has. */
    @Volatile
    var eventLogEntry: EventLogEntry? = eventLogEntry
}

/**
 * The latest commits, kept so that a subscriber can pick up where a read or an earlier subscription left off.
 *
 * Commits are only appended, so a command never waits for a subscriber. Each subscriber pulls what it has not seen yet,
 * and is told that it missed changes once they have been evicted.
 */
internal class ChangeHub(private val capacity: Int) {

    private val records = ArrayDeque<CommitRecord>()

    /** The sequence number of the latest evicted commit. Guarded by `this`. */
    private var evictedUpTo = 0L

    private val latest = MutableStateFlow(0L)

    val latestSequenceNumber: Long get() = latest.value

    /** Nothing from before [sequenceNumber] can be replayed, so an `after` from before a restart gives missed changes. */
    fun started(sequenceNumber: Long) {
        synchronized(this) {
            records.clear()
            evictedUpTo = sequenceNumber
        }
        latest.value = sequenceNumber
    }

    /** Called under the command mutex, so commits arrive in order. */
    fun publish(record: CommitRecord) {
        synchronized(this) {
            records.addLast(record)
            if (records.size > capacity) {
                evictedUpTo = records.removeFirst().sequenceNumber
            }
        }
        latest.value = record.sequenceNumber
    }

    fun eraseEventLogs(modelIds: Set<Int>) {
        forEachRecord { record ->
            if (record.eventLogEntry?.model?.value in modelIds) {
                record.eventLogEntry = null
            }
        }
    }

    fun eraseParamsAndExtra(cutoff: (EventReference) -> Instant?) {
        forEachRecord { record ->
            val entry = record.eventLogEntry ?: return@forEachRecord
            val before = cutoff(entry.eventReference) ?: return@forEachRecord
            if (entry.time <= before && (entry.params != null || entry.extra != null)) {
                record.eventLogEntry = entry.copy(params = null, extra = null)
            }
        }
    }

    private fun forEachRecord(action: (CommitRecord) -> Unit) {
        synchronized(this) { records.toList() }.forEach(action)
    }

    /**
     * The commits after [sequenceNumber], or null if some of them have been evicted, or if [sequenceNumber] is ahead of
     * the latest commit. A read may see one commit that has not been published yet, but not more.
     */
    private fun recordsAfter(sequenceNumber: Long): List<CommitRecord>? = synchronized(this) {
        if (sequenceNumber < evictedUpTo || sequenceNumber > latest.value + 1) return null
        var first = records.size
        while (first > 0 && records[first - 1].sequenceNumber > sequenceNumber) {
            first--
        }
        records.subList(first, records.size).toList()
    }

    /**
     * What [select] makes of each commit after [after], or after the latest commit if [after] is null. Commits that
     * cannot be replayed are reported with [missed].
     */
    fun <E : Any> subscribe(after: Long?, missed: (Long) -> E, select: suspend (CommitRecord) -> E?): Flow<E> = flow {
        var cursor = after ?: latest.value
        latest.collect {
            val batch = recordsAfter(cursor)
            if (batch == null) {
                cursor = latest.value
                emit(missed(cursor))
            } else {
                for (record in batch) {
                    cursor = record.sequenceNumber
                    select(record)?.let { emit(it) }
                }
            }
        }
    }
}

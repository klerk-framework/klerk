package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataKind
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.logger
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.storage.spi.AttachedDataDelta
import dev.klerkframework.klerk.storage.spi.AttachedDataDigest
import dev.klerkframework.klerk.storage.spi.AttachedDataRow
import dev.klerkframework.klerk.storage.spi.JobCommit
import dev.klerkframework.klerk.storage.spi.JobRecord
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * Keeps all data in memory. Should only be used for testing.
 *
 * Open so that a test can subclass it and override any [Persistence] member — counting the reads that reach storage,
 * or throwing at a chosen commit to simulate a crash. An override is expected to call `super`, since the state every
 * other member reads is private to this class.
 */
public open class RamStorage : Persistence {
    private val eventLog = mutableSetOf<EventLogEntry>()

    // Concurrent because readModel is called by readers that do not hold the write lock, while a commit writes here
    // (writes are serialized against each other, but not against readers, since a commit persists before it takes the
    // write lock).
    private val models = ConcurrentHashMap<Int, Model<Any>>()
    override val currentModelSchemaVersion: Int = 1

    // A null value means the bytes are in an external blob store rather than here.
    private val attachedRows = mutableMapOf<Int, AttachedDataRow<ByteArray?>>()
    private val jobs = mutableMapOf<JobID, JobRecord>()
    private val cronState = mutableMapOf<String, Instant>()
    private val tombstones = mutableMapOf<Int, Instant>()

    /**
     * Everything happens under one lock, which is what stands in for a transaction here. It is not a real one — a
     * crash mid-write leaves memory inconsistent — but RamStorage is wiped on every restart anyway, so there is no
     * state for a partial write to corrupt.
     */
    private val lock = Any()

    override fun store(batch: CommitBatch) {
        synchronized(lock) {
            writeAll(batch)
        }
    }

    override fun commitJobStep(batch: CommitBatch) {
        synchronized(lock) {
            writeAll(batch)
        }
    }

    private fun writeAll(batch: CommitBatch) {
        applyAttachedDataDelta(batch.attachedData)
        applyJobCommit(batch.jobs)
        batch.eventLogEntry?.let { eventLog.add(it) }
        for (model in batch.createdModels.plus(batch.updatedModels)) {
            @Suppress("UNCHECKED_CAST")
            models[model.id.value] = model as Model<Any>
        }
        for (model in batch.deletedModels) {
            models.remove(model.value)
        }
        for ((modelId, deletedAt) in batch.eventLogTombstones) {
            tombstones[modelId.value] = deletedAt
        }
    }

    private fun applyJobCommit(commit: JobCommit) {
        for (record in commit.upserted) {
            jobs[record.id] = record
        }
        for (id in commit.deleted) {
            jobs.remove(id)
        }
        for ((dataId, jobId) in commit.attachedDataClaimed) {
            attachedRows[dataId]?.let { attachedRows[dataId] = it.copy(claimedByJob = jobId) }
        }
        for (dataId in commit.attachedDataReleased) {
            attachedRows[dataId]?.let { attachedRows[dataId] = it.copy(claimedByJob = null) }
        }
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit) {
        for (model in models.values) {
            lambda(model)
        }
    }

    override fun readModel(id: Int): Model<out Any>? = models[id]

    override fun readEventLog(
        modelId: Int?,
        after: Instant,
        before: Instant,
        upToSequenceNumber: Long,
    ): Iterable<EventLogEntry> = synchronized(lock) {
        return eventLog
            .filter { modelId == null || modelId == it.model.value }
            .filter { it.time >= after && it.time <= before }
            .filter { it.sequenceNumber <= upToSequenceNumber }
            .sortedBy { it.sequenceNumber }
    }

    override fun readEventLogEntry(sequenceNumber: Long): EventLogEntry? = synchronized(lock) {
        return eventLog.firstOrNull { it.sequenceNumber == sequenceNumber }
    }

    override fun lastEventLogSequenceNumber(): Long = synchronized(lock) {
        eventLog.maxOfOrNull { it.sequenceNumber } ?: 0L
    }

    override fun eraseEventLogsOfDeletedModels(deletedAtOrBefore: Instant) {
        synchronized(lock) {
            val due = tombstones.filterValues { it <= deletedAtOrBefore }.keys
            eventLog.removeIf { it.model.value in due }
            tombstones.keys.removeAll(due)
        }
    }

    override fun eraseEventLogParamsAndExtra(before: Instant) {
        synchronized(lock) {
            val expired = eventLog.filter { it.time < before && (it.params != null || it.extra != null) }
            eventLog.removeAll(expired.toSet())
            eventLog.addAll(expired.map { it.copy(params = null, extra = null) })
        }
    }

    override fun setSpecification(specification: Specification<*, *>) {}

    override fun migrate(migrations: List<MigrationStep>) {
        logger.debug { "Skipping migration since RamStorage is always empty on startup" }
    }

    private fun applyAttachedDataDelta(attachedData: AttachedDataDelta) {
        for ((id, claim) in attachedData.claimed) {
            val row = requireNotNull(attachedRows[id]) { "Could not find attached data with id $id" }
            attachedRows[id] = row.copy(
                owner = claim.owner,
                expires = null,
                metadata = row.metadata.copy(visibility = claim.visibility),
            )
        }
        for (id in attachedData.deleted) {
            attachedRows.remove(id)
        }
    }

    override fun insertAttachedData(
        id: Int,
        value: InputStream?,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        createdAt: Instant,
        custom: Map<String, String>,
        preparedFor: String?,
        expires: Instant,
        claimedByJob: JobID?,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        require(!attachedRows.containsKey(id)) { "There is already attached data with id $id" }
        val bytes = value?.readAllBytes()
        val digest = digestAfterWrite()
        attachedRows[id] = AttachedDataRow(
            value = bytes,
            owner = null,
            metadata = AttachedDataMetadata(
                AttachedDataID(id), kind, visibility, createdAt, digest.size, digest.hash, custom, digest.contentType,
                preparedFor = preparedFor,
            ),
            expires = expires,
            claimedByJob = claimedByJob,
        )
    }

    override fun updateAttachedData(
        id: Int,
        value: InputStream?,
        completedSteps: List<String>,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        val row = requireNotNull(attachedRows[id]) { "There is no attached data with id $id" }
        val bytes = value?.readAllBytes()
        val digest = digestAfterWrite()
        attachedRows[id] = row.copy(
            value = bytes ?: row.value,
            metadata = row.metadata.copy(
                size = digest.size,
                hash = digest.hash,
                contentType = digest.contentType,
                completedSteps = completedSteps,
            ),
        )
    }

    override fun getAttachedData(id: Int): AttachedDataRow<Unit>? = attachedRows[id]?.let {
        AttachedDataRow(Unit, it.owner, it.metadata, it.expires, it.claimedByJob)
    }

    override fun getAttachedValue(id: Int): InputStream? = attachedRows[id]?.value?.inputStream()

    override fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>> = attachedRows.mapValues {
        AttachedDataRow(Unit, it.value.owner, it.value.metadata, it.value.expires, it.value.claimedByJob)
    }

    override fun deleteExpiredAttachedData(now: Instant): Set<Int> {
        val expired = attachedRows.filterValues { row ->
            row.claimedByJob == null && row.expires?.let { it < now } ?: false
        }.keys.toSet()
        for (id in expired) {
            attachedRows.remove(id)
        }
        return expired
    }

    override fun deleteAttachedData(ids: Set<Int>) {
        for (id in ids) {
            attachedRows.remove(id)
        }
    }

    override fun allJobs(): List<JobRecord> = synchronized(lock) { jobs.values.toList() }

    override fun cronState(): Map<String, Instant> = synchronized(lock) { cronState.toMap() }

    override fun setCronFired(scheduleId: String, firedAt: Instant) {
        synchronized(lock) {
            cronState[scheduleId] = firedAt
        }
    }

    override fun toString(): String = "RamStorage(models=${models.size}, jobs=${jobs.size})"
}

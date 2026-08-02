package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.JobMetadata
import dev.klerkframework.klerk.migration.MigrationStep
import java.io.InputStream
import kotlin.time.Instant

/** One persisted entry in the audit log: the record of a single committed command against a single model. */
public data class AuditEntry(
    val time: Instant,
    val eventReference: EventReference,
    val reference: Int,
    val actorType: Byte,
    val actorReference: Int?,
    val actorExternalId: Long?,
    val params: String,
    val extra: String?
)

/**
 * A row in the attached-data table.
 *
 * @property owner the id of the owning model, or null while the data is still unclaimed.
 * @property metadata everything about the data except the value itself, including its
 * [dev.klerkframework.klerk.AttachedDataKind]. Immutable, written once on insert.
 * @property expires when an unclaimed row is reaped. Null once the data has been claimed.
 */
public data class AttachedDataRow<T>(
    val value: T,
    val owner: Int?,
    val metadata: AttachedDataMetadata,
    val expires: Instant?,
)

/**
 * The changes to attached data that a command implies (see [dev.klerkframework.klerk.KlerkAttachedData]). Applied in the
 * same transaction as the models, so a failing command leaves the data untouched.
 *
 * Blobs and strings share one id space, so neither map needs to distinguish between them.
 *
 * @property claimed ids that got an owner, mapped to the owning model id
 * @property deleted ids that no property refers to any more
 */
public data class AttachedDataDelta(
    val claimed: Map<Int, Int> = emptyMap(),
    val deleted: Set<Int> = emptySet(),
) {
    public fun isEmpty(): Boolean = claimed.isEmpty() && deleted.isEmpty()
}

/**
 * Storage backend SPI: implement this to durably store models, the audit log, jobs and attached data. Klerk owns the
 * schema; implementations only need to persist and retrieve the shapes below. Provided implementations are
 * [dev.klerkframework.klerk.storage.SqlPersistence] and [RamStorage]. Wire an instance in via
 * `ConfigBuilder.persistence(...)`.
 */
public interface Persistence {
    /** The schema version currently stored (see [dev.klerkframework.klerk.migration.MigrationStep]). */
    public val currentModelSchemaVersion: Int

    public fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta = AttachedDataDelta()
    ): Unit

    public fun readAllModels(lambda: (Model<out Any>) -> Unit): Unit
    public fun readAuditLog(
        modelId: Int? = null,
        from: Instant = Instant.DISTANT_PAST,
        until: Instant = Instant.DISTANT_FUTURE
    ): Iterable<AuditEntry>

    public fun modifyEventsInAuditLog(modelId: Int, transformer: (AuditEntry) -> AuditEntry?): Unit
    public fun setConfig(config: Config<*, *>): Unit
    public fun migrate(migrations: List<MigrationStep>): Unit

    /**
     * Inserts an unclaimed value. Must fail if the id is already taken (i.e. insert, never upsert).
     *
     * Blobs and strings are stored identically — a string arrives as a stream over its UTF-8 bytes — so the only
     * thing distinguishing them here is [kind], which must be reported back by [getAttachedData] and
     * [readAllAttachedDataMetadata].
     *
     * The size and hash are not known before the value has been written, since it is streamed rather than held in
     * memory. [digestAfterWrite] provides them, and must therefore be called *after* [value] has been fully consumed
     * and before the insert is committed, so that a row is never visible without them.
     *
     * @param digestAfterWrite returns the size in bytes and the SHA-256 (lowercase hex) of what was written.
     */
    public fun insertAttachedData(
        id: Int,
        value: InputStream,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        createdAt: Instant,
        custom: Map<String, String>,
        expires: Instant,
        digestAfterWrite: () -> Pair<Long, String>,
    ): Unit

    public fun getAttachedData(id: Int): AttachedDataRow<InputStream>?

    /**
     * Reads every attached-data row without its value, so that the in-memory structures can be rebuilt at startup.
     */
    public fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>>

    /**
     * Deletes unclaimed rows whose expiry has passed.
     */
    public fun deleteExpiredAttachedData(now: Instant): Unit

    public fun insertJob(meta: JobMetadata)
    public fun updateJob(updated: JobMetadata)
    public fun getAllJobs(): Set<JobMetadata>
}

/**
 * Keeps all data in memory. Should only be used for testing.
 */
public class RamStorage : Persistence {
    private val auditLog = mutableSetOf<AuditEntry>()
    private val models = mutableMapOf<Int, Model<Any>>()
    override val currentModelSchemaVersion: Int = 1
    private val attachedRows = mutableMapOf<Int, AttachedDataRow<ByteArray>>()
    private val jobs = mutableMapOf<JobId, JobMetadata>()

    override fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta
    ) {
        applyAttachedDataDelta(attachedData)
        if (command != null && context != null) {
            auditLog.add(createAuditEntry(command, delta, context))
        }
        delta.createdModels
            .union(delta.aggregatedModelState.keys)
            .union(delta.transitions).forEach { modelId ->
                val model =
                    requireNotNull(delta.aggregatedModelState[modelId]) { "Could not find $modelId in modifiedModels" }
                models[model.id.value] = model as Model<Any>
            }

        delta.deletedModels.forEach { models.remove(it.value) }
        delta.newJobs.forEach {
            // TODO
        }
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit): Unit {
        return models.values.forEach { lambda(it) }
    }

    override fun readAuditLog(modelId: Int?, from: Instant, until: Instant): Iterable<AuditEntry> {
        return auditLog
            .filter {
                return@filter if (modelId == null) {
                    true
                } else {
                    modelId == it.reference
                }
            }
            .filter { it.time > from || it.time == from }
            .filter { it.time < until || it.time == from }
            .sortedBy { it.time }
    }

    override fun modifyEventsInAuditLog(modelId: Int, transformer: (AuditEntry) -> AuditEntry?) {
        readAuditLog(modelId).toList().forEach {
            auditLog.remove(it)
            val new = transformer(it)
            if (new != null) {
                auditLog.add(new)
            }
        }
    }

    override fun setConfig(config: Config<*, *>) {
        // not used
    }

    override fun migrate(migrations: List<MigrationStep>) {
        logger.debug { "Skipping migration since RamStorage is always empty on startup" }
    }

    private fun applyAttachedDataDelta(attachedData: AttachedDataDelta) {
        attachedData.claimed.forEach { (id, owner) ->
            val row = requireNotNull(attachedRows[id]) { "Could not find attached data with id $id" }
            attachedRows[id] = row.copy(owner = owner, expires = null)
        }
        attachedData.deleted.forEach { attachedRows.remove(it) }
    }

    override fun insertAttachedData(
        id: Int,
        value: InputStream,
        kind: AttachedDataKind,
        visibility: AttachedDataVisibility,
        createdAt: Instant,
        custom: Map<String, String>,
        expires: Instant,
        digestAfterWrite: () -> Pair<Long, String>,
    ) {
        require(!attachedRows.containsKey(id)) { "There is already attached data with id $id" }
        val bytes = value.readAllBytes()
        val (size, hash) = digestAfterWrite()
        attachedRows[id] = AttachedDataRow(
            value = bytes,
            owner = null,
            metadata = AttachedDataMetadata(kind, visibility, createdAt, size, hash, custom),
            expires = expires,
        )
    }

    override fun getAttachedData(id: Int): AttachedDataRow<InputStream>? =
        attachedRows[id]?.let { AttachedDataRow(it.value.inputStream(), it.owner, it.metadata, it.expires) }

    override fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>> =
        attachedRows.mapValues { AttachedDataRow(Unit, it.value.owner, it.value.metadata, it.value.expires) }

    override fun deleteExpiredAttachedData(now: Instant) {
        attachedRows.entries.removeIf { it.value.expires?.let { expires -> expires < now } ?: false }
    }

    override fun insertJob(meta: JobMetadata) {
        jobs[meta.id] = meta
    }

    override fun updateJob(updated: JobMetadata) {
        jobs[updated.id] = updated
    }

    override fun getAllJobs(): Set<JobMetadata> = jobs.values.toSet()

    public fun <T : Any, P, C : KlerkContext, V> createAuditEntry(
        command: Command<T, P>,
        result: ProcessingData<out T, C, V>,
        context: C
    ): AuditEntry {
        val reference = command.model?.value
            ?: result.createdModels.single { true }.value
        return AuditEntry(
            context.time,
            command.event.id,
            reference,
            context.actor.type.toByte(),
            context.actor.id?.value,
            context.actor.externalId,
            "some JSON", // TODO
            extra = context.auditExtra
        )
    }

}

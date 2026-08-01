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
 * A row in one of the large-data tables.
 *
 * @property owner the id of the owning model, or null while the data is still unclaimed.
 * @property expires when an unclaimed row is reaped. Null once the data has been claimed.
 */
public data class LargeDataRow<T>(
    val value: T,
    val owner: Int?,
    val authKey: String?,
    val expires: Instant?,
)

/**
 * The changes to large data that a command implies (see [dev.klerkframework.klerk.KlerkLargeData]). Applied in the
 * same transaction as the models, so a failing command leaves the data untouched.
 *
 * @property claimedBlobs blob ids that got an owner, mapped to the owning model id
 * @property claimedStrings string ids that got an owner, mapped to the owning model id
 * @property deletedBlobs blob ids that no property refers to any more
 * @property deletedStrings string ids that no property refers to any more
 */
public data class LargeDataDelta(
    val claimedBlobs: Map<Int, Int> = emptyMap(),
    val claimedStrings: Map<Int, Int> = emptyMap(),
    val deletedBlobs: Set<Int> = emptySet(),
    val deletedStrings: Set<Int> = emptySet(),
) {
    public fun isEmpty(): Boolean =
        claimedBlobs.isEmpty() && claimedStrings.isEmpty() && deletedBlobs.isEmpty() && deletedStrings.isEmpty()
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
        largeData: LargeDataDelta = LargeDataDelta()
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
     * Inserts an unclaimed blob. Must fail if the id is already taken (i.e. insert, never upsert).
     */
    public fun insertLargeBlob(id: Int, value: InputStream, authKey: String?, expires: Instant): Unit

    /**
     * Inserts an unclaimed string. Must fail if the id is already taken (i.e. insert, never upsert).
     */
    public fun insertLargeString(id: Int, value: String, authKey: String?, expires: Instant): Unit

    public fun getLargeBlob(id: Int): LargeDataRow<InputStream>?
    public fun getLargeString(id: Int): LargeDataRow<String>?

    /**
     * Reads every large-data row without its value, so that the in-memory structures can be rebuilt at startup.
     * @return blob rows and string rows
     */
    public fun readAllLargeDataMetadata(): Pair<Map<Int, LargeDataRow<Unit>>, Map<Int, LargeDataRow<Unit>>>

    /**
     * Deletes unclaimed rows whose expiry has passed.
     */
    public fun deleteExpiredLargeData(now: Instant): Unit

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
    private val largeBlobs = mutableMapOf<Int, LargeDataRow<ByteArray>>()
    private val largeStrings = mutableMapOf<Int, LargeDataRow<String>>()
    private val jobs = mutableMapOf<JobId, JobMetadata>()

    override fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        largeData: LargeDataDelta
    ) {
        applyLargeDataDelta(largeData)
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

    private fun applyLargeDataDelta(largeData: LargeDataDelta) {
        largeData.claimedBlobs.forEach { (id, owner) ->
            val row = requireNotNull(largeBlobs[id]) { "Could not find blob with id $id" }
            largeBlobs[id] = row.copy(owner = owner, expires = null)
        }
        largeData.claimedStrings.forEach { (id, owner) ->
            val row = requireNotNull(largeStrings[id]) { "Could not find string with id $id" }
            largeStrings[id] = row.copy(owner = owner, expires = null)
        }
        largeData.deletedBlobs.forEach { largeBlobs.remove(it) }
        largeData.deletedStrings.forEach { largeStrings.remove(it) }
    }

    override fun insertLargeBlob(id: Int, value: InputStream, authKey: String?, expires: Instant) {
        require(!largeBlobs.containsKey(id)) { "There is already a blob with id $id" }
        largeBlobs[id] = LargeDataRow(value.readAllBytes(), owner = null, authKey = authKey, expires = expires)
    }

    override fun insertLargeString(id: Int, value: String, authKey: String?, expires: Instant) {
        require(!largeStrings.containsKey(id)) { "There is already a string with id $id" }
        largeStrings[id] = LargeDataRow(value, owner = null, authKey = authKey, expires = expires)
    }

    override fun getLargeBlob(id: Int): LargeDataRow<InputStream>? =
        largeBlobs[id]?.let { LargeDataRow(it.value.inputStream(), it.owner, it.authKey, it.expires) }

    override fun getLargeString(id: Int): LargeDataRow<String>? = largeStrings[id]

    override fun readAllLargeDataMetadata(): Pair<Map<Int, LargeDataRow<Unit>>, Map<Int, LargeDataRow<Unit>>> =
        largeBlobs.mapValues { LargeDataRow(Unit, it.value.owner, it.value.authKey, it.value.expires) } to
                largeStrings.mapValues { LargeDataRow(Unit, it.value.owner, it.value.authKey, it.value.expires) }

    override fun deleteExpiredLargeData(now: Instant) {
        largeBlobs.entries.removeIf { it.value.expires?.let { expires -> expires < now } ?: false }
        largeStrings.entries.removeIf { it.value.expires?.let { expires -> expires < now } ?: false }
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

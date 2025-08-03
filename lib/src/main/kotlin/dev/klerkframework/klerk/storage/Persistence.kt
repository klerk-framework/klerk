package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.migration.MigrationStep
import kotlinx.datetime.Instant
import java.io.InputStream
import kotlin.collections.set

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

public interface Persistence {
    public val currentModelSchemaVersion: Int

    public fun <T : Any, P, C:KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?
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

    public fun putKeyValue(id: UInt, value: String, ttl: Instant?): Unit
    public fun putKeyValue(id: UInt, value: Int, ttl: Instant?): Unit
    public fun putKeyValue(id: UInt, value: InputStream, ttl: Instant?): Unit
    public fun updateBlob(id: UInt, ttl: Instant?, active: Boolean): Unit
    public fun getKeyValueString(id: UInt): Pair<String, Instant?>?
    public fun getKeyValueInt(id: UInt): Pair<Int, Instant?>?
    public fun getKeyValueBlob(id: UInt): Triple<InputStream, Instant?, Boolean>?
}

/**
 * Keeps all data in memory. Should only be used for testing.
 */
public class RamStorage : Persistence {
    private val auditLog = mutableSetOf<AuditEntry>()
    private val models = mutableMapOf<Int, Model<Any>>()
    override val currentModelSchemaVersion: Int = 1
    private val keyValueStrings = mutableMapOf<UInt, Pair<String, Instant?>>()
    private val keyValueInts = mutableMapOf<UInt, Pair<Int, Instant?>>()
    private val keyValueBlobs = mutableMapOf<UInt, Triple<ByteArray, Instant?, Boolean>>()

    override fun <T : Any, P, C:KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?
    ) {
        if (command != null && context != null) {
            auditLog.add(createAuditEntry(command, delta, context))
        }
        delta.createdModels
            .union(delta.aggregatedModelState.keys)
            .union(delta.transitions).forEach { modelId ->
            val model = requireNotNull(delta.aggregatedModelState[modelId]) { "Could not find $modelId in modifiedModels" }
            models[model.id.toInt()] = model as Model<Any>
        }

        delta.deletedModels.forEach { models.remove(it.toInt()) }
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
                    modelId.toInt() == it.reference
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

    override fun putKeyValue(id: UInt, value: String, ttl: Instant?) {
        keyValueStrings[id] = Pair(value, ttl)
    }

    override fun putKeyValue(id: UInt, value: Int, ttl: Instant?) {
        keyValueInts[id] = Pair(value, ttl)
    }

    override fun putKeyValue(id: UInt, value: InputStream, ttl: Instant?) {
        keyValueBlobs[id] = Triple(value.readAllBytes(), ttl, false)
    }

    override fun updateBlob(id: UInt, ttl: Instant?, active: Boolean) {
        val old = keyValueBlobs[id]
        if (old == null) {
            logger.warn { "Could not find blob with id $id" }
            return
        }
        keyValueBlobs[id] = Triple(old.first, ttl, active)
    }

    override fun getKeyValueString(id: UInt): Pair<String, Instant?>? = keyValueStrings[id]
    override fun getKeyValueInt(id: UInt): Pair<Int, Instant?>? = keyValueInts[id]
    override fun getKeyValueBlob(id: UInt): Triple<InputStream, Instant?, Boolean>? =
        keyValueBlobs[id]?.let { Triple(it.first.inputStream(), it.second, it.third) }

    public fun <T : Any, P, C:KlerkContext, V> createAuditEntry(
        command: Command<T, P>,
        result: ProcessingData<out T, C, V>,
        context: C
    ): AuditEntry {
        val reference = command.model?.toInt()
            ?: result.createdModels.single { true }.toInt()
        return AuditEntry(
            context.time,
            command.event.id,
            reference,
            context.actor.type.toByte(),
            context.actor.id?.toInt(),
            context.actor.externalId,
            "some JSON", // TODO
            extra = context.auditExtra
        )
    }

}

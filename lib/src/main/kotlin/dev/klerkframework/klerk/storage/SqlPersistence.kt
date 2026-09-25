package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.ActorType
import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataKind
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.PersistedModelMismatchException
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.decode64bitMicroseconds
import dev.klerkframework.klerk.job.JobAgent
import dev.klerkframework.klerk.job.JobHookKind
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobLogEntry
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobPriority
import dev.klerkframework.klerk.job.JobStatus
import dev.klerkframework.klerk.log
import dev.klerkframework.klerk.migration.MigrationModelV1
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.migration.ModelMigrationStep
import dev.klerkframework.klerk.misc.JsonMismatchException
import dev.klerkframework.klerk.misc.KlerkJson
import dev.klerkframework.klerk.storage.EventLogTable.actorIdentityExternalId
import dev.klerkframework.klerk.storage.EventLogTable.actorIdentityReference
import dev.klerkframework.klerk.storage.EventLogTable.actorIdentityType
import dev.klerkframework.klerk.storage.EventLogTable.event
import dev.klerkframework.klerk.storage.EventLogTable.timestamp
import dev.klerkframework.klerk.storage.ModelSchemaMigrationsTable.toVersion
import dev.klerkframework.klerk.storage.spi.AttachedDataDelta
import dev.klerkframework.klerk.storage.spi.AttachedDataDigest
import dev.klerkframework.klerk.storage.spi.AttachedDataRow
import dev.klerkframework.klerk.storage.spi.JobCommit
import dev.klerkframework.klerk.storage.spi.JobRecord
import dev.klerkframework.klerk.storage.spi.StoredActor
import dev.klerkframework.klerk.to64bitMicroseconds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.InputStream
import javax.sql.DataSource
import kotlin.reflect.KClass
import kotlin.system.measureTimeMillis
import kotlin.time.Clock
import kotlin.time.Instant

/** Stands in for the value of a row whose bytes live in an [AttachedBlobStore.External]. */
private val EMPTY_BLOB = ExposedBlob(ByteArray(0))

/** Keeps the `IN` lists of an erasure within what every database accepts. */
private const val ERASE_CHUNK_SIZE = 500

/** The job log and the child outcomes are stored as JSON, since neither is ever queried by SQL. */
private val jobJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
private val logSerializer = ListSerializer(JobLogEntry.serializer())
private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
private val stringListSerializer = ListSerializer(String.serializer())

/**
 * [Persistence] backend for a SQL database, via a [DataSource] and [Exposed](https://github.com/JetBrains/Exposed).
 * On construction, connects and creates its tables if missing (event log, models, schema-migration tracking,
 * attached data, jobs, cron state, event-log tombstones), then reads [currentModelSchemaVersion] from the `klerk_model_schema_migrations` table.
 * Model `props` and command `params` are stored as JSON.
 */
public class SqlPersistence(private val dataSource: DataSource) : Persistence {

    private val database: Database
    override var currentModelSchemaVersion: Int = 0
    private val logger = KotlinLogging.logger {}
    private lateinit var specification: Specification<*, *>

    /** The model classes by the simple name stored in the `type` column. Kept ready rather than built per read. */
    @Volatile
    private var modelClasses: Map<String, KClass<out Any>> = emptyMap()

    init {
        logger.info { "Connecting to database: $dataSource" }
        database = Database.connect(dataSource)

        transaction(database) {
            try {
                SchemaUtils.create(EventLogTable)
                SchemaUtils.create(ModelsTable)
                SchemaUtils.create(ModelSchemaMigrationsTable)
                SchemaUtils.create(AttachedDataTable)
                SchemaUtils.create(JobsTable)
                SchemaUtils.create(CronStateTable)
                SchemaUtils.create(EventLogTombstonesTable)
                SchemaUtils.create(CommandTokensTable)
                currentModelSchemaVersion = readCurrentModelSchemaVersion()
                logger.info { "Database ready (version: $currentModelSchemaVersion)" }
            } catch (e: Exception) {
                logger.error(e) { "Database not ready" }
            }
        }
    }

    private fun readCurrentModelSchemaVersion(): Int {
        val maxRow = ModelSchemaMigrationsTable.selectAll().maxByOrNull { it[toVersion] }
        if (maxRow == null) {
            return 1
        }
        return maxRow[toVersion]
    }

    override fun store(batch: CommitBatch) {
        transaction(database) {
            writeAll(batch)
        }
    }

    override fun commitJobStep(batch: CommitBatch) {
        // One Exposed transaction, so the whole contract on Persistence.commitJobStep holds: models, event log entry,
        // attached data and every job row either land together or not at all.
        transaction(database) {
            writeAll(batch)
        }
    }

    /**
     * The body shared by [store] and [commitJobStep]. Must be called inside a transaction.
     */
    private fun writeAll(batch: CommitBatch) {
        applyAttachedDataDelta(batch.attachedData)
        applyJobCommit(batch.jobs)

        batch.eventLogEntry?.let { entry ->
            EventLogTable.insert {
                it[sequenceNumber] = entry.sequenceNumber
                it[timestamp] = entry.time.to64bitMicroseconds()
                it[event] = entry.eventReference.toString()
                it[modelId] = entry.model.value
                it[params] = entry.params
                it[actorIdentityType] = entry.actorType.storedValue.toByte()
                it[actorIdentityReference] = entry.actorReference
                it[actorIdentityExternalId] = entry.actorExternalId
                it[extra] = entry.extra
            }
        }

        for (model in batch.createdModels) {
            ModelsTable.insert {
                it[id] = model.id.value
                it[type] = model.props::class.simpleName!!
                it[createdAt] = model.createdAt.to64bitMicroseconds()
                it[lastPropsUpdatedAt] = model.lastPropsUpdatedAt.to64bitMicroseconds()
                it[lastStateTransitionAt] = model.lastStateTransitionAt.to64bitMicroseconds()
                it[state] = model.state
                it[timeTrigger] = model.timeTrigger?.to64bitMicroseconds()
                it[properties] = KlerkJson.encode(model.props)
            }
        }

        for (model in batch.updatedModels) {
            ModelsTable.update({ ModelsTable.id eq model.id.value }) {
                it[lastPropsUpdatedAt] = model.lastPropsUpdatedAt.to64bitMicroseconds()
                it[lastStateTransitionAt] = model.lastStateTransitionAt.to64bitMicroseconds()
                it[state] = model.state
                it[timeTrigger] = model.timeTrigger?.to64bitMicroseconds()
                it[properties] = KlerkJson.encode(model.props)
            }
        }

        for (modelId in batch.deletedModels) {
            ModelsTable.deleteWhere { id eq modelId.value }
        }

        for ((modelId, deletedAt) in batch.eventLogTombstones) {
            EventLogTombstonesTable.insert {
                it[this.modelId] = modelId.value
                it[this.deletedAt] = deletedAt.to64bitMicroseconds()
            }
        }

        batch.commandToken?.let { token ->
            CommandTokensTable.insert {
                it[nonce] = token.nonce
                it[createdAt] = token.createdAt.to64bitMicroseconds()
            }
        }
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit) {
        transaction(database) {
            for (row in ModelsTable.selectAll()) {
                lambda(toModel(row))
            }
        }
    }

    override fun readModel(id: Int): Model<out Any>? = transaction(database) {
        ModelsTable.selectAll().where { ModelsTable.id eq id }.singleOrNull()?.let { toModel(it) }
    }

    private fun toModel(row: ResultRow): Model<out Any> {
        val modelId = row[ModelsTable.id]
        try {
            val type = row[ModelsTable.type]
            val kClass = modelClasses[type] ?: throw PersistedModelMismatchException(
                type,
                modelId,
                "there is no model class named $type " +
                    "(the model classes are ${modelClasses.keys.sorted().joinToString(", ")})",
            )
            val props = try {
                KlerkJson.decode(kClass, row[ModelsTable.properties])
            } catch (e: JsonMismatchException) {
                throw PersistedModelMismatchException(type, modelId, e.reason)
            }
            return Model(
                id = ModelID(modelId),
                createdAt = decode64bitMicroseconds(row[ModelsTable.createdAt]),
                lastPropsUpdatedAt = decode64bitMicroseconds(row[ModelsTable.lastPropsUpdatedAt]),
                lastStateTransitionAt = decode64bitMicroseconds(row[ModelsTable.lastStateTransitionAt]),
                state = row[ModelsTable.state],
                timeTrigger = row[ModelsTable.timeTrigger]?.let { decode64bitMicroseconds(it) },
                props = props,
            )
        } catch (e: Exception) {
            logger.error { "Error while reading model $modelId from database" }
            throw e
        }
    }

    override fun readEventLog(
        modelId: Int?,
        after: Instant,
        before: Instant,
        upToSequenceNumber: Long,
    ): Iterable<EventLogEntry> {
        return transaction(database) {
            val query = EventLogTable.selectAll()
                .where(timestamp greaterEq after.to64bitMicroseconds())
                .andWhere { timestamp lessEq before.to64bitMicroseconds() }
                .andWhere { EventLogTable.sequenceNumber lessEq upToSequenceNumber }

            if (modelId != null) {
                query.andWhere { EventLogTable.modelId eq modelId }
            }

            return@transaction query.orderBy(EventLogTable.sequenceNumber).map { row -> toEventLogEntry(row) }
        }
    }

    override fun readEventLogEntry(sequenceNumber: Long): EventLogEntry? = transaction(database) {
        EventLogTable.selectAll()
            .where { EventLogTable.sequenceNumber eq sequenceNumber }
            .firstOrNull()
            ?.let { toEventLogEntry(it) }
    }

    override fun lastEventLogSequenceNumber(): Long = transaction(database) {
        EventLogTable.select(EventLogTable.sequenceNumber)
            .orderBy(EventLogTable.sequenceNumber, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(EventLogTable.sequenceNumber) ?: 0L
    }

    private fun toEventLogEntry(row: ResultRow): EventLogEntry = EventLogEntry(
        sequenceNumber = row[EventLogTable.sequenceNumber],
        time = decode64bitMicroseconds(row[timestamp]),
        eventReference = EventReference.parse(row[event]),
        model = ModelID(row[EventLogTable.modelId]),
        actorType = ActorType.fromStoredValue(row[actorIdentityType].toInt()),
        actorReference = row[actorIdentityReference],
        actorExternalId = row[actorIdentityExternalId],
        params = row[EventLogTable.params],
        extra = row[EventLogTable.extra],
    )

    override fun eraseEventLogsOfDeletedModels(deletedAtOrBefore: Instant) {
        val cutoff = deletedAtOrBefore.to64bitMicroseconds()
        transaction(database) {
            val modelIds = EventLogTombstonesTable.select(EventLogTombstonesTable.modelId)
                .where { EventLogTombstonesTable.deletedAt lessEq cutoff }
                .map { it[EventLogTombstonesTable.modelId] }
            for (chunk in modelIds.chunked(ERASE_CHUNK_SIZE)) {
                EventLogTable.deleteWhere { EventLogTable.modelId inList chunk }
                EventLogTombstonesTable.deleteWhere { EventLogTombstonesTable.modelId inList chunk }
            }
        }
    }

    override fun eraseEventLogParamsAndExtra(before: Instant) {
        val cutoff = before.to64bitMicroseconds()
        transaction(database) {
            EventLogTable.update({
                (timestamp less cutoff) and (EventLogTable.params.isNotNull() or EventLogTable.extra.isNotNull())
            }) {
                it[params] = null
                it[extra] = null
            }
        }
    }

    override fun readEventLogTombstones(): Map<Int, Instant> = transaction(database) {
        EventLogTombstonesTable.selectAll().associate {
            it[EventLogTombstonesTable.modelId] to decode64bitMicroseconds(it[EventLogTombstonesTable.deletedAt])
        }
    }

    override fun readCommandTokens(createdAtOrAfter: Instant): List<UsedCommandToken> = transaction(database) {
        CommandTokensTable.selectAll()
            .where { CommandTokensTable.createdAt greaterEq createdAtOrAfter.to64bitMicroseconds() }
            .map { row ->
                val createdAt = decode64bitMicroseconds(row[CommandTokensTable.createdAt])
                UsedCommandToken(row[CommandTokensTable.nonce], createdAt)
            }
    }

    override fun deleteCommandTokens(createdBefore: Instant) {
        val cutoff = createdBefore.to64bitMicroseconds()
        transaction(database) {
            CommandTokensTable.deleteWhere { createdAt less cutoff }
        }
    }

    override fun setSpecification(specification: Specification<*, *>) {
        this.specification = specification
        this.modelClasses = specification.managedModels.associate { it.kClass.simpleName!! to it.kClass }
    }

    override fun migrate(migrations: List<MigrationStep>) {
        logger.info { "Migrating database" }
        transaction(database) {
            for (migration in migrations) {
                logger.info { "Applying migration: ${migration.description}" }
                val executionTime = measureTimeMillis {
                    for (row in ModelsTable.selectAll()) {
                        when (migration) {
                            is ModelMigrationStep -> migrateV1toV1(migration, row)
                            else -> error("Unknown migration step")
                        }
                    }
                }
                ModelSchemaMigrationsTable.insert {
                    it[toVersion] = migration.migratesToVersion
                    it[description] = migration.description
                    it[installedOn] = Clock.System.now().toString()
                    it[executionTimeMillis] = executionTime.toInt()
                }
                currentModelSchemaVersion = migration.migratesToVersion
            }
        }
        logger.info { "Migration done (version is now $currentModelSchemaVersion)" }
    }

    /**
     * Must be called within a transaction so that the attached data is committed together with the models.
     */
    private fun applyAttachedDataDelta(attachedData: AttachedDataDelta) {
        for ((dataId, claim) in attachedData.claimed) {
            AttachedDataTable.update(where = { AttachedDataTable.id eq dataId }) {
                it[owner] = claim.owner
                it[expires] = null
                it[visibility] = claim.visibility.ordinal.toByte()
            }
        }
        for (dataId in attachedData.deleted) {
            AttachedDataTable.deleteWhere { id eq dataId }
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
        preparedBy: StoredActor,
        claimedByJob: JobID?,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        transaction(database) {
            // insert (not upsert): with a primary key, an id collision from any source throws instead of destroying
            // data
            AttachedDataTable.insert {
                it[this.id] = id
                // An empty blob rather than null when the bytes live in an external store: the column is NOT NULL in
                // databases created by earlier versions, and SchemaUtils.create never alters an existing table.
                it[this.value] = if (value == null) EMPTY_BLOB else ExposedBlob(value)
                it[this.kind] = kind.ordinal.toByte()
                it[this.owner] = null
                it[this.visibility] = visibility.ordinal.toByte()
                it[this.created] = createdAt.to64bitMicroseconds()
                it[this.size] = 0
                it[this.hash] = ""
                it[this.metadata] = encodeCustomMetadata(custom)
                it[this.preparedFor] = preparedFor
                it[this.expires] = expires.to64bitMicroseconds()
                it[this.claimedByJob] = claimedByJob?.value
                it[this.preparedByType] = preparedBy.type.storedValue
                it[this.preparedById] = preparedBy.id
                it[this.preparedByExternalId] = preparedBy.externalId
                it[this.preparedByPluginName] = preparedBy.pluginName
            }
            // the stream has been consumed by the insert above, so the digest is complete. Updating in the same
            // transaction means no row is ever committed without its size, hash and content type.
            val digest = digestAfterWrite()
            AttachedDataTable.update(where = { AttachedDataTable.id eq id }) {
                it[this.size] = digest.size
                it[this.hash] = digest.hash
                it[this.contentType] = digest.contentType
            }
        }
    }

    override fun getAttachedData(id: Int): AttachedDataRow<Unit>? = transaction(database) {
        AttachedDataTable.select(
            AttachedDataTable.id,
            AttachedDataTable.owner,
            AttachedDataTable.kind,
            AttachedDataTable.visibility,
            AttachedDataTable.created,
            AttachedDataTable.size,
            AttachedDataTable.hash,
            AttachedDataTable.metadata,
            AttachedDataTable.contentType,
            AttachedDataTable.completedSteps,
            AttachedDataTable.preparedFor,
            AttachedDataTable.expires,
            AttachedDataTable.claimedByJob,
        )
            .where { AttachedDataTable.id eq id }
            .map {
                AttachedDataRow(
                    value = Unit,
                    owner = it[AttachedDataTable.owner],
                    metadata = it.toAttachedDataMetadata(),
                    expires = it[AttachedDataTable.expires]?.let { e -> decode64bitMicroseconds(e) },
                    claimedByJob = it[AttachedDataTable.claimedByJob]?.let { j -> JobID(j) },
                )
            }.firstOrNull()
    }

    override fun updateAttachedData(
        id: Int,
        value: InputStream?,
        completedSteps: List<String>,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        transaction(database) {
            if (value != null) {
                AttachedDataTable.update(where = { AttachedDataTable.id eq id }) { it[this.value] = ExposedBlob(value) }
            }
            // As on insert: the stream has been consumed above, so the digest is complete, and it is written in the
            // same transaction as the bytes it describes.
            val digest = digestAfterWrite()
            AttachedDataTable.update(where = { AttachedDataTable.id eq id }) {
                it[this.size] = digest.size
                it[this.hash] = digest.hash
                it[this.contentType] = digest.contentType
                it[this.completedSteps] = Json.encodeToString(stringListSerializer, completedSteps)
            }
        }
    }

    override fun getAttachedValue(id: Int): InputStream? = transaction(database) {
        AttachedDataTable.select(AttachedDataTable.value)
            .where { AttachedDataTable.id eq id }
            .map { it[AttachedDataTable.value].inputStream }
            .firstOrNull()
    }

    override fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>> = transaction(database) {
        AttachedDataTable.select(
            AttachedDataTable.id,
            AttachedDataTable.owner,
            AttachedDataTable.kind,
            AttachedDataTable.visibility,
            AttachedDataTable.created,
            AttachedDataTable.size,
            AttachedDataTable.hash,
            AttachedDataTable.metadata,
            AttachedDataTable.contentType,
            AttachedDataTable.completedSteps,
            AttachedDataTable.preparedFor,
            AttachedDataTable.expires,
            AttachedDataTable.claimedByJob,
            AttachedDataTable.preparedByType,
            AttachedDataTable.preparedById,
            AttachedDataTable.preparedByExternalId,
            AttachedDataTable.preparedByPluginName,
        ).associate {
            it[AttachedDataTable.id] to AttachedDataRow(
                Unit,
                it[AttachedDataTable.owner],
                it.toAttachedDataMetadata(),
                it[AttachedDataTable.expires]?.let { e -> decode64bitMicroseconds(e) },
                it[AttachedDataTable.claimedByJob]?.let { j -> JobID(j) },
                it.toPreparedBy(),
            )
        }
    }

    private fun ResultRow.toPreparedBy(): StoredActor? = this[AttachedDataTable.preparedByType]?.let { type ->
        StoredActor(
            type = ActorType.fromStoredValue(type),
            id = this[AttachedDataTable.preparedById],
            externalId = this[AttachedDataTable.preparedByExternalId],
            pluginName = this[AttachedDataTable.preparedByPluginName],
        )
    }

    private fun ResultRow.toAttachedDataMetadata(): AttachedDataMetadata = AttachedDataMetadata(
        id = AttachedDataID(this[AttachedDataTable.id]),
        kind = AttachedDataKind.entries[this[AttachedDataTable.kind].toInt()],
        visibility = AttachedDataVisibility.entries[this[AttachedDataTable.visibility].toInt()],
        createdAt = decode64bitMicroseconds(this[AttachedDataTable.created]),
        size = this[AttachedDataTable.size],
        hash = this[AttachedDataTable.hash],
        custom = decodeCustomMetadata(this[AttachedDataTable.metadata]),
        contentType = this[AttachedDataTable.contentType],
        completedSteps = this[AttachedDataTable.completedSteps]?.let { Json.decodeFromString(stringListSerializer, it) }
            ?: emptyList(),
        preparedFor = this[AttachedDataTable.preparedFor],
    )

    private fun encodeCustomMetadata(custom: Map<String, String>): String? =
        if (custom.isEmpty()) null else Json.encodeToString(stringMapSerializer, custom)

    private fun decodeCustomMetadata(json: String?): Map<String, String> =
        json?.let { Json.decodeFromString(stringMapSerializer, it) } ?: emptyMap()

    override fun deleteExpiredAttachedData(now: Instant): Set<Int> {
        val cutoff = now.to64bitMicroseconds()
        return transaction(database) {
            // Rows with a null expiry (i.e. ones a model owns) never match a comparison, so they are left alone. Rows
            // a job has claimed are excluded explicitly: they have no owning model yet, but they are not orphans.
            // Read the ids first: the caller needs them to delete the bytes from an external blob store.
            val doomed = AttachedDataTable.select(AttachedDataTable.id)
                .where { (AttachedDataTable.expires less cutoff) and (AttachedDataTable.claimedByJob eq null) }
                .map { it[AttachedDataTable.id] }
                .toSet()
            AttachedDataTable.deleteWhere { (expires less cutoff) and (claimedByJob eq null) }
            doomed
        }
    }

    override fun deleteAttachedData(ids: Set<Int>) {
        if (ids.isEmpty()) {
            return
        }
        transaction(database) {
            AttachedDataTable.deleteWhere { AttachedDataTable.id inList ids }
        }
    }

    /**
     * Writes the job part of a commit. Must be called inside a transaction — that is what makes the atomicity
     * contract on [Persistence.commitJobStep] hold.
     */
    private fun applyJobCommit(commit: JobCommit) {
        for (record in commit.upserted) {
            val updated = JobsTable.update({ JobsTable.id eq record.id.value }) { it.writeJob(record) }
            if (updated == 0) {
                JobsTable.insert {
                    it[this.id] = record.id.value
                    it.writeJob(record)
                }
            }
        }
        for (jobId in commit.deleted) {
            JobsTable.deleteWhere { id eq jobId.value }
        }
        for ((dataId, jobId) in commit.attachedDataClaimed) {
            AttachedDataTable.update(where = { AttachedDataTable.id eq dataId }) {
                it[claimedByJob] = jobId.value
            }
        }
        for (dataId in commit.attachedDataReleased) {
            AttachedDataTable.update(where = { AttachedDataTable.id eq dataId }) {
                it[claimedByJob] = null
            }
        }
    }

    private fun UpdateBuilder<*>.writeJob(record: JobRecord) {
        this[JobsTable.name] = record.name.value
        this[JobsTable.cursor] = record.cursor
        this[JobsTable.status] = record.status.ordinal.toByte()
        this[JobsTable.priority] = record.priority.ordinal.toByte()
        this[JobsTable.agent] = record.agent.ordinal.toByte()
        this[JobsTable.ownerActorType] = record.ownerActorType.storedValue
        this[JobsTable.ownerActorId] = record.ownerActorId
        this[JobsTable.ownerActorExternalId] = record.ownerActorExternalId
        this[JobsTable.ownerActorName] = record.ownerActorName
        this[JobsTable.step] = record.step
        this[JobsTable.attempt] = record.attempt
        this[JobsTable.created] = record.createdAt.to64bitMicroseconds()
        this[JobsTable.readyAt] = record.readyAt?.to64bitMicroseconds()
        this[JobsTable.firstAttemptStarted] = record.firstAttemptStarted?.to64bitMicroseconds()
        this[JobsTable.lastAttemptStarted] = record.lastAttemptStarted?.to64bitMicroseconds()
        this[JobsTable.lastAttemptFinished] = record.lastAttemptFinished?.to64bitMicroseconds()
        this[JobsTable.progressCompleted] = record.progressCompleted
        this[JobsTable.progressTotal] = record.progressTotal
        this[JobsTable.progressMessage] = record.progressMessage
        this[JobsTable.log] = jobJson.encodeToString(logSerializer, record.log)
        this[JobsTable.parent] = record.parent?.value
        this[JobsTable.root] = record.root.value
        this[JobsTable.depth] = record.depth
        this[JobsTable.result] = record.result
        this[JobsTable.failedAtCursor] = record.failedAtCursor
        this[JobsTable.hookCursor] = record.hookCursor
        this[JobsTable.hookKind] = record.hookKind?.ordinal?.toByte()
        this[JobsTable.cancellationRequested] = record.cancellationRequested
        this[JobsTable.reason] = record.reason
        this[JobsTable.noProgressStreak] = record.noProgressStreak
        this[JobsTable.cronScheduleId] = record.cronScheduleId
    }

    override fun allJobs(): List<JobRecord> = transaction(database) {
        JobsTable.selectAll().map { row ->
            JobRecord(
                id = JobID(row[JobsTable.id]),
                name = JobName(row[JobsTable.name]),
                cursor = row[JobsTable.cursor],
                status = JobStatus.entries[row[JobsTable.status].toInt()],
                priority = JobPriority.entries[row[JobsTable.priority].toInt()],
                agent = JobAgent.entries[row[JobsTable.agent].toInt()],
                ownerActorType = ActorType.fromStoredValue(row[JobsTable.ownerActorType]),
                ownerActorId = row[JobsTable.ownerActorId],
                ownerActorExternalId = row[JobsTable.ownerActorExternalId],
                ownerActorName = row[JobsTable.ownerActorName],
                step = row[JobsTable.step],
                attempt = row[JobsTable.attempt],
                createdAt = decode64bitMicroseconds(row[JobsTable.created]),
                readyAt = row[JobsTable.readyAt]?.let { decode64bitMicroseconds(it) },
                firstAttemptStarted = row[JobsTable.firstAttemptStarted]?.let { decode64bitMicroseconds(it) },
                lastAttemptStarted = row[JobsTable.lastAttemptStarted]?.let { decode64bitMicroseconds(it) },
                lastAttemptFinished = row[JobsTable.lastAttemptFinished]?.let { decode64bitMicroseconds(it) },
                progressCompleted = row[JobsTable.progressCompleted],
                progressTotal = row[JobsTable.progressTotal],
                progressMessage = row[JobsTable.progressMessage],
                log = jobJson.decodeFromString(logSerializer, row[JobsTable.log]),
                parent = row[JobsTable.parent]?.let { JobID(it) },
                root = JobID(row[JobsTable.root]),
                depth = row[JobsTable.depth],
                result = row[JobsTable.result],
                failedAtCursor = row[JobsTable.failedAtCursor],
                hookCursor = row[JobsTable.hookCursor],
                hookKind = row[JobsTable.hookKind]?.let { JobHookKind.entries[it.toInt()] },
                cancellationRequested = row[JobsTable.cancellationRequested],
                reason = row[JobsTable.reason],
                noProgressStreak = row[JobsTable.noProgressStreak],
                cronScheduleId = row[JobsTable.cronScheduleId],
            )
        }
    }

    override fun cronState(): Map<String, Instant> = transaction(database) {
        CronStateTable.selectAll().associate {
            it[CronStateTable.scheduleId] to decode64bitMicroseconds(it[CronStateTable.lastFiredAt])
        }
    }

    override fun setCronFired(scheduleId: String, firedAt: Instant) {
        transaction(database) {
            val micros = firedAt.to64bitMicroseconds()
            val updated = CronStateTable.update({ CronStateTable.scheduleId eq scheduleId }) {
                it[lastFiredAt] = micros
            }
            if (updated == 0) {
                CronStateTable.insert {
                    it[this.scheduleId] = scheduleId
                    it[this.lastFiredAt] = micros
                }
            }
        }
    }

    private fun migrateV1toV1(migration: ModelMigrationStep, row: ResultRow) {
        val before = MigrationModelV1(
            type = row[ModelsTable.type],
            id = row[ModelsTable.id],
            createdAt = decode64bitMicroseconds(row[ModelsTable.createdAt]),
            lastPropsUpdatedAt = decode64bitMicroseconds(row[ModelsTable.lastPropsUpdatedAt]),
            lastStateTransitionAt = decode64bitMicroseconds(row[ModelsTable.lastStateTransitionAt]),
            state = row[ModelsTable.state],
            props = Json.parseToJsonElement(row[ModelsTable.properties]).jsonObject,
        )
        val after = migration.migrateModel(before)
        if (after == before) {
            return
        }
        if (after == null) {
            ModelsTable.deleteWhere { ModelsTable.id eq before.id }
        } else {
            require(after.id == before.id)
            ModelsTable.update({ ModelsTable.id eq before.id }) {
                it[type] = after.type
                it[id] = after.id
                it[createdAt] = after.createdAt.to64bitMicroseconds()
                it[lastPropsUpdatedAt] = after.lastPropsUpdatedAt.to64bitMicroseconds()
                it[lastStateTransitionAt] = after.lastStateTransitionAt.to64bitMicroseconds()
                it[state] = after.state
                it[properties] = after.props.toString()
            }
        }
    }

    override fun toString(): String = "SqlPersistence($dataSource)"
}

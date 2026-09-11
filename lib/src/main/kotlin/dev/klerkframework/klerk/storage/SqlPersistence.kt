package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.migration.MigrationModelV1
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.migration.MigrationStepV1toV1
import dev.klerkframework.klerk.misc.JsonMismatchException
import dev.klerkframework.klerk.misc.KlerkJson
import dev.klerkframework.klerk.storage.SqlPersistence.EventLog.actorIdentityExternalId
import dev.klerkframework.klerk.storage.SqlPersistence.EventLog.actorIdentityReference
import dev.klerkframework.klerk.storage.SqlPersistence.EventLog.actorIdentityType
import dev.klerkframework.klerk.storage.SqlPersistence.EventLog.event
import dev.klerkframework.klerk.storage.SqlPersistence.EventLog.timestamp
import dev.klerkframework.klerk.storage.SqlPersistence.ModelSchemaMigrations.toVersion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.InputStream
import javax.sql.DataSource
import kotlin.reflect.KClass
import kotlin.system.measureTimeMillis
import kotlin.time.Clock
import kotlin.time.Instant

/** Stands in for the value of a row whose bytes live in an [AttachedBlobStore.External]. */
private val EMPTY_BLOB = ExposedBlob(ByteArray(0))

/** The job log and the child outcomes are stored as JSON, since neither is ever queried by SQL. */
private val jobJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private val logSerializer = ListSerializer(JobLogEntry.serializer())
private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
private val stringListSerializer = ListSerializer(String.serializer())

/**
 * [Persistence] backend for a SQL database, via a [DataSource] and [Exposed](https://github.com/JetBrains/Exposed).
 * On construction, connects and creates its tables if missing (event log, models, schema-migration tracking,
 * attached data, jobs), then reads [currentModelSchemaVersion] from the `klerk_model_schema_migrations` table.
 * Model `props` and command `params` are stored as JSON.
 */
public class SqlPersistence(dataSource: DataSource) : Persistence {

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
                SchemaUtils.create(EventLog)
                SchemaUtils.create(Models)
                SchemaUtils.create(ModelSchemaMigrations)
                SchemaUtils.create(AttachedData)
                SchemaUtils.create(Jobs)
                SchemaUtils.create(CronState)
                currentModelSchemaVersion = readCurrentModelSchemaVersion()
                logger.info { "Database ready (version: $currentModelSchemaVersion)" }
            } catch (e: Exception) {
                logger.error(e) { "Database not ready" }
            }
        }
    }

    private fun readCurrentModelSchemaVersion(): Int {
        val maxRow = ModelSchemaMigrations.selectAll().maxByOrNull { it[toVersion] }
        if (maxRow == null) {
            return 1
        }
        return maxRow[toVersion]
    }

    override fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta,
        jobs: JobCommit,
        sequenceNumber: Long,
    ) {
        transaction(database) {
            writeAll(delta, command, context, attachedData, jobs, sequenceNumber)
        }
    }

    override fun <T : Any, P, C : KlerkContext, V> commitJobStep(
        delta: ProcessingData<out T, C, V>?,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta,
        jobs: JobCommit,
        sequenceNumber: Long,
    ) {
        // One Exposed transaction, so the whole contract on Persistence.commitJobStep holds: models, event log entry,
        // attached data and every job row either land together or not at all.
        transaction(database) {
            writeAll(delta, command, context, attachedData, jobs, sequenceNumber)
        }
    }

    /**
     * The body shared by [store] and [commitJobStep]. Must be called inside a transaction.
     */
    private fun <T : Any, P, C : KlerkContext, V> writeAll(
        delta: ProcessingData<out T, C, V>?,
        command: Command<T, P>?,
        context: C?,
        attachedData: AttachedDataDelta,
        jobCommit: JobCommit,
        sequenceNumber: Long,
    ) {
        run {
            applyAttachedDataDelta(attachedData)
            applyJobCommit(jobCommit)

            if (delta == null) {
                return
            }

            if (command != null) {
                requireNotNull(context)
                val reference = command.model?.value ?: delta.primaryModel?.value ?: 0
                EventLog.insert {
                    it[EventLog.sequenceNumber] = sequenceNumber
                    it[timestamp] = context.time.to64bitMicroseconds()
                    it[event] = command.event.id.toString()
                    it[modelId] = reference
                    it[params] = KlerkJson.encode(command.params)
                    it[actorIdentityType] = context.actor.type.toByte()
                    it[actorIdentityReference] = context.actor.id?.value
                    it[actorIdentityExternalId] = context.actor.externalId
                    it[extra] = context.eventLogExtra
                }
            }

            delta.createdModels.forEach { modelId ->
                val model = requireNotNull(delta.aggregatedModelState[modelId])
                Models.insert {
                    it[id] = model.id.value
                    it[type] = model.props::class.simpleName!!
                    it[createdAt] = model.createdAt.to64bitMicroseconds()
                    it[lastPropsUpdateAt] = model.lastPropsUpdateAt.to64bitMicroseconds()
                    it[lastTransitionAt] = model.lastStateTransitionAt.to64bitMicroseconds()
                    it[state] = model.state
                    it[timeTrigger] = model.timeTrigger?.to64bitMicroseconds()
                    it[properties] = KlerkJson.encode(model.props)
                }
            }

            delta.updatedModels
                .union(delta.transitions)
                .minus(delta.createdModels.toSet()) //  We have already stored these above
                .forEach { modelId ->
                    val model = requireNotNull(delta.aggregatedModelState[modelId])
                    Models.update({ Models.id eq modelId.value }) {
                        it[lastPropsUpdateAt] = model.lastPropsUpdateAt.to64bitMicroseconds()
                        it[lastTransitionAt] = model.lastStateTransitionAt.to64bitMicroseconds()
                        it[state] = model.state
                        it[timeTrigger] = model.timeTrigger?.to64bitMicroseconds()
                        it[properties] = KlerkJson.encode(model.props)
                    }
                }

            delta.deletedModels.forEach { modelId ->
                Models.deleteWhere { id eq modelId.value }
            }
        }
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit): Unit {
        transaction(database) {
            Models.selectAll().forEach { row -> lambda(toModel(row)) }
        }
    }

    override fun readModel(id: Int): Model<out Any>? {
        return transaction(database) {
            Models.selectAll().where { Models.id eq id }.singleOrNull()?.let { toModel(it) }
        }
    }

    private fun toModel(row: ResultRow): Model<out Any> {
        val modelId = row[Models.id]
        try {
            val type = row[Models.type]
            val kClass = modelClasses[type] ?: throw PersistedModelMismatchException(
                type,
                modelId,
                "there is no model class named $type (the model classes are ${modelClasses.keys.sorted().joinToString(", ")})"
            )
            val props = try {
                KlerkJson.decode(kClass, row[Models.properties])
            } catch (e: JsonMismatchException) {
                throw PersistedModelMismatchException(type, modelId, e.reason)
            }
            return Model(
                id = ModelID(modelId),
                createdAt = decode64bitMicroseconds(row[Models.createdAt]),
                lastPropsUpdateAt = decode64bitMicroseconds(row[Models.lastPropsUpdateAt]),
                lastStateTransitionAt = decode64bitMicroseconds(row[Models.lastTransitionAt]),
                state = row[Models.state],
                timeTrigger = row[Models.timeTrigger]?.let { decode64bitMicroseconds(it) },
                props = props
            )
        } catch (e: Exception) {
            logger.error { "Error while reading model $modelId from database" }
            throw e
        }
    }

    override fun readEventLog(
        modelId: Int?,
        from: Instant,
        until: Instant,
        upToSequenceNumber: Long,
        sequenceNumber: Long?,
    ): Iterable<EventLogEntry> {
        return transaction(database) {
            val query = EventLog.selectAll()
                .where(timestamp greaterEq from.to64bitMicroseconds())
                .andWhere { timestamp lessEq until.to64bitMicroseconds() }
                .andWhere { EventLog.sequenceNumber lessEq upToSequenceNumber }

            if (modelId != null) {
                query.andWhere { EventLog.modelId eq modelId }
            }
            if (sequenceNumber != null) {
                query.andWhere { EventLog.sequenceNumber eq sequenceNumber }
            }

            return@transaction query.orderBy(EventLog.sequenceNumber).map { row -> toEventLogEntry(row) }
        }
    }

    override fun lastEventLogSequenceNumber(): Long = transaction(database) {
        EventLog.select(EventLog.sequenceNumber)
            .orderBy(EventLog.sequenceNumber, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(EventLog.sequenceNumber) ?: 0L
    }

    private fun toEventLogEntry(row: ResultRow): EventLogEntry = EventLogEntry(
        sequenceNumber = row[EventLog.sequenceNumber],
        time = decode64bitMicroseconds(row[timestamp]),
        eventReference = EventReference.from(row[event]),
        reference = row[EventLog.modelId],
        actorType = row[actorIdentityType],
        actorReference = row[actorIdentityReference],
        actorExternalId = row[actorIdentityExternalId],
        params = row[EventLog.params],
        extra = row[EventLog.extra],
    )

    override fun modifyEventLog(modelId: Int, transformer: (EventLogEntry) -> EventLogEntry?): Unit {
        val updatedEntries = mutableSetOf<EventLogEntry>()
        val deletedEntries = mutableSetOf<Long>()
        readEventLog(modelId).forEach { original ->
            val updated = transformer(original)
            if (updated == null) {
                deletedEntries.add(original.sequenceNumber)
                return@forEach
            }
            require(updated.reference == original.reference) { "Updating of ID is not supported" }
            require(updated.sequenceNumber == original.sequenceNumber) { "Updating of sequenceNumber is not supported" }
            updatedEntries.add(updated)
        }

        transaction(database) {
            deletedEntries.forEach { seq ->
                EventLog.deleteWhere { EventLog.sequenceNumber eq seq }
            }
            updatedEntries.forEach { updated ->
                EventLog.update({ EventLog.sequenceNumber eq updated.sequenceNumber }) {
                    it[timestamp] = updated.time.to64bitMicroseconds()
                    it[event] = updated.eventReference.id()
                    it[params] = updated.params
                    it[actorIdentityType] = updated.actorType
                    it[actorIdentityReference] = updated.actorReference
                    it[actorIdentityExternalId] = updated.actorExternalId
                    it[extra] = updated.extra
                }
            }
        }
    }

    override fun setSpecification(specification: Specification<*, *>) {
        this.specification = specification
        this.modelClasses = specification.managedModels.associate { it.kClass.simpleName!! to it.kClass }
    }

    override fun migrate(migrations: List<MigrationStep>) {
        logger.info { "Migrating database" }
        transaction(database) {
            migrations.forEach { migration ->
                logger.info { "Applying migration: ${migration.description}" }
                val executionTime = measureTimeMillis {
                    Models.selectAll().forEach { row ->
                        when (migration) {
                            is MigrationStepV1toV1 -> migrateV1toV1(migration, row)
                            else -> error("Unknown migration step")
                        }
                    }
                }
                ModelSchemaMigrations.insert {
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
        attachedData.claimed.forEach { (dataId, claim) ->
            AttachedData.update(where = { AttachedData.id eq dataId }) {
                it[owner] = claim.owner
                it[expires] = null
                it[visibility] = claim.visibility.ordinal.toByte()
            }
        }
        attachedData.deleted.forEach { dataId ->
            AttachedData.deleteWhere { id eq dataId }
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
        claimedByJob: JobId?,
        digestAfterWrite: () -> AttachedDataDigest,
    ) {
        transaction(database) {
            // insert (not upsert): with a primary key, an id collision from any source throws instead of destroying data
            AttachedData.insert {
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
            }
            // the stream has been consumed by the insert above, so the digest is complete. Updating in the same
            // transaction means no row is ever committed without its size, hash and content type.
            val digest = digestAfterWrite()
            AttachedData.update(where = { AttachedData.id eq id }) {
                it[this.size] = digest.size
                it[this.hash] = digest.hash
                it[this.contentType] = digest.contentType
            }
        }
    }

    override fun getAttachedData(id: Int): AttachedDataRow<Unit>? =
        transaction(database) {
            AttachedData.select(
                AttachedData.id,
                AttachedData.owner,
                AttachedData.kind,
                AttachedData.visibility,
                AttachedData.created,
                AttachedData.size,
                AttachedData.hash,
                AttachedData.metadata,
                AttachedData.contentType,
                AttachedData.completedSteps,
                AttachedData.preparedFor,
                AttachedData.expires,
                AttachedData.claimedByJob,
            )
                .where { AttachedData.id eq id }
                .map {
                    AttachedDataRow(
                        value = Unit,
                        owner = it[AttachedData.owner],
                        metadata = it.toAttachedDataMetadata(),
                        expires = it[AttachedData.expires]?.let { e -> decode64bitMicroseconds(e) },
                        claimedByJob = it[AttachedData.claimedByJob]?.let { j -> JobId(j) },
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
                AttachedData.update(where = { AttachedData.id eq id }) { it[this.value] = ExposedBlob(value) }
            }
            // As on insert: the stream has been consumed above, so the digest is complete, and it is written in the
            // same transaction as the bytes it describes.
            val digest = digestAfterWrite()
            AttachedData.update(where = { AttachedData.id eq id }) {
                it[this.size] = digest.size
                it[this.hash] = digest.hash
                it[this.contentType] = digest.contentType
                it[this.completedSteps] = Json.encodeToString(stringListSerializer, completedSteps)
            }
        }
    }

    override fun getAttachedValue(id: Int): InputStream? =
        transaction(database) {
            AttachedData.select(AttachedData.value)
                .where { AttachedData.id eq id }
                .map { it[AttachedData.value].inputStream }
                .firstOrNull()
        }

    override fun readAllAttachedDataMetadata(): Map<Int, AttachedDataRow<Unit>> =
        transaction(database) {
            AttachedData.select(
                AttachedData.id,
                AttachedData.owner,
                AttachedData.kind,
                AttachedData.visibility,
                AttachedData.created,
                AttachedData.size,
                AttachedData.hash,
                AttachedData.metadata,
                AttachedData.contentType,
                AttachedData.completedSteps,
                AttachedData.preparedFor,
                AttachedData.expires,
                AttachedData.claimedByJob
            ).associate {
                it[AttachedData.id] to AttachedDataRow(
                    Unit,
                    it[AttachedData.owner],
                    it.toAttachedDataMetadata(),
                    it[AttachedData.expires]?.let { e -> decode64bitMicroseconds(e) },
                    it[AttachedData.claimedByJob]?.let { j -> JobId(j) })
            }
        }

    private fun ResultRow.toAttachedDataMetadata(): AttachedDataMetadata = AttachedDataMetadata(
        id = AttachedDataID(this[AttachedData.id]),
        kind = AttachedDataKind.entries[this[AttachedData.kind].toInt()],
        visibility = AttachedDataVisibility.entries[this[AttachedData.visibility].toInt()],
        createdAt = decode64bitMicroseconds(this[AttachedData.created]),
        size = this[AttachedData.size],
        hash = this[AttachedData.hash],
        custom = decodeCustomMetadata(this[AttachedData.metadata]),
        contentType = this[AttachedData.contentType],
        completedSteps = this[AttachedData.completedSteps]?.let { Json.decodeFromString(stringListSerializer, it) }
            ?: emptyList(),
        preparedFor = this[AttachedData.preparedFor],
    )

    private fun encodeCustomMetadata(custom: Map<String, String>): String? =
        if (custom.isEmpty()) null else Json.encodeToString(stringMapSerializer, custom)

    private fun decodeCustomMetadata(json: String?): Map<String, String> =
        if (json == null) emptyMap() else Json.decodeFromString(stringMapSerializer, json)

    override fun deleteExpiredAttachedData(now: Instant): Set<Int> {
        val cutoff = now.to64bitMicroseconds()
        return transaction(database) {
            // Rows with a null expiry (i.e. ones a model owns) never match a comparison, so they are left alone. Rows
            // a job has claimed are excluded explicitly: they have no owning model yet, but they are not orphans.
            // Read the ids first: the caller needs them to delete the bytes from an external blob store.
            val doomed = AttachedData.select(AttachedData.id)
                .where { (AttachedData.expires less cutoff) and (AttachedData.claimedByJob eq null) }
                .map { it[AttachedData.id] }
                .toSet()
            AttachedData.deleteWhere { (expires less cutoff) and (claimedByJob eq null) }
            doomed
        }
    }

    override fun deleteAttachedData(ids: Set<Int>) {
        if (ids.isEmpty()) {
            return
        }
        transaction(database) {
            AttachedData.deleteWhere { AttachedData.id inList ids }
        }
    }

    /**
     * Writes the job part of a commit. Must be called inside a transaction — that is what makes the atomicity
     * contract on [Persistence.commitJobStep] hold.
     */
    private fun applyJobCommit(commit: JobCommit) {
        commit.upserted.forEach { record ->
            val updated = Jobs.update({ Jobs.id eq record.id.value }) { it.writeJob(record) }
            if (updated == 0) {
                Jobs.insert {
                    it[this.id] = record.id.value
                    it.writeJob(record)
                }
            }
        }
        commit.deleted.forEach { jobId ->
            Jobs.deleteWhere { id eq jobId.value }
        }
        commit.attachedDataClaimed.forEach { (dataId, jobId) ->
            AttachedData.update(where = { AttachedData.id eq dataId }) {
                it[claimedByJob] = jobId.value
            }
        }
        commit.attachedDataReleased.forEach { dataId ->
            AttachedData.update(where = { AttachedData.id eq dataId }) {
                it[claimedByJob] = null
            }
        }
    }

    private fun UpdateBuilder<*>.writeJob(record: JobRecord) {
        this[Jobs.name] = record.name.value
        this[Jobs.cursor] = record.cursor
        this[Jobs.status] = record.status.ordinal.toByte()
        this[Jobs.priority] = record.priority.ordinal.toByte()
        this[Jobs.agent] = record.agent.ordinal.toByte()
        this[Jobs.ownerActorType] = record.ownerActorType
        this[Jobs.ownerActorId] = record.ownerActorId
        this[Jobs.ownerActorExternalId] = record.ownerActorExternalId
        this[Jobs.stepNumber] = record.stepNumber
        this[Jobs.attempt] = record.attempt
        this[Jobs.created] = record.created.to64bitMicroseconds()
        this[Jobs.readyAt] = record.readyAt?.to64bitMicroseconds()
        this[Jobs.firstAttemptStarted] = record.firstAttemptStarted?.to64bitMicroseconds()
        this[Jobs.lastAttemptStarted] = record.lastAttemptStarted?.to64bitMicroseconds()
        this[Jobs.lastAttemptFinished] = record.lastAttemptFinished?.to64bitMicroseconds()
        this[Jobs.progressCompleted] = record.progressCompleted
        this[Jobs.progressTotal] = record.progressTotal
        this[Jobs.progressMessage] = record.progressMessage
        this[Jobs.log] = jobJson.encodeToString(logSerializer, record.log)
        this[Jobs.parentId] = record.parentId?.value
        this[Jobs.rootId] = record.rootId.value
        this[Jobs.depth] = record.depth
        this[Jobs.result] = record.result
        this[Jobs.failedAtCursor] = record.failedAtCursor
        this[Jobs.hookCursor] = record.hookCursor
        this[Jobs.hookKind] = record.hookKind?.ordinal?.toByte()
        this[Jobs.cancellationRequested] = record.cancellationRequested
        this[Jobs.reason] = record.reason
        this[Jobs.noProgressStreak] = record.noProgressStreak
        this[Jobs.cronScheduleId] = record.cronScheduleId
    }

    override fun getAllJobs(): List<JobRecord> =
        transaction(database) {
            Jobs.selectAll().map { row ->
                JobRecord(
                    id = JobId(row[Jobs.id]),
                    name = JobName(row[Jobs.name]),
                    cursor = row[Jobs.cursor],
                    status = JobStatus.entries[row[Jobs.status].toInt()],
                    priority = JobPriority.entries[row[Jobs.priority].toInt()],
                    agent = JobAgent.entries[row[Jobs.agent].toInt()],
                    ownerActorType = row[Jobs.ownerActorType],
                    ownerActorId = row[Jobs.ownerActorId],
                    ownerActorExternalId = row[Jobs.ownerActorExternalId],
                    stepNumber = row[Jobs.stepNumber],
                    attempt = row[Jobs.attempt],
                    created = decode64bitMicroseconds(row[Jobs.created]),
                    readyAt = row[Jobs.readyAt]?.let { decode64bitMicroseconds(it) },
                    firstAttemptStarted = row[Jobs.firstAttemptStarted]?.let { decode64bitMicroseconds(it) },
                    lastAttemptStarted = row[Jobs.lastAttemptStarted]?.let { decode64bitMicroseconds(it) },
                    lastAttemptFinished = row[Jobs.lastAttemptFinished]?.let { decode64bitMicroseconds(it) },
                    progressCompleted = row[Jobs.progressCompleted],
                    progressTotal = row[Jobs.progressTotal],
                    progressMessage = row[Jobs.progressMessage],
                    log = jobJson.decodeFromString(logSerializer, row[Jobs.log]),
                    parentId = row[Jobs.parentId]?.let { JobId(it) },
                    rootId = JobId(row[Jobs.rootId]),
                    depth = row[Jobs.depth],
                    result = row[Jobs.result],
                    failedAtCursor = row[Jobs.failedAtCursor],
                    hookCursor = row[Jobs.hookCursor],
                    hookKind = row[Jobs.hookKind]?.let { JobHookKind.entries[it.toInt()] },
                    cancellationRequested = row[Jobs.cancellationRequested],
                    reason = row[Jobs.reason],
                    noProgressStreak = row[Jobs.noProgressStreak],
                    cronScheduleId = row[Jobs.cronScheduleId],
                )
            }
        }

    override fun getCronState(): Map<String, Instant> =
        transaction(database) {
            CronState.selectAll().associate {
                it[CronState.scheduleId] to decode64bitMicroseconds(it[CronState.lastFiredAt])
            }
        }

    override fun setCronFired(scheduleId: String, firedAt: Instant) {
        transaction(database) {
            val micros = firedAt.to64bitMicroseconds()
            val updated = CronState.update({ CronState.scheduleId eq scheduleId }) { it[lastFiredAt] = micros }
            if (updated == 0) {
                CronState.insert {
                    it[this.scheduleId] = scheduleId
                    it[this.lastFiredAt] = micros
                }
            }
        }
    }

    internal object EventLog : Table("\"klerk_event_log\"") {
        val sequenceNumber = long("sequence_number")
        val timestamp = long("timestamp")   // microseconds since 1970
        val event = varchar("event_id", length = 100)
        val modelId = integer("model_id").index()
        val params = varchar("params", length = 100000)
        val actorIdentityType = byte("actor_identity_type")
        val actorIdentityReference = integer("actor_identity_reference").nullable()
        val actorIdentityExternalId = long("actor_identity_externalId").nullable()
        val extra = varchar("extra", length = 1000).nullable()
        override val primaryKey = PrimaryKey(sequenceNumber)
    }

    internal object Models : Table("\"klerk_models\"") {
        val id = integer("id").index()
        val type = varchar("type", length = 50)
        val createdAt = long("created")   // microseconds since 1970
        val lastPropsUpdateAt = long("last_props_update_at")   // microseconds since 1970
        val lastTransitionAt = long("last_transition_at")   // microseconds since 1970
        val state = varchar("state", length = 50)
        val timeTrigger = long("time_trigger").nullable()  // microseconds since 1970
        val properties = varchar("props", length = 100000)
        override val primaryKey = PrimaryKey(id)
    }

    // possible optimization: it[relationsToThis] = ExposedBlob(toByteArray(emptyList()))  this is a possible optimization. BUT it is probably best to store this in another table that can be wiped and rebuilt (and if so, should we have one row for each relation?)
    //val relationsToThis = blob("relations_to_this_model")    this is a possible optimization

    internal object ModelSchemaMigrations : Table("\"klerk_model_schema_migrations\"") {
        val toVersion = integer("to_version")
        val description = varchar("description", 200)
        val installedOn = varchar("installed_on", 30)
        val executionTimeMillis = integer("execution_time_ms")
    }

    /**
     * Blobs and strings live in the same table: Klerk never looks inside the value, so a string gains nothing from a
     * text column, and one table means one id space (an id is a safe cache key on its own) and one code path.
     */
    internal object AttachedData : Table("\"klerk_attached_data\"") {
        val id = integer("id")
        val value = blob("value")                   // a string is stored as its UTF-8 bytes
        val kind = byte("kind")                     // the ordinal of AttachedDataKind
        val owner = integer("owner").nullable()     // the id of the owning model, null while unclaimed
        val visibility = byte("visibility")         // the ordinal of AttachedDataVisibility
        val created = long("created")               // microseconds since 1970
        val size = long("size")                     // bytes
        val hash = varchar("hash", length = 64)     // SHA-256, lowercase hex
        val metadata = text("metadata").nullable()  // the application's own metadata, as JSON
        val expires = long("expires").nullable()    // microseconds since 1970, null once claimed by a model

        // What the bytes were recognised as, or null when they match no known format. Klerk's own finding: what the
        // uploader claimed the value was, if anything, is the application's business and lives in metadata.
        val contentType = varchar("content_type", length = 255).nullable()

        // The names of the declared steps that have run against this value, as a JSON array. What makes an
        // interrupted pipeline resumable, and what a command checks before letting a model claim the value.
        val completedSteps = text("completed_steps").nullable()

        // The qualified name of the AttachedBlobContainer the value was prepared for, whose steps are the ones above.
        val preparedFor = text("prepared_for").nullable()

        // The second, independent claim: a job that prepared this data and is still alive. A row is reaped only when
        // neither claim holds.
        val claimedByJob = integer("claimed_by_job").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    /**
     * One row per job instance. The enum-valued columns store the ordinal of the corresponding Kotlin enum, so entries
     * may be appended to those enums but never reordered or removed.
     */
    internal object Jobs : Table("\"klerk_jobs\"") {
        val id = integer("id")
        val name = varchar("name", length = 100)        // the JobName, i.e. what resolves the JobType after a restart
        val cursor = text("cursor")                     // encoded by the job type; opaque here
        val status = byte("status")                     // the ordinal of JobStatus
        val priority = byte("priority")                 // the ordinal of JobPriority
        val agent = byte("agent")                       // the ordinal of JobAgent
        val ownerActorType = integer("owner_actor_type")
        val ownerActorId = integer("owner_actor_id").nullable()
        val ownerActorExternalId = long("owner_actor_external_id").nullable()
        val stepNumber = integer("step_number")
        val attempt = integer("attempt")
        val created = long("created")                   // microseconds since 1970
        val readyAt = long("ready_at").nullable()       // microseconds since 1970
        val firstAttemptStarted = long("first_attempt_started").nullable()
        val lastAttemptStarted = long("last_attempt_started").nullable()
        val lastAttemptFinished = long("last_attempt_finished").nullable()
        val progressCompleted = integer("progress_completed").nullable()
        val progressTotal = integer("progress_total").nullable()
        val progressMessage = text("progress_message").nullable()
        val log = text("log")                           // JSON array of JobLogEntry
        val parentId = integer("parent_id").nullable()
        val rootId = integer("root_id")
        val depth = integer("depth")
        val result = text("result").nullable()
        val failedAtCursor = text("failed_at_cursor").nullable()
        val hookCursor = text("hook_cursor").nullable()
        val hookKind = byte("hook_kind").nullable()     // the ordinal of JobHookKind
        val cancellationRequested = bool("cancellation_requested")
        val reason = text("reason").nullable()
        val noProgressStreak = integer("no_progress_streak")
        val cronScheduleId = varchar("cron_schedule_id", length = 250).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    /**
     * When each cron schedule last fired. The schedules themselves are configuration, not rows — but without this,
     * `CatchUp` could not tell a restart apart from a first run.
     */
    internal object CronState : Table("\"klerk_cron_state\"") {
        val scheduleId = varchar("schedule_id", length = 250)
        val lastFiredAt = long("last_fired_at")     // microseconds since 1970
        override val primaryKey = PrimaryKey(scheduleId)
    }

    private fun migrateV1toV1(migration: MigrationStepV1toV1, row: ResultRow) {
        val before = MigrationModelV1(
            type = row[Models.type],
            id = row[Models.id],
            createdAt = decode64bitMicroseconds(row[Models.createdAt]),
            lastPropsUpdatedAt = decode64bitMicroseconds(row[Models.lastPropsUpdateAt]),
            lastTransitionAt = decode64bitMicroseconds(row[Models.lastTransitionAt]),
            state = row[Models.state],
            props = Json.parseToJsonElement(row[Models.properties]).jsonObject
        )
        val after = migration.migrateModel(before)
        if (after == before) {
            return
        }
        if (after == null) {
            Models.deleteWhere { Models.id eq before.id }
        } else {
            require(after.id == before.id)
            Models.update({ Models.id eq before.id }) {
                it[type] = after.type
                it[id] = after.id
                it[createdAt] = after.createdAt.to64bitMicroseconds()
                it[lastPropsUpdateAt] = after.lastPropsUpdatedAt.to64bitMicroseconds()
                it[lastTransitionAt] = after.lastTransitionAt.to64bitMicroseconds()
                it[state] = after.state
                it[properties] = after.props.toString()
            }
        }
    }

}

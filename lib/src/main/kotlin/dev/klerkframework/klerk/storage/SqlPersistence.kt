package dev.klerkframework.klerk.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.migration.MigrationModelV1
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.migration.MigrationStepV1toV1
import dev.klerkframework.klerk.storage.SqlPersistence.AuditLog.actorIdentityExternalId
import dev.klerkframework.klerk.storage.SqlPersistence.AuditLog.actorIdentityReference
import dev.klerkframework.klerk.storage.SqlPersistence.AuditLog.actorIdentityType
import dev.klerkframework.klerk.storage.SqlPersistence.AuditLog.event
import dev.klerkframework.klerk.storage.SqlPersistence.AuditLog.timestamp
import dev.klerkframework.klerk.storage.SqlPersistence.ModelSchemaMigrations.toVersion
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.InputStream
import javax.sql.DataSource
import kotlin.reflect.KClass
import kotlin.system.measureTimeMillis

public class SqlPersistence(dataSource: DataSource) : Persistence {

    private val database: Database
    override var currentModelSchemaVersion: Int = 0
    private val logger = KotlinLogging.logger {}
    private lateinit var config: Config<*, *>
    private lateinit var gson: Gson
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    init {
        logger.info { "Connecting to database: $dataSource" }
        database = Database.connect(dataSource)

        transaction(database) {
            try {
                SchemaUtils.create(AuditLog)
                SchemaUtils.create(Models)
                SchemaUtils.create(ModelSchemaMigrations)
                SchemaUtils.create(KeyValueStrings)
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
        context: C?
    ) {
        transaction(database) {
            if (command != null) {
                requireNotNull(context)
                val reference = command.model?.toInt() ?: delta.primaryModel?.toInt() ?: 0
                AuditLog.insert {
                    it[timestamp] = context.time.to64bitMicroseconds()
                    it[event] = command.event.id.toString()
                    it[modelId] = reference
                    it[params] = gson.toJson(command.params)
                    it[actorIdentityType] = context.actor.type.toByte()
                    it[actorIdentityReference] = context.actor.id?.toInt()
                    it[actorIdentityExternalId] = context.actor.externalId
                    it[extra] = context.auditExtra
                }
            }

            delta.createdModels.forEach { modelId ->
                val model = requireNotNull(delta.aggregatedModelState[modelId])
                Models.insert {
                    it[id] = model.id.toInt()
                    it[type] = model.props::class.simpleName!!
                    it[createdAt] = model.createdAt.to64bitMicroseconds()
                    it[lastPropsUpdateAt] = model.lastPropsUpdateAt.to64bitMicroseconds()
                    it[lastTransitionAt] = model.lastStateTransitionAt.to64bitMicroseconds()
                    it[state] = model.state
                    it[timeTrigger] = model.timeTrigger?.to64bitMicroseconds()
                    it[properties] = gson.toJson(model.props)
                }
            }

            delta.updatedModels
                .union(delta.transitions)
                .minus(delta.createdModels.toSet()) //  We have already stored these above
                .forEach { modelId ->
                    val model = requireNotNull(delta.aggregatedModelState[modelId])
                    Models.update({ Models.id eq modelId.toInt() }) {
                        it[lastPropsUpdateAt] = model.lastPropsUpdateAt.to64bitMicroseconds()
                        it[lastTransitionAt] = model.lastStateTransitionAt.to64bitMicroseconds()
                        it[state] = model.state
                        it[timeTrigger] = model.timeTrigger?.to64bitMicroseconds()
                        it[properties] = gson.toJson(model.props)
                    }
                }

            delta.deletedModels.forEach { modelId ->
                Models.deleteWhere { id eq modelId.toInt() }
            }
        }
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit): Unit {
        val modelClasses = mutableMapOf<String, KClass<out Any>>()
        config.managedModels.forEach {
            modelClasses[it.kClass.simpleName!!] = it.kClass
        }

        transaction(database) {
            Models.selectAll().forEach { row ->
                val type = row[Models.type]
                val props = gson.fromJson(
                    row[Models.properties], modelClasses[type]?.javaObjectType ?: throw NoSuchElementException(
                        "Type is $type in database but the code only has these types: ${modelClasses.keys.joinToString(", ")}"
                    )
                )

                lambda(
                    Model(
                        id = ModelID(row[Models.id]!!.toInt()),
                        createdAt = decode64bitMicroseconds(row[Models.createdAt]),
                        lastPropsUpdateAt = decode64bitMicroseconds(row[Models.lastPropsUpdateAt]),
                        lastStateTransitionAt = decode64bitMicroseconds(row[Models.lastTransitionAt]),
                        state = row[Models.state],
                        timeTrigger = row[Models.timeTrigger]?.let { decode64bitMicroseconds(it) },
                        props = props
                    )
                )
            }
        }
    }

    override fun readAuditLog(modelId: Int?, from: Instant, until: Instant): Iterable<AuditEntry> {
        return transaction(database) {
            val query = AuditLog.selectAll()
                .where(timestamp greaterEq from.to64bitMicroseconds())
                .andWhere { timestamp lessEq until.to64bitMicroseconds() }

            if (modelId != null) {
                query.andWhere { AuditLog.modelId eq modelId }
            }

            return@transaction query.map { row ->
                val time = decode64bitMicroseconds(row[timestamp])
                val eventReference = EventReference.from(row[event])
                val actorType = row[actorIdentityType]
                val actorReference = row[actorIdentityReference]
                val actorExternalId = row[actorIdentityExternalId]
                val reference = row[AuditLog.modelId]
                val params = row[AuditLog.params]
                val extra = row[AuditLog.extra]
                AuditEntry(time, eventReference, reference, actorType, actorReference, actorExternalId, params, extra)
            }
        }
    }

    override fun modifyEventsInAuditLog(modelId: Int, transformer: (AuditEntry) -> AuditEntry?): Unit {
        val updatedEntries = mutableSetOf<AuditEntry>()
        val deletedEntries = mutableSetOf<Int>()
        readAuditLog(modelId).map { original ->
            val updated = transformer(original)
            if (updated == null) {
                deletedEntries.add(original.reference)
                return@map
            }
            require(updated.reference == original.reference) { "Updating of ID is not supported" }
            updatedEntries.add(updated)
        }

        transaction(database) {
            deletedEntries.forEach { id ->
                AuditLog.deleteWhere { AuditLog.modelId eq id }
            }
            updatedEntries.forEach { updated ->
                AuditLog.update({ AuditLog.modelId eq updated.reference }) {
                    it[timestamp] = updated.time.to64bitMicroseconds()
                    it[event] = updated.eventReference.id()
                    it[params] = gson.toJson(updated.params)
                    it[actorIdentityType] = updated.actorType
                    it[actorIdentityReference] = updated.actorReference
                    it[actorIdentityExternalId] = updated.actorExternalId
                }
            }
        }
    }

    override fun setConfig(config: Config<*, *>) {
        this.config = config
        this.gson = config.gson
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

    override fun putKeyValue(id: UInt, value: String, ttl: Instant?) {
        transaction(database) {
            KeyValueStrings.insert {
                it[this.id] = id
                it[this.value] = value
                it[this.ttl] = ttl?.to64bitMicroseconds()
            }
        }
    }

    override fun putKeyValue(id: UInt, value: Int, ttl: Instant?) {
        transaction(database) {
            KeyValueInts.insert {
                it[this.id] = id
                it[this.value] = value
                it[this.ttl] = ttl?.to64bitMicroseconds()
            }
        }
    }

    override fun putKeyValue(id: UInt, value: InputStream, ttl: Instant?) {
        transaction(database) {
            KeyValueBlobs.upsert {
                it[this.id] = id
                it[this.value] = ExposedBlob(value)
                it[this.ttl] = null
                it[this.active] = false
            }
        }
    }

    override fun updateBlob(id: UInt, ttl: Instant?, active: Boolean) {
        TODO("Not yet implemented")
    }

    override fun getKeyValueString(id: UInt): Pair<String, Instant?>? =
        transaction(database) {
            KeyValueStrings.selectAll()
                .where { KeyValueStrings.id eq id }
                .map {
                    it[KeyValueStrings.value] to it[KeyValueStrings.ttl]?.let { ttl -> decode64bitMicroseconds(ttl) }
                }.firstOrNull()
        }

    override fun getKeyValueInt(id: UInt): Pair<Int, Instant?>? =
        transaction(database) {
            KeyValueInts.selectAll()
                .where { KeyValueInts.id eq id }
                .map {
                    it[KeyValueInts.value] to it[KeyValueInts.ttl]?.let { ttl -> decode64bitMicroseconds(ttl) }
                }.firstOrNull()
        }

    override fun getKeyValueBlob(id: UInt): Triple<InputStream, Instant?, Boolean>? =
        transaction(database) {
            KeyValueBlobs.selectAll()
                .where { KeyValueBlobs.id eq id }
                .map {
                    Triple(
                        it[KeyValueBlobs.value].inputStream,
                        it[KeyValueBlobs.ttl]?.let { ttl -> decode64bitMicroseconds(ttl) },
                        it[KeyValueBlobs.active]
                    )
                }.firstOrNull()
        }

    internal object AuditLog : Table("\"klerk_audit_log\"") {
        val timestamp = long("timestamp")   // microseconds since 1970
        val event = varchar("event_id", length = 100)
        val modelId = integer("model_id")
        val params = varchar("params", length = 100000)
        val actorIdentityType = byte("actor_identity_type")
        val actorIdentityReference = integer("actor_identity_reference").nullable()
        val actorIdentityExternalId = long("actor_identity_externalId").nullable()
        val extra = varchar("extra", length = 1000).nullable()
        override val primaryKey = PrimaryKey(timestamp)
    }

    internal object Models : Table("\"klerk_models\"") {
        val id = integer("id")
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

    internal object KeyValueStrings : Table("\"klerk_strings\"") {
        val id = uinteger("id")
        val value = varchar("value", length = 100000)
        val ttl = long("ttl").nullable() // microseconds since 1970
        override val primaryKey = PrimaryKey(id)
    }

    internal object KeyValueInts : Table("\"klerk_ints\"") {
        val id = uinteger("id")
        val value = integer("value")
        val ttl = long("ttl").nullable() // microseconds since 1970
        override val primaryKey = PrimaryKey(id)
    }

    internal object KeyValueBlobs : Table("\"klerk_blobs\"") {
        val id = uinteger("id")
        val value = blob("value")
        val ttl = long("ttl").nullable() // microseconds since 1970
        val active = bool("active")  // blobs are first prepared, then activated in the second step
        override val primaryKey = PrimaryKey(id)
    }

    private fun migrateV1toV1(migration: MigrationStepV1toV1, row: ResultRow) {
        val before = MigrationModelV1(
            type = row[Models.type],
            id = row[Models.id],
            createdAt = decode64bitMicroseconds(row[Models.createdAt]),
            lastPropsUpdatedAt = decode64bitMicroseconds(row[Models.lastPropsUpdateAt]),
            lastTransitionAt = decode64bitMicroseconds(row[Models.lastTransitionAt]),
            state = row[Models.state],
            props = gson.fromJson(row[Models.properties], mapType)
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
                it[properties] = gson.toJson(after.props)
            }
        }
    }

}

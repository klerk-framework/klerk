package dev.klerkframework.klerk.storage

import org.jetbrains.exposed.sql.Table

internal object EventLogTable : Table("\"klerk_event_log\"") {
    val sequenceNumber = long("sequence_number")
    val timestamp = long("timestamp") // microseconds since 1970
    val event = varchar("event_id", length = 100)
    val modelId = integer("model_id").index()
    val params = varchar("params", length = 100000)
    val actorIdentityType = byte("actor_identity_type")
    val actorIdentityReference = integer("actor_identity_reference").nullable()
    val actorIdentityExternalId = long("actor_identity_externalId").nullable()
    val extra = varchar("extra", length = 1000).nullable()
    override val primaryKey = PrimaryKey(sequenceNumber)
}

internal object ModelsTable : Table("\"klerk_models\"") {
    val id = integer("id").index()
    val type = varchar("type", length = 50)
    val createdAt = long("created") // microseconds since 1970
    val lastPropsUpdatedAt = long("last_props_update_at") // microseconds since 1970
    val lastStateTransitionAt = long("last_transition_at") // microseconds since 1970
    val state = varchar("state", length = 50)
    val timeTrigger = long("time_trigger").nullable() // microseconds since 1970
    val properties = varchar("props", length = 100000)
    override val primaryKey = PrimaryKey(id)
}

// possible optimization: it[relationsToThis] = ExposedBlob(toByteArray(emptyList()))  this is a possible
// optimization. BUT it is probably best to store this in another table that can be wiped and rebuilt (and if so,
// should we have one row for each relation?)
// val relationsToThis = blob("relations_to_this_model")    this is a possible optimization

internal object ModelSchemaMigrationsTable : Table("\"klerk_model_schema_migrations\"") {
    val toVersion = integer("to_version")
    val description = varchar("description", 200)
    val installedOn = varchar("installed_on", 30)
    val executionTimeMillis = integer("execution_time_ms")
}

/**
 * Blobs and strings live in the same table: Klerk never looks inside the value, so a string gains nothing from a
 * text column, and one table means one id space (an id is a safe cache key on its own) and one code path.
 */
internal object AttachedDataTable : Table("\"klerk_attached_data\"") {
    val id = integer("id")
    val value = blob("value") // a string is stored as its UTF-8 bytes
    val kind = byte("kind") // the ordinal of AttachedDataKind
    val owner = integer("owner").nullable() // the id of the owning model, null while unclaimed
    val visibility = byte("visibility") // the ordinal of AttachedDataVisibility
    val created = long("created") // microseconds since 1970
    val size = long("size") // bytes
    val hash = varchar("hash", length = 64) // SHA-256, lowercase hex
    val metadata = text("metadata").nullable() // the application's own metadata, as JSON
    val expires = long("expires").nullable() // microseconds since 1970, null once claimed by a model

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
    val claimedByJob = long("claimed_by_job").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * One row per job instance. The enum-valued columns store the ordinal of the corresponding Kotlin enum, so entries
 * may be appended to those enums but never reordered or removed.
 */
internal object JobsTable : Table("\"klerk_jobs\"") {
    val id = long("id")
    val name = varchar("name", length = 100) // the JobName, i.e. what resolves the JobType after a restart
    val cursor = text("cursor") // encoded by the job type; opaque here
    val status = byte("status") // the ordinal of JobStatus
    val priority = byte("priority") // the ordinal of JobPriority
    val agent = byte("agent") // the ordinal of JobAgent
    val ownerActorType = integer("owner_actor_type")
    val ownerActorId = integer("owner_actor_id").nullable()
    val ownerActorExternalId = long("owner_actor_external_id").nullable()
    val step = integer("step_number")
    val attempt = integer("attempt")
    val created = long("created") // microseconds since 1970
    val readyAt = long("ready_at").nullable() // microseconds since 1970
    val firstAttemptStarted = long("first_attempt_started").nullable()
    val lastAttemptStarted = long("last_attempt_started").nullable()
    val lastAttemptFinished = long("last_attempt_finished").nullable()
    val progressCompleted = integer("progress_completed").nullable()
    val progressTotal = integer("progress_total").nullable()
    val progressMessage = text("progress_message").nullable()
    val log = text("log") // JSON array of JobLogEntry
    val parent = long("parent_id").nullable()
    val root = long("root_id")
    val depth = integer("depth")
    val result = text("result").nullable()
    val failedAtCursor = text("failed_at_cursor").nullable()
    val hookCursor = text("hook_cursor").nullable()
    val hookKind = byte("hook_kind").nullable() // the ordinal of JobHookKind
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
internal object CronStateTable : Table("\"klerk_cron_state\"") {
    val scheduleId = varchar("schedule_id", length = 250)
    val lastFiredAt = long("last_fired_at") // microseconds since 1970
    override val primaryKey = PrimaryKey(scheduleId)
}

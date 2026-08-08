package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Encodes and decodes a job's cursor, for the cases where the `@Serializable` default is not enough — a legacy wire
 * format, a value the application already knows how to serialize, or a type it cannot annotate.
 *
 * Overriding [JobType.codec] replaces [JobType.cursorSerializer] entirely.
 */
public interface CursorCodec<Cursor : Any> {
    public fun encode(cursor: Cursor): String

    /** @throws Exception if [encoded] cannot be decoded. Klerk treats any throw as an unloadable cursor. */
    public fun decode(encoded: String): Cursor
}

/**
 * A managed, persisted, retried background job — a **step machine**.
 *
 * Klerk invokes [Local.step]/[Portable.step]; it does some work and returns a [JobResult]. A `Yield` says "I am not
 * done, here is my new cursor, and here is at most one command to apply on my behalf". Klerk applies the command and
 * stores the cursor **in a single transaction**, then re-queues the job. Three rules follow from that:
 *
 * 1. A job never mutates Klerk directly; it returns a command and Klerk applies it.
 * 2. At most one command per step. Three mutations means three steps.
 * 3. A step's command and the job's new cursor commit together, or not at all — so a resumed job never re-emits a
 *    command that was already applied.
 *
 * Declare the type as an `object`, give it a stable [name], and register it in `ConfigBuilder`:
 *
 * ```
 * object ImportBooks : JobType.Local<ImportCursor, Ctx, Views>() {
 *     override val name = JobName("import-books")
 *     override val priority = JobPriority.Bulk
 *
 *     override suspend fun step(args: JobStepArgs.Local<ImportCursor, Ctx, Views>): JobResult<ImportCursor> { ... }
 * }
 *
 * // in the config
 * jobs { register(ImportBooks) }
 * ```
 *
 * @param Cursor the job's persisted state between steps. Treat it as a **persisted schema**: it must be
 * `@Serializable` (or covered by a [codec]), and while instances may be in flight you may add optional fields but not
 * remove or retype existing ones. See [UnloadableJobPolicy] for what happens when a cursor no longer deserializes.
 */
public sealed class JobType<Cursor : Any, C : KlerkContext, V> {

    /** The stable identity of this job type, persisted with every instance. Changing it strands existing jobs. */
    public abstract val name: JobName

    /**
     * Whose authority this job's commands are applied with. Defaults to [JobAgent.System] — a job is configuration,
     * i.e. trusted code.
     */
    public open val agent: JobAgent get() = JobAgent.System

    /**
     * The queueing class of instances of this type, or null to inherit the priority of the command that scheduled the
     * job.
     */
    public open val priority: JobPriority? get() = JobPriority.Normal

    /** How many times a step is retried after a `Fail` before the job is dead-lettered. */
    public open val maxRetries: Int get() = 3

    /**
     * How many instances of this type may be *running* at once, or null for no limit. This caps dispatch, not
     * scheduling — capping scheduling would fail the 51st user's command for reasons they cannot perceive.
     */
    public open val maxConcurrent: Int? get() = null

    /** Aborts the job once it has taken this many steps. Null (the default) means no limit. */
    public open val maxSteps: Int? get() = null

    /** Aborts the job once this long has passed since its first attempt started. Null (the default) means no limit. */
    public open val maxDuration: Duration? get() = null

    /** The total number of descendants any one root job of this type may spawn before a step is aborted. */
    public open val maxDescendants: Int get() = 10_000

    /** How deep the spawn tree below a root job of this type may get before a step is aborted. */
    public open val maxDepth: Int get() = 8

    /**
     * How the cursor is turned into the string Klerk persists. Defaults to `kotlinx.serialization` JSON using
     * [cursorSerializer]; override to take over entirely.
     */
    public open val codec: CursorCodec<Cursor>? get() = null

    /**
     * The serializer for [Cursor]. Derived from the declared type argument by default, which works for any
     * `@Serializable` class; override it (`override val cursorSerializer = MyCursor.serializer()`) if the derivation
     * cannot see through your type, e.g. because the job type is itself generic.
     *
     * Resolved eagerly when the type is registered, so a missing `@Serializable` is a configuration error rather than
     * a surprise the first time the job runs.
     */
    public open val cursorSerializer: KSerializer<Cursor> by lazy { deriveCursorSerializer() }

    /**
     * Declares an instance of this job. Nothing is scheduled until the returned value is handed to
     * `klerk.jobs.schedule(...)`, returned from a state machine's `job(...)` executable, or declared in a step's
     * `JobResult.Yield(spawn = ...)`.
     *
     * @param cursor the job's initial state.
     * @param scheduleAt the earliest time the job may run. Null means as soon as a dispatch slot is free.
     * @param priority overrides the type's [priority] for this instance.
     */
    public fun schedule(
        cursor: Cursor,
        scheduleAt: Instant? = null,
        priority: JobPriority? = null,
    ): ScheduledJob<C, V> = ScheduledJob(this, encodeCursor(cursor), scheduleAt, priority)

    internal fun encodeCursor(cursor: Cursor): String =
        codec?.encode(cursor) ?: cursorJson.encodeToString(cursorSerializer, cursor)

    internal fun decodeCursor(encoded: String): Cursor =
        codec?.decode(encoded) ?: cursorJson.decodeFromString(cursorSerializer, encoded)

    @Suppress("UNCHECKED_CAST")
    internal fun encodeUnknownCursor(cursor: Any): String = encodeCursor(cursor as Cursor)

    /**
     * Forces the cursor codec to resolve, so that a job type with a non-serializable cursor fails at config time.
     *
     * @throws IllegalArgumentException if no serializer can be derived and no [codec] was supplied.
     */
    internal fun validateCursorCodec() {
        if (codec != null) {
            return
        }
        try {
            cursorSerializer
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Cannot serialize the cursor of the job type '${name.value}'. Annotate the cursor class with " +
                        "@Serializable, or override 'cursorSerializer' or 'codec' on the job type.",
                e
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deriveCursorSerializer(): KSerializer<Cursor> {
        val cursorType = findCursorType()
            ?: throw IllegalArgumentException(
                "Could not work out the cursor type of the job type '${name.value}'. Override 'cursorSerializer' " +
                        "(e.g. 'override val cursorSerializer = MyCursor.serializer()') or supply a 'codec'."
            )
        try {
            return serializer(cursorType) as KSerializer<Cursor>
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "There is no serializer for '$cursorType', the cursor of the job type '${name.value}'. Annotate it " +
                        "with @Serializable, or override 'cursorSerializer' or 'codec' on the job type.",
                e
            )
        }
    }

    /**
     * The declared `Cursor` type argument of the `JobType.Local`/`JobType.Portable` supertype. An `object` or a class
     * that names its cursor concretely (which is the normal case) records it in its supertype, so no annotation is
     * needed on the job type itself.
     */
    private fun findCursorType(): KType? =
        this::class.allSupertypes
            .firstOrNull { (it.classifier as? KClass<*>) in cursorBearingClasses }
            ?.arguments?.firstOrNull()?.type

    /**
     * A job that runs on the master node and may read. Use this by default.
     */
    public abstract class Local<Cursor : Any, C : KlerkContext, V> : JobType<Cursor, C, V>() {

        /** Does one step's worth of work and reports what should happen next. */
        public abstract suspend fun step(args: JobStepArgs.Local<Cursor, C, V>): JobResult<Cursor>

        /** Runs after the job has been cancelled, as a step machine over the same cursor type. */
        public open suspend fun onCancelled(args: JobEndArgs.Local<Cursor, C, V>): JobResult<Cursor> =
            JobResult.Success()

        /** Runs after the job has been dead-lettered, as a step machine over the same cursor type. */
        public open suspend fun onDeadLettered(args: JobEndArgs.Local<Cursor, C, V>): JobResult<Cursor> =
            JobResult.Success()
    }

    /**
     * A job with no [dev.klerkframework.klerk.read.Reader]: everything it needs is in its cursor.
     *
     * Writing a job as `Portable` is a promise about *where it may run*, not only about reading — a job that needs a
     * machine-local file, a JVM type from your application, or a node-local secret is [Local] even if it never reads.
     * Portable jobs will become eligible to run on remote worker nodes in a later milestone; today they run on the
     * master like everything else.
     */
    public abstract class Portable<Cursor : Any, C : KlerkContext, V> : JobType<Cursor, C, V>() {

        public abstract suspend fun step(args: JobStepArgs.Portable<Cursor, C, V>): JobResult<Cursor>

        public open suspend fun onCancelled(args: JobEndArgs.Portable<Cursor, C, V>): JobResult<Cursor> =
            JobResult.Success()

        public open suspend fun onDeadLettered(args: JobEndArgs.Portable<Cursor, C, V>): JobResult<Cursor> =
            JobResult.Success()
    }

    override fun toString(): String = name.value
}

private val cursorBearingClasses = setOf(JobType.Local::class, JobType.Portable::class)

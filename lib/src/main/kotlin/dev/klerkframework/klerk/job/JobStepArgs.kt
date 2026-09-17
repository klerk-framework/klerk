package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.read.ModelReader
import kotlin.time.Instant

/**
 * The log entries a step or an end-of-life hook can build, all stamped with the step's own [time].
 *
 * Entries are returned on the [JobResult], not written directly, so that they commit with the step.
 */
public interface JobLogging {

    /** The time this step is considered to happen at, taken from the configured clock. */
    public val time: Instant

    /** Builds a log entry stamped with this step's time. */
    public fun log(message: String, level: JobLogLevel = JobLogLevel.Info): JobLogEntry =
        JobLogEntry(time, level, message)

    /** Builds a debug log entry stamped with this step's time. */
    public fun debug(message: String): JobLogEntry = log(message, JobLogLevel.Debug)
    /** Builds an info log entry stamped with this step's time. */
    public fun info(message: String): JobLogEntry = log(message, JobLogLevel.Info)
    /** Builds a warning log entry stamped with this step's time. */
    public fun warn(message: String): JobLogEntry = log(message, JobLogLevel.Warn)
    /** Builds an error log entry stamped with this step's time. */
    public fun error(message: String): JobLogEntry = log(message, JobLogLevel.Error)
}

/**
 * Everything a step is given.
 *
 * The two variants differ only in whether a [ModelReader] is available: [Local] jobs run on the master node and may
 * read; [Portable] jobs must find everything they need in their cursor, which is what will later let them run on a
 * remote worker.
 */
public sealed class JobStepArgs<Cursor : Any, C : KlerkContext, V>(initialCancellationRequested: Boolean) :
    JobLogging {

    /**
     * Re-read on every access while the step runs, so that a long step sees a cancellation that arrives after it
     * started. A test constructing these directly gets the fixed value it passed in.
     */
    internal var cancellationProvider: () -> Boolean = { initialCancellationRequested }

    /** The job's state, as returned by the previous step (or as scheduled, on the first step). */
    public abstract val cursor: Cursor

    /**
     * The outcome of the command the previous step emitted, or null on the first step and whenever the previous step
     * emitted none.
     *
     * A [CommandResult.Failure] here is **data, not a job failure** — the model may simply have moved on while
     * the job was queued. The step still counted as completed; decide what to do and return normally.
     */
    public abstract val previousResult: CommandResult<*>?

    /** Identity and bookkeeping for the running job: id, step number, attempt, priority, ancestry. */
    public abstract val job: JobInfo

    /** The context the job runs under, produced from `systemContextProvider` or from the scheduling actor. */
    public abstract val context: C

    /**
     * What this job's children reported: every child of it that has reached a terminal status, oldest first. Empty
     * for a job that never spawned any.
     *
     * Read from the children's own rows each time a step runs, rather than accumulated on this job, so it is the
     * whole picture rather than only what finished since the previous step. After a
     * `JobResult.Yield(awaitSpawned = true)` the next step is guaranteed to see all of them, because that is what it
     * waited for.
     */
    public abstract val children: List<ChildOutcome>

    /**
     * True once someone has called `klerk.jobs.cancel(...)`.
     *
     * Cancellation takes effect at a step boundary, never mid-step — interrupting a running step would break the
     * command-plus-checkpoint atomicity everything else rests on. A step that takes a long time should therefore
     * check this as it goes and return early, cooperatively; the value changes under it the moment cancellation is
     * requested.
     */
    public val cancellationRequested: Boolean get() = cancellationProvider()

    override val time: Instant get() = context.time

    override fun toString(): String =
        "JobStepArgs.${this::class.simpleName}(job=${job.id}, step=${job.step}, cursor=$cursor)"

    /** The arguments of a [JobType.Local] step. */
    public class Local<Cursor : Any, C : KlerkContext, V>(
        override val cursor: Cursor,
        override val previousResult: CommandResult<*>?,
        override val job: JobInfo,
        override val context: C,
        /** Reads the models as they are committed right now; valid only for the duration of the step. */
        public val reader: ModelReader<C, V>,
        /**
         * The framework itself, for the subsystems a step may need — `attachedData` above all. **Not** for issuing
         * commands: return the command from the step instead, so that it commits atomically with the checkpoint.
         * Reading goes through [reader], which is already inside the step's read block.
         */
        public val klerk: Klerk<C, V>,
        override val children: List<ChildOutcome> = emptyList(),
        cancellationRequested: Boolean = false,
    ) : JobStepArgs<Cursor, C, V>(cancellationRequested)

    /** The arguments of a [JobType.Portable] step. No [ModelReader] — everything the step needs is in the cursor. */
    public class Portable<Cursor : Any, C : KlerkContext, V>(
        override val cursor: Cursor,
        override val previousResult: CommandResult<*>?,
        override val job: JobInfo,
        override val context: C,
        override val children: List<ChildOutcome> = emptyList(),
        cancellationRequested: Boolean = false,
    ) : JobStepArgs<Cursor, C, V>(cancellationRequested)
}

/**
 * Everything an end-of-life hook step is given.
 *
 * Hooks are step machines in their own right — same `Yield`/`Success`/`Fail`/`Abort` vocabulary, same
 * one-command-per-step rule, same atomic checkpointing — so compensation that needs three mutations takes three steps
 * and survives a restart halfway through.
 *
 * Use [failedAtCursor], not the event log, to decide what needs undoing: the cursor is a record your own code
 * designed, while the event log is authorization-gated and may have been erased by retention rules. The hook's own
 * [cursor] is checkpointed separately, so unwinding never destroys [failedAtCursor].
 */
public sealed class JobEndArgs<Cursor : Any, C : KlerkContext, V> : JobLogging {

    /** The hook's own state, as returned by the previous hook step (it starts out equal to [failedAtCursor]). */
    public abstract val cursor: Cursor

    /** The job's cursor at the moment it died, read-only for the life of the hook. */
    public abstract val failedAtCursor: Cursor

    /** Why the job is ending: the `Fail`/`Abort` reason, or the cancellation reason. */
    public abstract val reason: String

    /** The outcome of the command the previous hook step emitted, or null if there was none. */
    public abstract val previousResult: CommandResult<*>?

    /** Identity and bookkeeping for the ending job: id, step number, attempt, priority, ancestry. */
    public abstract val job: JobInfo

    /** The context the hook runs under, produced from `systemContextProvider` or from the scheduling actor. */
    public abstract val context: C

    /** What the job's children reported: every child that has reached a terminal status, oldest first. */
    public abstract val children: List<ChildOutcome>

    /** Which hook is running. */
    public val kind: JobHookKind get() = requireNotNull(job.hook) { "A JobEndArgs always belongs to a hook" }

    override val time: Instant get() = context.time

    override fun toString(): String =
        "JobEndArgs.${this::class.simpleName}(job=${job.id}, cursor=$cursor, reason=$reason)"

    /** The arguments of a [JobType.Local] hook step. */
    public class Local<Cursor : Any, C : KlerkContext, V>(
        override val cursor: Cursor,
        override val failedAtCursor: Cursor,
        override val reason: String,
        override val previousResult: CommandResult<*>?,
        override val job: JobInfo,
        override val context: C,
        /** Reads the models as they are committed right now; valid only for the duration of the step. */
        public val reader: ModelReader<C, V>,
        /** As on [JobStepArgs.Local.klerk]. */
        public val klerk: Klerk<C, V>,
        override val children: List<ChildOutcome> = emptyList(),
    ) : JobEndArgs<Cursor, C, V>()

    /** The arguments of a [JobType.Portable] hook step. */
    public class Portable<Cursor : Any, C : KlerkContext, V>(
        override val cursor: Cursor,
        override val failedAtCursor: Cursor,
        override val reason: String,
        override val previousResult: CommandResult<*>?,
        override val job: JobInfo,
        override val context: C,
        override val children: List<ChildOutcome> = emptyList(),
    ) : JobEndArgs<Cursor, C, V>()
}

package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions

/**
 * What one step of a job (or of an end-of-life hook) reports back to Klerk.
 *
 * A step never mutates Klerk itself: it *returns* at most one command, and Klerk applies that command together with
 * the new cursor in a single transaction. That is what makes a resumed job unable to re-emit a command it already
 * applied, and it is the property everything else in the job module rests on.
 *
 * An uncaught exception thrown by a step is treated as [Fail].
 */
public sealed interface JobResult<out Cursor> {

    /**
     * Diagnostic entries to append to the job's log, committed with the step. Build them with the helpers on
     * [JobStepArgs] / [JobEndArgs] (`args.info("...")`, `args.warn("...")`), which stamp them with the step's time.
     */
    public val log: List<JobLogEntry>

    /**
     * Not done yet.
     *
     * The [command] (if any), the spawned children, the new [cursor] and the [progress] all commit together, and the
     * job is then re-queued at the tail of its priority class — or moved to [JobStatus.Waiting] if [awaitSpawned].
     *
     * @param cursor the job's state for the next step. Serialized by Klerk; treat its type as a persisted schema.
     * @param command at most one command, applied on the job's behalf. Its outcome is handed to the next step as
     * `previousResult`.
     * @param options processing options for [command], e.g. `ProcessingOptions(CommandToken.requireUnmodifiedModel(id))`
     * to make the step conditional on the model not having changed. Defaults to a plain token.
     * @param spawn children to schedule. Declared rather than scheduled imperatively so that a step which throws after
     * scheduling cannot spawn them twice on the retry.
     * @param awaitSpawned if true the job moves to [JobStatus.Waiting] and is re-queued once every job in [spawn] —
     * and every job spawned in earlier steps that is still outstanding — has reached a terminal status. The outcomes
     * arrive as `args.children`.
     * @param progress how far the job has got, for display.
     */
    public class Yield<Cursor>(
        public val cursor: Cursor,
        public val command: Command<*, *>? = null,
        public val options: ProcessingOptions? = null,
        public val spawn: List<ScheduledJob<*, *>> = emptyList(),
        public val awaitSpawned: Boolean = false,
        public val progress: JobProgress? = null,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Cursor> {
        init {
            require(!awaitSpawned || spawn.isNotEmpty()) {
                "awaitSpawned = true but nothing was spawned, and no earlier step can be awaited retroactively. " +
                        "Either spawn children or yield without awaiting."
            }
        }
    }

    /**
     * Done. The job is not retried.
     *
     * @param command an optional final command, committed together with the terminal status. Convenient, but you never
     * get to see its result — [Yield] instead if the outcome matters.
     * @param result a value handed to the parent job (if any) as [ChildOutcome.result]. Encode it yourself; Klerk
     * stores it as an opaque string.
     */
    public class Success(
        public val command: Command<*, *>? = null,
        public val options: ProcessingOptions? = null,
        public val progress: JobProgress? = null,
        public val result: String? = null,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Nothing>

    /**
     * This attempt failed, but another might work — the API timed out, the host was unreachable.
     *
     * Retried with exponential backoff (base 3 s) until the type's `maxRetries` is reached, then dead-lettered.
     */
    public class Fail(
        public val reason: String,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Nothing>

    /**
     * This will never work — the account no longer exists, the file is malformed. Straight to the dead letter with no
     * retries.
     *
     * @param runHook whether to run `onDeadLettered`. Set it to false when aborting *is* the correct end state and
     * there is deliberately nothing to compensate.
     */
    public class Abort(
        public val reason: String,
        public val runHook: Boolean = true,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Nothing>
}

package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext
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
public sealed interface JobResult<out Cursor, out C : KlerkContext, out V> {

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
     */
    public data class Yield<Cursor, C : KlerkContext, V>(
        /** The job's state for the next step. Serialized by Klerk; treat its type as a persisted schema. */
        public val cursor: Cursor,
        /**
         * At most one command, applied on the job's behalf. Its outcome is handed to the next step as `previousResult`.
         */
        public val command: Command<*, *>? = null,
        /**
         * Processing options for [command], e.g. `ProcessingOptions(CommandToken.requireUnmodifiedModel(id))` to make
         * the step conditional on the model not having changed. Defaults to a plain token.
         */
        public val options: ProcessingOptions? = null,
        /**
         * Children to schedule. Declared rather than scheduled imperatively so that a step which throws after
         * scheduling cannot spawn them twice on the retry.
         */
        public val spawn: List<DeclaredJob<C, V>> = emptyList(),
        /**
         * If true, the job moves to [JobStatus.Waiting] and is re-queued once every job in [spawn] — and every job
         * spawned in earlier steps that is still outstanding — has reached a terminal status. The outcomes arrive as
         * `args.children`.
         */
        public val awaitSpawned: Boolean = false,
        /** How far the job has got, for display. */
        public val progress: JobProgress? = null,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Cursor, C, V> {
        init {
            require(!awaitSpawned || spawn.isNotEmpty()) {
                "awaitSpawned = true but nothing was spawned, and no earlier step can be awaited retroactively. " +
                    "Either spawn children or yield without awaiting."
            }
        }
    }

    /**
     * Done. The job is not retried.
     */
    public data class Success(
        /**
         * An optional final command, committed together with the terminal status. Convenient, but you never get to see
         * its result — [Yield] instead if the outcome matters.
         */
        public val command: Command<*, *>? = null,
        /** Processing options for [command]. */
        public val options: ProcessingOptions? = null,
        /** How far the job got, for display. */
        public val progress: JobProgress? = null,
        /**
         * A value handed to the parent job (if any) as [ChildOutcome.result]. Klerk stores it as a string: encode it
         * with [encodeJobResult] and the parent decodes it with [ChildOutcome.resultAs].
         */
        public val result: String? = null,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Nothing, Nothing, Nothing>

    /**
     * This attempt failed, but another might work — the API timed out, the host was unreachable.
     *
     * Retried with exponential backoff (base 3 s) until the type's `maxRetries` is reached, then dead-lettered.
     */
    public data class Fail(
        /** Why the attempt failed, for the job's log. */
        public val reason: String,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Nothing, Nothing, Nothing>

    /**
     * This will never work — the account no longer exists, the file is malformed. Straight to the dead letter with no
     * retries.
     */
    public data class Abort(
        /** Why the job gave up, for a human looking at the dead letter. */
        public val reason: String,
        /**
         * Whether to run `onDeadLettered`. Set it to false when aborting *is* the correct end state and there is
         * deliberately nothing to compensate.
         */
        public val runHook: Boolean = true,
        override val log: List<JobLogEntry> = emptyList(),
    ) : JobResult<Nothing, Nothing, Nothing>
}

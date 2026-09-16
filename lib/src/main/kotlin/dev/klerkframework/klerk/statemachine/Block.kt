package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.statemachine.executables.*

/** One thing a block does when it runs. [A] is the args type of the block it belongs to. */
internal interface Executable<T : Any, A, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: A) -> Boolean)?
}

/** A pending fire-and-forget job produced by `unmanagedJob`, to be run after the command commits. */
internal class UnmanagedJob(internal val f: () -> Unit, val function: Function<*>, val description: String)

/** A block of a state machine: what happens when an event arrives, or when a state is entered or left. */
@SpecificationMarker
public sealed class Block<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val name: String,
    internal val type: BlockType,
) {

    internal class VoidLifecycleBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
        name: String,
        type: BlockType,
    ) : Block<T, ModelStates, C, V>(name, type)

    /**
     * What every block can do, whatever triggered it. [A] is the block's args type: [VoidEventArgs] in a void
     * `onEvent`, [InstanceEventArgs] in an instance `onEvent`, [LifecycleArgs] in `onEnter`/`onExit`/`after`/`atTime`.
     */
    public sealed class ExecutableBlock<
        T : Any,
        A : RuleArgs<C, V>,
        ModelStates : Enum<*>,
        C : KlerkContext,
        V,
    > protected constructor(
        name: String,
        type: BlockType,
    ) : Block<T, ModelStates, C, V>(name, type) {

        internal val executables = mutableListOf<Executable<T, A, C, V>>()

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction as the triggering
         * command — e.g. cascading a deletion to related models.
         */
        public fun commands(
            function: (args: A) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: A) -> Boolean)? = null,
        ) {
            executables.add(CreateCommands(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with
         * `MyJobType.declare(cursor)`. They are persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: A) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: A) -> Boolean)? = null,
        ) {
            executables.add(ScheduleJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: A) -> DeclaredJob<C, V>,
            onCondition: ((args: A) -> Boolean)? = null,
        ) {
            executables.add(ScheduleJob(function, onCondition))
        }

        /**
         * A "fire and forget" job that will be executed asynchronously.
         *
         * Note that exceptions thrown by the job will be pretty much ignored, so make sure that the job
         * catches all exceptions.
         *
         * The job will be executed on the main node in the background after the command has been processed. This may
         * have an impact on system performance, so consider a normal job instead if you need to do anything
         * non-trivial.
         */
        public fun unmanagedJob(
            function: (args: A) -> Unit,
            onCondition: ((args: A) -> Boolean)? = null,
        ) {
            executables.add(RunUnmanagedJob(function, onCondition))
        }

        override fun toString(): String = name
    }

    /** What a block can do when there is a model to act on, i.e. everywhere except a void `onEvent`. */
    public sealed class ModelBlock<
        T : Any,
        A : ModelArgs<T, C, V>,
        ModelStates : Enum<*>,
        C : KlerkContext,
        V,
    > protected constructor(
        name: String,
        type: BlockType,
    ) : ExecutableBlock<T, A, ModelStates, C, V>(name, type) {

        /**
         * Moves the model to [targetState] once this block finishes. At most one transition per block (`transitionTo`
         * or `transitionWhen`) — a second call throws `IllegalArgumentException`. Rejected at startup if
         * [targetState] equals the state this block belongs to.
         */
        public fun transitionTo(
            targetState: ModelStates,
            onCondition: ((args: A) -> Boolean)? = null,
        ) {
            require(executables.none { it is Transition<*, *, *, *, *> }) { "A block can only have one transition" }
            executables.add(Transition(targetState, onCondition))
        }

        /**
         * Transitions to the target of the first `on` whose decision returns `true`, in declaration order, or to
         * `otherwise` if none match (does nothing when no `otherwise` was given).
         *
         * ```kotlin
         * transitionWhen {
         *     on(::isAnImpostor, Amateur)
         *     on(::hasTalent, Established)
         *     otherwise(Improving)
         * }
         * ```
         */
        public fun transitionWhen(init: TransitionBranches<A, ModelStates>.() -> Unit) {
            val branches = TransitionBranches<A, ModelStates>()
            branches.init()
            executables.add(TransitionWhen(branches.branches, branches.otherwise))
        }

        /**
         * Deletes the model. At most one `delete` per block — a second call throws `IllegalArgumentException`.
         */
        public fun delete(onCondition: ((args: A) -> Boolean)? = null) {
            require(executables.none { it is DeleteModel<*, *, *, *> }) { "A block can only have one delete" }
            executables.add(DeleteModel(onCondition))
        }

        /**
         * Replaces the model's properties with whatever [function] returns.
         */
        public fun update(
            function: (args: A) -> T,
            onCondition: ((args: A) -> Boolean)? = null,
        ) {
            executables.add(UpdateModel(function, onCondition))
        }
    }

    /** The block of a void `onEvent`, i.e. what happens when a model is created. */
    public class VoidEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
        name: String,
        type: BlockType,
    ) : ExecutableBlock<T, VoidEventArgs<T, P, C, V>, ModelStates, C, V>(name, type) {

        /**
         * Builds the new model's properties by calling [function] and puts it directly into [initialState]. This is
         * the only way to create a model — there is no `createModel` outside a state machine's `voidState` block
         * (aside from the `klerk.unsafe.create` escape hatch).
         */
        public fun createModel(
            initialState: ModelStates,
            function: (args: VoidEventArgs<T, P, C, V>) -> T,
            onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)? = null,
        ) {
            executables.add(CreateModel(initialState, function, onCondition))
        }
    }

    /** The block of an `onEnter`, `onExit`, `after` or `atTime`, i.e. what happens without an event of its own. */
    public class InstanceLifecycleBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
        name: String,
        type: BlockType,
    ) : ModelBlock<T, LifecycleArgs<T, C, V>, ModelStates, C, V>(name, type)

    /** The block of an instance `onEvent`, i.e. what happens when an event is received for an existing model. */
    public class InstanceEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
        name: String,
        type: BlockType,
    ) : ModelBlock<T, InstanceEventArgs<T, P, C, V>, ModelStates, C, V>(name, type)
}

/** Which kind of block an executable belongs to: `onEnter`, `onExit`, an `onEvent` handler, or a time trigger. */
internal enum class BlockType {
    Enter,
    Exit,
    Event,
    Time
}


/**
 * The branches of a `transitionWhen { }`. Each [on] is evaluated in declaration order, and the model transitions to
 * the target of the first decision that returns `true`.
 */
@SpecificationMarker
public class TransitionBranches<Args, ModelStates : Enum<*>> internal constructor() {

    internal val branches = LinkedHashMap<(Args) -> Boolean, ModelStates>()
    internal var otherwise: ModelStates? = null

    /**
     * Transitions to [targetState] when [decision] returns true. Must be a named function reference, like every other
     * rule in a specification.
     */
    public fun on(decision: (Args) -> Boolean, targetState: ModelStates) {
        branches[decision] = targetState
    }

    /** Transitions to [targetState] when no [on] matched. Optional: without it, nothing happens when none match. */
    public fun otherwise(targetState: ModelStates) {
        otherwise = targetState
    }
}

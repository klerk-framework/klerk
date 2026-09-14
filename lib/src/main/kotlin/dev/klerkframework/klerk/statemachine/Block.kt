package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.statemachine.executables.*

internal interface VoidEventExecutable<T : Any, P, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: VoidEventArgs<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)?
}

internal interface InstanceLifecycleExecutable<T : Any, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: LifecycleArgs<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)?
}

internal interface InstanceEventExecutable<T : Any, P, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: InstanceEventArgs<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)?
}

/** A pending fire-and-forget job produced by [Block.VoidEventBlock.unmanagedJob] / [Block.InstanceLifecycleBlock.unmanagedJob] / [Block.InstanceEventBlock.unmanagedJob], to be run after the command commits. */
public class UnmanagedJob internal constructor(internal val f: () -> Unit, public val description: String)

@SpecificationMarker
public sealed class Block<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val name: String,
    internal val type: BlockType
) {

    internal class VoidLifecycleBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
        name: String,
        type: BlockType
    ) :
        Block<T, ModelStates, C, V>(name, type) {
    }

    public class VoidEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(name: String, type: BlockType) :
        Block<T, ModelStates, C, V>(name, type) {
        internal val executables = mutableListOf<VoidEventExecutable<T, P, C, V>>()

        /**
         * Builds the new model's properties by calling [function] and puts it directly into [initialState]. This is
         * the only way to create a model — there is no `createModel` outside a state machine's `voidState` block
         * (aside from the `unsafeCreate` escape hatch on `KlerkModels`).
         */
        public fun createModel(
            initialState: ModelStates,
            function: (args: VoidEventArgs<T, P, C, V>) -> T,
            onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventCreateModel(initialState, function, onCondition))
        }

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction as the triggering
         * command — e.g. cascading a creation into related models.
         */
        public fun commands(
            function: (args: VoidEventArgs<T, P, C, V>) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventCreateEvents(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with `MyJobType.declare(cursor)`.
         * They are persisted in this command's own transaction, so a failing command schedules nothing. See
         * [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: VoidEventArgs<T, P, C, V>) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: VoidEventArgs<T, P, C, V>) -> DeclaredJob<C, V>,
            onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventJob(function, onCondition))
        }

        /**
         * A "fire and forget" job that will be executed asynchronously.
         *
         * Note that exceptions thrown by the job will be pretty much ignored, so make sure that the job
         * catches all exceptions.
         *
         * The job will be executed on the main node in the background after the command has been processed. This may have an impact on
         * system performance, so consider a normal job instead if you need to do anything non-trivial.
         */
        public fun unmanagedJob(
            function: (args: VoidEventArgs<T, P, C, V>) -> Unit,
            onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventUnmanagedJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }
    }

    public class InstanceLifecycleBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
        name: String,
        type: BlockType,
    ) :
        Block<T, ModelStates, C, V>(name, type) {

        internal val executables = mutableListOf<InstanceLifecycleExecutable<T, C, V>>()

        /**
         * Moves the model to [targetState] once this block finishes. At most one transition per block (`transitionTo`
         * or `transitionWhen`) — a second call throws `IllegalArgumentException`. Rejected at startup if
         * [targetState] equals the state this block belongs to.
         */
        public fun transitionTo(
            targetState: ModelStates,
            onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null
        ) {
            require(executables.none { it is InstanceLifecycleTransition<T, *, C, V> }) { "A block can only have one transition" }
            executables.add(InstanceLifecycleTransition(targetState, onCondition))
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
        public fun transitionWhen(init: TransitionBranches<LifecycleArgs<T, C, V>, ModelStates>.() -> Unit) {
            val branches = TransitionBranches<LifecycleArgs<T, C, V>, ModelStates>()
            branches.init()
            executables.add(InstanceLifecycleTransitionWhen(branches.branches, branches.otherwise))
        }

        /**
         * Deletes the model. At most one `delete` per block — a second call throws `IllegalArgumentException`.
         */
        public fun delete(onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null) {
            require(executables.none { it is InstanceLifecycleDelete<*, C, V> }) { "A block can only have one delete" }
            executables.add(InstanceLifecycleDelete(onCondition))
        }

        /**
         * Replaces the model's properties with whatever [function] returns.
         */
        public fun update(
            function: (args: LifecycleArgs<T, C, V>) -> T,
            onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceLifecycleUpdateModel(function, onCondition))
        }

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction — e.g. cascading
         * a deletion to related models.
         */
        public fun commands(
            function: (args: LifecycleArgs<T, C, V>) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceLifecycleCreateEvents(function, onCondition))
        }

        /**
         * A "fire and forget" job that will be executed asynchronously.
         *
         * Note that exceptions thrown by the job will be pretty much ignored, so make sure that the job
         * catches all exceptions.
         *
         * The job will be executed on the main node in the background after the command has been processed. This may have an impact on
         * system performance, so consider a normal job instead if you need to do anything non-trivial.
         */
        public fun unmanagedJob(
            function: (args: LifecycleArgs<T, C, V>) -> Unit,
            onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceLifecycleUnmanagedJob(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with `MyJobType.declare(cursor)`.
         * They are persisted in this command's own transaction, so a failing command schedules nothing. See
         * [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: LifecycleArgs<T, C, V>) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceLifecycleJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: LifecycleArgs<T, C, V>) -> DeclaredJob<C, V>,
            onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceLifecycleJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }

    }

    public class InstanceEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
        name: String,
        type: BlockType,
    ) :
        Block<T, ModelStates, C, V>(name, type) {
        internal val executables = mutableListOf<InstanceEventExecutable<T, P, C, V>>()

        /**
         * Moves the model to [targetState] once this block finishes. At most one transition per block (`transitionTo`
         * or `transitionWhen`) — a second call throws `IllegalArgumentException`. Rejected at startup if
         * [targetState] equals the state this block belongs to.
         */
        public fun transitionTo(
            targetState: ModelStates,
            onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            require(executables.none { it is InstanceEventTransition<T, P, *, C, V> }) { "A block can only have one transition" }
            executables.add(InstanceEventTransition(targetState, onCondition))
        }

        /**
         * Transitions to the target of the first `on` whose decision returns `true`, in declaration order, or to
         * `otherwise` if none match (does nothing when no `otherwise` was given).
         *
         * ```kotlin
         * transitionWhen {
         *     on(::isOverdue, Overdue)
         *     otherwise(Active)
         * }
         * ```
         */
        public fun transitionWhen(init: TransitionBranches<InstanceEventArgs<T, P, C, V>, ModelStates>.() -> Unit) {
            val branches = TransitionBranches<InstanceEventArgs<T, P, C, V>, ModelStates>()
            branches.init()
            executables.add(InstanceEventTransitionWhen(branches.branches, branches.otherwise))
        }

        /**
         * Deletes the model. At most one `delete` per block — a second call throws `IllegalArgumentException`.
         */
        public fun delete(onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null) {
            require(executables.none { it is InstanceEventDelete<T, P, C, V> }) { "A block can only have one delete" }
            executables.add(InstanceEventDelete(onCondition))
        }

        /**
         * Replaces the model's properties with whatever [function] returns.
         */
        public fun update(
            function: (args: InstanceEventArgs<T, P, C, V>) -> T,
            onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventUpdateModel(function, onCondition))
        }

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction — e.g. cascading
         * this event to related models.
         */
        public fun commands(
            function: (args: InstanceEventArgs<T, P, C, V>) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventCreateEvents(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with `MyJobType.declare(cursor)`.
         * They are persisted in this command's own transaction, so a failing command schedules nothing. See
         * [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: InstanceEventArgs<T, P, C, V>) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: InstanceEventArgs<T, P, C, V>) -> DeclaredJob<C, V>,
            onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventJob(function, onCondition))
        }

        /**
         * A "fire and forget" job that will be executed asynchronously.
         *
         * Note that exceptions thrown by the job will be pretty much ignored, so make sure that the job
         * catches all exceptions.
         *
         * The job will be executed on the main node in the background after the command has been processed. This may have an impact on
         * system performance, so consider a normal job instead if you need to do anything non-trivial.
         */
        public fun unmanagedJob(
            function: (args: InstanceEventArgs<T, P, C, V>) -> Unit,
            onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventUnmanagedJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }
    }
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

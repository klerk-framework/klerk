package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.statemachine.executables.*

internal interface VoidEventExecutable<T : Any, P, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
}

internal interface InstanceNonEventExecutable<T : Any, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
}

internal interface InstanceEventExecutable<T : Any, P, C : KlerkContext, V> {

    fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
}

/** A pending fire-and-forget job produced by [Block.VoidEventBlock.unmanagedJob] / [Block.InstanceNonEventBlock.unmanagedJob] / [Block.InstanceEventBlock.unmanagedJob], to be run after the command commits. */
public class UnmanagedJob internal constructor(internal val f: () -> Unit, public val description: String)

@SpecificationMarker
public sealed class Block<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val name: String,
    internal val type: BlockType
) {

    internal class VoidNonEventBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
        name: String,
        type: BlockType
    ) :
        Block<T, ModelStates, C, V>(name, type) {
    }

    public class VoidEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, type: BlockType) :
        Block<T, ModelStates, C, V>(name, type) {
        internal val executables = mutableListOf<VoidEventExecutable<T, P, C, V>>()

        /**
         * Builds the new model's properties by calling [function] and puts it directly into [initialState]. This is
         * the only way to create a model — there is no `createModel` outside a state machine's `voidState` block
         * (aside from the `unsafeCreate` escape hatch on `KlerkModels`).
         */
        public fun createModel(
            initialState: ModelStates,
            function: (args: ArgForVoidEvent<T, P, C, V>) -> T,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventCreateModel(initialState, function, onCondition))
        }

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction as the triggering
         * command — e.g. cascading a creation into related models.
         */
        public fun createCommands(
            function: (args: ArgForVoidEvent<T, P, C, V>) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventCreateEvents(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with `MyJobType.declare(cursor)`.
         * They are persisted in this command's own transaction, so a failing command schedules nothing. See
         * [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: ArgForVoidEvent<T, P, C, V>) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: ArgForVoidEvent<T, P, C, V>) -> DeclaredJob<C, V>,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
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
            function: (args: ArgForVoidEvent<T, P, C, V>) -> Unit,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventUnmanagedJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }
    }

    public class InstanceNonEventBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
        name: String,
        type: BlockType
    ) :
        Block<T, ModelStates, C, V>(name, type) {

        internal val executables = mutableListOf<InstanceNonEventExecutable<T, C, V>>()

        /**
         * Moves the model to [targetState] once this block finishes. At most one transition per block (`transitionTo`
         * or `transitionWhen`) — a second call throws `IllegalArgumentException`. Rejected at startup if
         * [targetState] equals the state this block belongs to.
         */
        public fun transitionTo(
            targetState: ModelStates,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            require(executables.none { it is InstanceNonEventTransition<T, *, C, V> }) { "A block can only have one transition" }
            executables.add(InstanceNonEventTransition(targetState, onCondition))
        }

        /**
         * Evaluates each key in [branches], in iteration order, and transitions to the first value whose key returns
         * `true`; transitions to [otherwise] if none match (does nothing if [otherwise] is null and none match).
         */
        public fun transitionWhen(
            branches: LinkedHashMap<(args: ArgForInstanceNonEvent<T, C, V>) -> Boolean, ModelStates>,
            otherwise: ModelStates? = null
        ) {
            executables.add(InstanceNonEventTransitionWhen(branches, otherwise))
        }

        /**
         * Deletes the model. At most one `delete` per block — a second call throws `IllegalArgumentException`.
         */
        public fun delete(onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null) {
            require(executables.none { it is InstanceNonEventDelete<*, C, V> }) { "A block can only have one delete" }
            executables.add(InstanceNonEventDelete(onCondition))
        }

        /**
         * Replaces the model's properties with whatever [function] returns.
         */
        public fun update(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> T,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventUpdateModel(function, onCondition))
        }

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction — e.g. cascading
         * a deletion to related models.
         */
        public fun createCommands(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventCreateEvents(function, onCondition))
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
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> Unit,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventUnmanagedJob(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with `MyJobType.declare(cursor)`.
         * They are persisted in this command's own transaction, so a failing command schedules nothing. See
         * [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> DeclaredJob<C, V>,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }

    }

    public class InstanceEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V>(
        name: String,
        type: BlockType
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
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            require(executables.none { it is InstanceEventTransition<T, P, *, C, V> }) { "A block can only have one transition" }
            executables.add(InstanceEventTransition(targetState, onCondition))
        }

        /**
         * Evaluates each key in [branches], in iteration order, and transitions to the first value whose key returns
         * `true`; transitions to [otherwise] if none match (does nothing if [otherwise] is null and none match).
         */
        public fun transitionWhen(
            branches: LinkedHashMap<(args: ArgForInstanceEvent<T, P, C, V>) -> Boolean, ModelStates>,
            otherwise: ModelStates? = null
        ) {
            // TODO: check that target != current state
            executables.add(InstanceEventTransitionWhen(branches, otherwise))
        }

        /**
         * Deletes the model. At most one `delete` per block — a second call throws `IllegalArgumentException`.
         */
        public fun delete(onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null) {
            require(executables.none { it is InstanceEventDelete<T, P, C, V> }) { "A block can only have one delete" }
            executables.add(InstanceEventDelete(onCondition))
        }

        /**
         * Replaces the model's properties with whatever [function] returns.
         */
        public fun update(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> T,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventUpdateModel(function, onCondition))
        }

        /**
         * Returns commands produced by [function] to be submitted as part of the same transaction — e.g. cascading
         * this event to related models.
         */
        public fun createCommands(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> List<Command<out Any, out Any?>>,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventCreateEvents(function, onCondition))
        }

        /**
         * Schedules managed background work: [function] returns the jobs to schedule, built with `MyJobType.declare(cursor)`.
         * They are persisted in this command's own transaction, so a failing command schedules nothing. See
         * [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun jobs(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> List<DeclaredJob<C, V>>,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventJobs(function, onCondition))
        }

        /**
         * Schedules a single piece of managed background work: [function] returns the job to schedule, built with
         * `MyJobType.declare(cursor)`. It is persisted in this command's own transaction, so a failing command
         * schedules nothing. See [dev.klerkframework.klerk.job.JobType] for the distinction from [unmanagedJob].
         */
        public fun job(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> DeclaredJob<C, V>,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
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
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> Unit,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventUnmanagedJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }
    }
}

/** Which kind of block an executable belongs to: `onEnter`, `onExit`, an `onEvent` handler, or a time trigger. */
public enum class BlockType {
    Enter,
    Exit,
    Event,
    Time
}


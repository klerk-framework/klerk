package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.statemachine.executables.*

internal interface VoidEventExecutable<T : Any, P, C : KlerkContext, V> {

    fun <Primary:Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
}

internal interface InstanceNonEventExecutable<T : Any, C : KlerkContext, V> {

    fun <Primary:Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
}

internal interface InstanceEventExecutable<T : Any, P, C : KlerkContext, V> {

    fun <Primary:Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V>

    val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
}

public  data class GeneralAction(public val f: () -> Unit, public val description: String)

@ConfigMarker
public sealed class Block<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(internal val name: String, internal val type: BlockType) {

    internal class VoidNonEventBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, type: BlockType) :
        Block<T, ModelStates, C, V>(name, type) {
    }

    public class VoidEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, type: BlockType) :
        Block<T, ModelStates, C, V>(name, type) {
        internal val executables = mutableListOf<VoidEventExecutable<T, P, C, V>>()

        public fun createModel(
            initialState: ModelStates,
            function: (args: ArgForVoidEvent<T, P, C, V>) -> T,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventCreateModel(initialState, function, onCondition))
        }

        public fun createCommands(
            function: (args: ArgForVoidEvent<T, P, C, V>) -> List<Command<out Any, out Any>>,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventCreateEvents(function, onCondition))
        }

        public fun job(
            function: (args: ArgForVoidEvent<T, P, C, V>) -> List<Job<C, V>>,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventJob(function, onCondition))
        }

        /**
         * A "fire and forget" action that will be executed asynchronously.
         *
         * Note that exceptions thrown by the action will be pretty much ignored, so make sure that the action
         * catches all exceptions.
         *
         * The action will be executed on the master node using the default dispatcher. This may have an impact on
         * system performance, so consider a job instead if you need to do anything non-trivial.
         */
        public fun action(
            function: (args: ArgForVoidEvent<T, P, C, V>) -> Unit,
            onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(VoidEventAction(function, onCondition))
        }

        override fun toString(): String {
            return name
        }
    }

    public class InstanceNonEventBlock<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, type: BlockType) :
        Block<T, ModelStates, C, V>(name, type) {

        internal val executables = mutableListOf<InstanceNonEventExecutable<T, C, V>>()

        public fun transitionTo(
            targetState: ModelStates,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            require(executables.none { it is InstanceNonEventTransition<T, *, C, V> }) { "A block can only have one transition" }
            executables.add(InstanceNonEventTransition(targetState, onCondition))
        }

        public fun transitionWhen(
            branches: LinkedHashMap<(args: ArgForInstanceNonEvent<T, C, V>) -> Boolean, ModelStates>,
            otherwise: ModelStates? = null
        ) {
            executables.add(InstanceNonEventTransitionWhen(branches, otherwise))
        }

        public fun delete(onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null) {
            require(executables.none { it is InstanceNonEventDelete<*, C, V> }) { "A block can only have one delete" }
            executables.add(InstanceNonEventDelete(onCondition))
        }

        public fun update(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> T,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventUpdateModel(function, onCondition))
        }

        public fun createCommands(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> List<Command<out Any, out Any>>,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventCreateEvents(function, onCondition))
        }

        /**
         * A "fire and forget" action that will be executed asynchronously.
         *
         * Note that exceptions thrown by the action will be pretty much ignored, so make sure that the action
         * catches all exceptions.
         *
         * The action will be executed on the master node using the default dispatcher. This may have an impact on
         * system performance, so consider a job instead if you need to do anything non-trivial.
         */
        public fun action(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> Unit,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventAction(function, onCondition))
        }

        public fun job(
            function: (args: ArgForInstanceNonEvent<T, C, V>) -> List<Job<C, V>>,
            onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceNonEventJob(function, onCondition))
        }

        override fun toString(): String {
            return name
        }

    }

    public class InstanceEventBlock<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, type: BlockType) :
        Block<T, ModelStates, C, V>(name, type) {
        internal val executables = mutableListOf<InstanceEventExecutable<T, P, C, V>>()

        public fun transitionTo(
            targetState: ModelStates,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            require(executables.none { it is InstanceEventTransition<T, P, *, C, V> }) { "A block can only have one transition" }
            executables.add(InstanceEventTransition(targetState, onCondition))
        }

        public fun transitionWhen(
            branches: LinkedHashMap<(args: ArgForInstanceEvent<T, P, C, V>) -> Boolean, ModelStates>,
            otherwise: ModelStates? = null
        ) {
            // TODO: check that target != current state
            executables.add(InstanceEventTransitionWhen(branches, otherwise))
        }

        public fun delete(onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null) {
            require(executables.none { it is InstanceEventDelete<T, P, C, V> }) { "A block can only have one delete" }
            executables.add(InstanceEventDelete(onCondition))
        }

        public fun update(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> T,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventUpdateModel(function, onCondition))
        }

        public fun createCommands(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> List<Command<out Any, out Any>>,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventCreateEvents(function, onCondition))
        }

        public fun job(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> List<Job<C, V>>,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventJob(function, onCondition))
        }

        /**
         * A "fire and forget" action that will be executed asynchronously.
         *
         * Note that exceptions thrown by the action will be pretty much ignored, so make sure that the action
         * catches all exceptions.
         *
         * The action will be executed on the master node using the default dispatcher. This may have an impact on
         * system performance, so consider a job instead if you need to do anything non-trivial.
         */
        public fun action(
            function: (args: ArgForInstanceEvent<T, P, C, V>) -> Unit,
            onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)? = null
        ) {
            executables.add(InstanceEventAction(function, onCondition))
        }

        override fun toString(): String {
            return name
        }
    }
}

public enum class BlockType {
    Enter,
    Exit,
    Event,
    Time
}


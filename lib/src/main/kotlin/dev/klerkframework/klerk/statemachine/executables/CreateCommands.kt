package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.extractNameFromFunction

import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import dev.klerkframework.klerk.statemachine.VoidEventExecutable

internal class VoidEventCreateEvents<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForVoidEvent<T, P, C, V>) -> List<Command<out Any, out Any>>,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f, false)}'"),
        )

}

internal class InstanceNonEventCreateEvents<T : Any, C : KlerkContext, V>(
    val f: (args: ArgForInstanceNonEvent<T, C, V>) -> List<Command<out Any, out Any>>,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f, false)}'"),
        )

}

internal class InstanceEventCreateEvents<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForInstanceEvent<T, P, C, V>) -> List<Command<out Any, out Any>>,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)??
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f, false)}'"),
        )

}

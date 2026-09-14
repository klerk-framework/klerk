package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.extractNameFromFunction

import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceLifecycleExecutable
import dev.klerkframework.klerk.statemachine.VoidEventExecutable

internal class VoidEventCreateEvents<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForVoidEvent<T, P, C, V>) -> List<Command<out Any, out Any?>>,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f)}'"),
    )

}

internal class InstanceLifecycleCreateEvents<T : Any, C : KlerkContext, V>(
    val f: (args: LifecycleArgs<T, C, V>) -> List<Command<out Any, out Any?>>,
    override val onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)?
) : InstanceLifecycleExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: LifecycleArgs<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f)}'"),
    )

}

internal class InstanceEventCreateEvents<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForInstanceEvent<T, P, C, V>) -> List<Command<out Any, out Any?>>,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f)}'"),
    )

}

package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.EventProcessingOptions
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ProcessingData
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.Executable
import dev.klerkframework.klerk.view.ModelViews

internal class CreateCommands<T : Any, A, C : KlerkContext, V>(
    val f: (args: A) -> List<Command<out Any, out Any?>>,
    override val onCondition: ((args: A) -> Boolean)?,
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        remainingCommands = f(args),
        log = listOf("Adding commands '${extractNameFromFunction(f)}'"),
    )
}

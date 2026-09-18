package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.EventProcessingOptions
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelArgs
import dev.klerkframework.klerk.ProcessingData
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.statemachine.Executable
import dev.klerkframework.klerk.view.ModelViews

internal class Transition<T : Any, A : ModelArgs<T, C, V>, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val targetState: ModelStates,
    override val onCondition: ((args: A) -> Boolean)?,
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = transition(targetState.name, args.model, args.context.time, specification, view)
}

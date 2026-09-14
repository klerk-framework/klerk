package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.misc.makeExactSerializable
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceLifecycleExecutable
import kotlin.time.Instant

internal class InstanceLifecycleTransition<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val targetState: ModelStates,
    override val onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)?
) : InstanceLifecycleExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: LifecycleArgs<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, args.time, targetState.name, specification, view)

}

internal class InstanceEventTransition<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val targetState: ModelStates,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, args.context.time, targetState.name, specification, view)

}


private fun <Primary : Any, T : Any, C : KlerkContext, V> process(
    model: Model<T>?,
    time: Instant,
    targetState: String,
    specification: Specification<C, V>,
    view: ModelViews<T, C>,
): ProcessingData<Primary, C, V> {
    requireNotNull(model)
    val exitBlock = specification.getStateMachine(model).mutableStates.single { it.name == model.state }.exitBlock
    val updatedModel = model.copy(state = targetState, lastStateTransitionAt = makeExactSerializable(time))
    val enterBlock =
        specification.getStateMachine(updatedModel).mutableStates.single { it.name == updatedModel.state }.enterBlock

    // note that we will not update modifiedModel now since we must first execute any exit block using the model as it
    // currently is.
    return ProcessingData(
        transitions = listOf(updatedModel.id),
        unFinalizedTransition = Triple(updatedModel.state, updatedModel.lastStateTransitionAt, updatedModel),
        remainingBlocks = listOf(exitBlock, enterBlock),
        functionsToUpdateViews = listOf { view.internalDidUpdate(model, updatedModel) },
        log = listOf("Transition from ${model.state} -> ${updatedModel.state}")
    )
}

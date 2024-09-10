package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import kotlinx.datetime.Instant

internal class InstanceNonEventTransition<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val targetState: ModelStates,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, args.time, targetState.name, config, view)

}

internal class InstanceEventTransition<T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val targetState: ModelStates,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, args.context.time, targetState.name, config, view)

}


private fun <Primary : Any, T : Any, C : KlerkContext, V> process(
    model: Model<T>?,
    time: Instant,
    targetState: String,
    config: Config<C, V>,
    view: ModelCollections<T, C>,
): ProcessingData<Primary, C, V> {
    requireNotNull(model)
    val exitBlock = config.getStateMachine(model).states.single { it.name == model.state }.exitBlock
    val updatedModel = model.copy(state = targetState, lastStateTransitionAt = time)
    val enterBlock = config.getStateMachine(updatedModel).states.single { it.name == updatedModel.state }.enterBlock

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

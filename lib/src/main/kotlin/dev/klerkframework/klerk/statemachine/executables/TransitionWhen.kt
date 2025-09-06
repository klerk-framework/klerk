package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import kotlinx.datetime.Instant

internal class InstanceNonEventTransitionWhen<ModelStates : Enum<*>, T : Any, C : KlerkContext, V>(
    internal val branches: LinkedHashMap<(args: ArgForInstanceNonEvent<T, C, V>) -> Boolean, ModelStates>,
    internal val otherwise: ModelStates?
) :
    InstanceNonEventExecutable<T, C, V> {
    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>
    ): ProcessingData<Primary, C, V> {
        branches.forEach { (condition, targetState) ->
            if (condition.invoke(args)) {
                return transition(targetState, args.model, args.time, config, view)
            }
        }
        if (otherwise != null) {
            return transition(otherwise, args.model, args.time, config, view)
        }
        return ProcessingData()
    }

    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean) = { true }

}

internal class InstanceEventTransitionWhen<ModelStates : Enum<*>, T : Any, P, C : KlerkContext, V>(
    internal val branches: LinkedHashMap<(args: ArgForInstanceEvent<T, P, C, V>) -> Boolean, ModelStates>,
    internal val otherwise: ModelStates?
) :
    InstanceEventExecutable<T, P, C, V> {
    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>
    ): ProcessingData<Primary, C, V> {
        branches.forEach { (condition, targetState) ->
            if (condition.invoke(args)) {
                return transition(targetState, args.model, args.context.time, config, view)
            }
        }
        if (otherwise != null) {
            return transition(otherwise, args.model, args.context.time, config, view)
        }
        return ProcessingData()
    }

    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean) = { true }

}

private fun <Primary : Any, ModelStates : Enum<*>, T : Any, C : KlerkContext, V> transition(
    targetState: ModelStates,
    model: Model<T>,
    time: Instant,
    config: Config<C, V>,
    view: ModelViews<T, C>,
): ProcessingData<Primary, C, V> {
    val exitBlock = config.getStateMachine(model).states.single { it.name == model.state }.exitBlock
    val updatedModel = model.copy(state = targetState.name, lastStateTransitionAt = time)
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

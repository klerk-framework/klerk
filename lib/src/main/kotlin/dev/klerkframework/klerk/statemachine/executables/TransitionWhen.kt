package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.misc.makeExactSerializable
import dev.klerkframework.klerk.statemachine.Executable
import kotlin.time.Instant

internal class TransitionWhen<T : Any, A : ModelArgs<T, C, V>, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val branches: LinkedHashMap<(args: A) -> Boolean, ModelStates>,
    internal val otherwise: ModelStates?,
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        for ((condition, targetState) in branches) {
            if (condition.invoke(args)) {
                return transition(targetState.name, args.model, args.context.time, specification, view)
            }
        }
        if (otherwise != null) {
            return transition(otherwise.name, args.model, args.context.time, specification, view)
        }
        return ProcessingData()
    }

    override val onCondition: ((args: A) -> Boolean) = { true }

}

/**
 * The exit block runs before the transition takes effect, so the model is not updated here — the transition is
 * finalized when the exit block's result is merged.
 */
internal fun <Primary : Any, T : Any, C : KlerkContext, V> transition(
    targetState: String,
    model: Model<T>,
    time: Instant,
    specification: Specification<C, V>,
    view: ModelViews<T, C>,
): ProcessingData<Primary, C, V> {
    val exitBlock = specification.getStateMachine(model).states.single { it.name == model.state }.exitBlock
    val updatedModel = model.copy(state = targetState, lastStateTransitionAt = makeExactSerializable(time))
    val enterBlock =
        specification.getStateMachine(updatedModel).states.single { it.name == updatedModel.state }.enterBlock

    return ProcessingData(
        transitions = listOf(updatedModel.id),
        unFinalizedTransition = UnfinalizedTransition(
            updatedModel.state,
            updatedModel.lastStateTransitionAt,
            updatedModel,
        ),
        remainingBlocks = listOf(exitBlock, enterBlock),
        functionsToUpdateViews = listOf { view.internalDidUpdate(model, updatedModel) },
        log = listOf("Transition from ${model.state} -> ${updatedModel.state}"),
    )
}

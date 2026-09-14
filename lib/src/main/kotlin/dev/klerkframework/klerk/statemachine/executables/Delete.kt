package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceLifecycleExecutable
import dev.klerkframework.klerk.storage.ModelCache

internal class InstanceLifecycleDelete<T : Any, C : KlerkContext, V>(
    override val onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)?
) : InstanceLifecycleExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: LifecycleArgs<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, processingDataSoFar, view)

}

internal class InstanceEventDelete<T : Any, P, C : KlerkContext, V>(
    override val onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: InstanceEventArgs<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, processingDataSoFar, view)

}

private fun <Primary : Any, T : Any, C : KlerkContext, V> process(
    model: Model<T>?,
    processingDataSoFar: ProcessingData<Primary, C, V>,
    view: ModelViews<T, C>,
): ProcessingData<Primary, C, V> {
    requireNotNull(model)
    val other = ModelCache.referencingIds(model.id)
    if (other.isNotEmpty()) {
        val currentReferencesToModel = other.filter { !processingDataSoFar.deletedModels.contains(it) }
        if (currentReferencesToModel.isNotEmpty()) {
            return ProcessingData(
                problems = listOf(
                    StateProblem(
                        endUserTranslatedMessage = "Cannot delete since it is used elsewhere.",
                        internalDescription = "Cannot delete model ${model.id} since these models have a reference to it: ${
                            currentReferencesToModel.joinToString(
                                ", "
                            ) { it.toString() }
                        }",
                        KlerkErrorCode.BrokenReference)))
        }
    }
    return ProcessingData(
        deletedModels = listOf(model.id),
        functionsToUpdateViews = listOf { view.internalDidDelete(model) },
        log = listOf("Deleting model ${model.id}")
    )
}

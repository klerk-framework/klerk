package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import dev.klerkframework.klerk.storage.ModelCache

internal class InstanceNonEventDelete<T : Any, C : KlerkContext, V>(
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(args.model, processingDataSoFar, view)

}

internal class InstanceEventDelete<T : Any, P, C : KlerkContext, V>(
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        config: Config<C, V>,
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
    val other = ModelCache.getAllRelated(model.id)
    if (other.isNotEmpty()) {
        val currentReferencesToModel = other.filter { !processingDataSoFar.deletedModels.contains(it) }
        if (currentReferencesToModel.isNotEmpty()) {
            return ProcessingData(problems = listOf(StateProblem("Cannot delete model ${model.id} since these models have a reference to it: ${
                currentReferencesToModel.joinToString(
                    ", "
                ) { it.toString() }
            }")))
        }
    }
    return ProcessingData(
        deletedModels = listOf(model.id),
        functionsToUpdateViews = listOf { view.internalDidDelete(model) },
        log = listOf("Deleting model ${model.id}")
        )
}


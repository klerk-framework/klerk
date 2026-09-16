package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.statemachine.Executable
import dev.klerkframework.klerk.storage.ModelCache

internal class DeleteModel<T : Any, A : ModelArgs<T, C, V>, C : KlerkContext, V>(
    override val onCondition: ((args: A) -> Boolean)?,
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        val model = args.model
        val other = ModelCache.referencingIds(model.id)
        if (other.isNotEmpty()) {
            val currentReferencesToModel = other.filter { it !in processingDataSoFar.deletedModels }
            if (currentReferencesToModel.isNotEmpty()) {
                return ProcessingData(
                    problems = listOf(
                        StateProblem(
                            endUserTranslatedMessage = "Cannot delete since it is used elsewhere.",
                            internalDescription = "Cannot delete model ${model.id} since these models have a " +
                                "reference to it: ${currentReferencesToModel.joinToString(", ")}",
                            KlerkErrorCode.BrokenReference,
                        ),
                    ),
                )
            }
        }
        return ProcessingData(
            deletedModels = listOf(model.id),
            functionsToUpdateViews = listOf { view.internalDidDelete(model) },
            log = listOf("Deleting model ${model.id}"),
        )
    }

}

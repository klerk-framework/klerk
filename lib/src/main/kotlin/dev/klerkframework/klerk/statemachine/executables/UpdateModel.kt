package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.EventProcessingOptions
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelArgs
import dev.klerkframework.klerk.ProcessingData
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.misc.makeExactSerializable
import dev.klerkframework.klerk.statemachine.Executable
import dev.klerkframework.klerk.view.ModelViews

internal class UpdateModel<T : Any, A : ModelArgs<T, C, V>, C : KlerkContext, V>(
    val f: (args: A) -> T,
    override val onCondition: ((args: A) -> Boolean)?,
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        val newProperties = f(args)
        val model = args.model
        val validationProblems = validateModelProps(newProperties, args.context.translation)
        if (validationProblems.isNotEmpty()) {
            return ProcessingData(problems = validationProblems)
        }

        val updatedModel = model.copy(
            props = newProperties,
            lastPropsUpdatedAt = makeExactSerializable(args.context.time),
        )
        return ProcessingData(
            updatedModels = listOf(updatedModel.id),
            aggregatedModelState = mapOf(updatedModel.id to updatedModel),
            functionsToUpdateViews = listOf { view.internalDidUpdate(model, updatedModel) },
            log = listOf("Updating properties using ${extractNameFromFunction(f)}"),
        )
    }
}

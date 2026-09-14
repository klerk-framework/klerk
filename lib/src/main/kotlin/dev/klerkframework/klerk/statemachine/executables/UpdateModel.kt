package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.misc.makeExactSerializable

import dev.klerkframework.klerk.misc.verifyReferencesExist
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceLifecycleExecutable
import kotlin.time.Instant

internal class InstanceLifecycleUpdateModel<T : Any, C : KlerkContext, V>(
    val f: (LifecycleArgs<T, C, V>) -> T,
    override val onCondition: ((args: LifecycleArgs<T, C, V>) -> Boolean)?
) : InstanceLifecycleExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: LifecycleArgs<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(f(args), args.model, args.time, args.reader, view, extractNameFromFunction(f))

}

internal class InstanceEventUpdateModel<T : Any, P, C : KlerkContext, V>(
    val f: (InstanceEventArgs<T, P, C, V>) -> T,
    override val onCondition: ((args: InstanceEventArgs<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: InstanceEventArgs<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        process(f(args), args.model, args.context.time, args.reader, view, extractNameFromFunction(f))

}

private fun <Primary : Any, T : Any, C : KlerkContext, V> process(
    newProperties: T,
    model: Model<T>,
    time: Instant,
    reader: ModelReader<C, V>,
    view: ModelViews<T, C>,
    functionName: String,
): ProcessingData<Primary, C, V> {
    val validationProblems = validateModelProps(newProperties)
    if (validationProblems.isNotEmpty()) {
        return ProcessingData(problems = validationProblems)
    }

    val updatedModel = model.copy(props = newProperties, lastPropsUpdatedAt = makeExactSerializable(time))
    val referenceProblem = verifyReferencesExist(updatedModel, reader)
    if (referenceProblem != null) {
        throw referenceProblem.asException()
    }
    return ProcessingData(
        updatedModels = listOf(updatedModel.id),
        aggregatedModelState = mapOf(updatedModel.id to updatedModel),
        functionsToUpdateViews = listOf { view.internalDidUpdate(model, updatedModel) },
        log = listOf("Updating properties using $functionName")
    )
}

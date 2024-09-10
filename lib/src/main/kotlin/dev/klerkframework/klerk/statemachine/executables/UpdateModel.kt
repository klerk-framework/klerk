package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.misc.verifyReferencesExist
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import kotlinx.datetime.Instant

internal class InstanceNonEventUpdateModel<T : Any, C : KlerkContext, V>(
    val f: (ArgForInstanceNonEvent<T, C, V>) -> T,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = process(f(args), args.model, args.time, args.reader, view,  extractNameFromFunction(f, false))

}

internal class InstanceEventUpdateModel<T : Any, P, C : KlerkContext, V>(
    val f: (ArgForInstanceEvent<T, P, C, V>) -> T,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = process(f(args), args.model, args.context.time, args.reader, view, extractNameFromFunction(f, false))

}

private fun <Primary : Any, T : Any, C : KlerkContext, V> process(
    newProperties: T,
    model: Model<T>,
    time: Instant,
    reader: Reader<C, V>,
    view: ModelCollections<T, C>,
    functionName: String,
): ProcessingData<Primary, C, V> {
    val validationProblems = validateModelProps(newProperties)
    if (validationProblems.isNotEmpty()) {
        return ProcessingData(problems = validationProblems)
    }

    val updatedModel = model.copy(props = newProperties, lastPropsUpdateAt = time)
    val referenceProblem = verifyReferencesExist(updatedModel, reader)
    if (referenceProblem != null) {
        throw referenceProblem.asException()
    }
    return ProcessingData(
        updatedModels = listOf(updatedModel.id),
        aggregatedModelState = mapOf(updatedModel.id to updatedModel),
        functionsToUpdateViews = listOf { view.internalDidUpdate(model, updatedModel) },
        log = listOf("Updating properties using $functionName"))
}

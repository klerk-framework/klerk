package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.misc.extractNameFromFunction

import dev.klerkframework.klerk.misc.verifyReferencesExist
import dev.klerkframework.klerk.statemachine.VoidEventExecutable
import kotlinx.datetime.Instant

internal class VoidEventCreateModel<ModelStates : Enum<*>, T : Any, P, C : KlerkContext, V>(
    val initialState: ModelStates,
    val f: (args: ArgForVoidEvent<T, P, C, V>) -> T,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        val Any = f(args)
        val validationProblems = validateModelProps(Any)
        if (validationProblems.isNotEmpty()) {
            return ProcessingData(problems = validationProblems)
        }

        val created = Model(
            id = processingOptions.idProvider.getNext(),
            createdAt = args.context.time,
            lastPropsUpdateAt = args.context.time,
            lastStateTransitionAt = Instant.DISTANT_PAST,
            state = "void",
            timeTrigger = null,
            props = Any,
        )
        val referenceProblem = verifyReferencesExist(created, args.reader)
        if (referenceProblem != null) {
            throw referenceProblem.asException()
        }
        val sm = config.getStateMachine(created)
        val voidExitBlock = sm.voidState.exitBlock
        val enterBlock = sm.states.single { it.name == initialState.name }.enterBlock
        return ProcessingData(
            createdModels = listOf(created.id),
            unFinalizedTransition = Triple(initialState.name, args.context.time, created),
            aggregatedModelState = mapOf(created.id to created),
            currentModel = created.id,
            remainingBlocks = listOf(voidExitBlock, enterBlock),
            functionsToUpdateViews = listOf { view.internalDidCreate(created) },
            log = listOf("Creating model using '${extractNameFromFunction(f, false)}'"),
        )
    }

}

internal fun validateModelProps(Any: Any): List<Problem> {
    if (Any !is Validatable) {
        return emptyList()
    }
    return Any.validators().filter { it.invoke() is PropertyCollectionValidity.Invalid }.map {
        StateProblem(
            "The command would result in an invalid model",
            violatedRule = RuleDescription(it, RuleType.ModelValidation)
        )
    }
}

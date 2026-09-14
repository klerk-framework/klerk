package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.misc.makeExactSerializable

import dev.klerkframework.klerk.misc.verifyReferencesExist
import dev.klerkframework.klerk.statemachine.VoidEventExecutable
import kotlin.time.Instant

internal class VoidEventCreateModel<ModelStates : Enum<*>, T : Any, P, C : KlerkContext, V>(
    val initialState: ModelStates,
    val f: (args: VoidEventArgs<T, P, C, V>) -> T,
    override val onCondition: ((args: VoidEventArgs<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: VoidEventArgs<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        val Any = f(args)
        val validationProblems = validateModelProps(Any, args.context.translation)
        if (validationProblems.isNotEmpty()) {
            return ProcessingData(problems = validationProblems)
        }

        val time = makeExactSerializable(args.context.time)
        val created = Model(
            id = processingOptions.idProvider.getNextModelID(),
            createdAt = time,
            lastPropsUpdatedAt = time,
            lastStateTransitionAt = Instant.DISTANT_PAST,
            state = "void",
            timeTrigger = null,
            props = Any,
        )
        val referenceProblem = verifyReferencesExist(created, args.reader)
        if (referenceProblem != null) {
            throw referenceProblem.asException()
        }
        val sm = specification.getStateMachine(created)
        val voidExitBlock = sm.voidState.exitBlock
        val enterBlock = sm.mutableStates.single { it.name == initialState.name }.enterBlock
        return ProcessingData(
            createdModels = listOf(created.id),
            unFinalizedTransition = Triple(initialState.name, time, created),
            aggregatedModelState = mapOf(created.id to created),
            currentModel = created.id,
            remainingBlocks = listOf(voidExitBlock, enterBlock),
            functionsToUpdateViews = listOf { view.internalDidCreate(created) },
            log = listOf("Creating model using '${extractNameFromFunction(f)}'"),
        )
    }

}

internal fun validateModelProps(Any: Any, translation: Translation): List<Problem> {
    if (Any !is Validatable) {
        return emptyList()
    }
    // Its own error code: the parameters were fine, it is the model they would produce that is not.
    return Any.validators().mapNotNull { rule ->
        (rule.invoke() as? PropertyCollectionValidity.Invalid)?.message(rule, translation)?.let { message ->
            StateProblem(
                message,
                message,
                violatedRule = RuleDescription(rule, RuleType.ModelValidation),
                code = KlerkErrorCode.CommandModelValidation
            )
        }
    }
}

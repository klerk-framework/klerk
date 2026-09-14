package dev.klerkframework.klerk.validation

import dev.klerkframework.klerk.misc.functionName
import dev.klerkframework.klerk.misc.requireNamedRule
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.PositiveAuthorization.Allow
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.statemachine.DeclaredEventRules
import dev.klerkframework.klerk.misc.getStateMachine
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.ModelCache

/*
Before a command is accepted, a lot of rules must be evaluated.

Using event parameters:
* DataContainers used in event parameters
* Validation functions on the parameters class (if is Validatable)

Using parameters and context:
* Validate the context

Using parameters, context and state:
* Do all parameter references (ModelID) point on an existing model?
* Is the event possible given the current state of the model?
* Rules specified in the state machine when declaring the event
* Authorization rules

 */

internal class Validator<C : KlerkContext, V>(private val klerk: KlerkImpl<C, V>) {

    fun isEventPossibleGivenModelState(
        currentCommand: Command<out Any, *>,
        reader: ModelReader<C, V>
    ): Problem? {
        val sm = klerk.specification.getStateMachineForEvent(currentCommand.event)
        if (currentCommand.model == null) {
            val smState = sm.voidState
            if (smState.getEvents().none { it.name == currentCommand.event.name }) {
                return StateProblem(
                    "Event '${currentCommand.event}' is not possible in void state",
                    "Event '${currentCommand.event}' is not possible in void state",
                    KlerkErrorCode.EventNotPossibleInVoidState
                )
            }
        } else {
            val model = reader.get(currentCommand.model)
            val smState = sm.instanceStates.single { it.name == model.state }
            if (smState.getEvents().none { it.name == currentCommand.event.name }) {
                return StateProblem(
                    "Event '${currentCommand.event}' is not possible on model ${model.id} which is in state '${model.state}'",
                    "Event '${currentCommand.event}' is not possible on model ${model.id} which is in state '${model.state}'",
                    KlerkErrorCode.EventNotPossibleInState
                )
            }
        }
        return null
    }

    private fun <T : Any, P> validateEventRules(
        context: C,
        event: Event<T, P>,
        id: ModelID<T>?,
        params: P,
        reader: ModelReader<C, V>
    ): List<Problem> {
        val rules = klerk.specification.rulesOf(event.id)
        // The rule function is kept next to its result, so a failure can be named in the message and the problem.
        val results: List<Pair<Function<Any>, PropertyCollectionValidity>> = when (event) {

            is VoidEventNoParameters<T> -> {
                val command = Command(event, null, null)
                val args = VoidEventArgs(command, context, reader)
                rules.withoutParameters<VoidEventArgs<T, Nothing?, C, V>>().map { it to it.invoke(args) }
            }

            is VoidEventWithParameters<T, *> -> {
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, null, null)
                val argsWithoutParams = VoidEventArgs(commandWithoutParams, context, reader)
                val withoutParams = rules.withoutParameters<VoidEventArgs<T, Nothing?, C, V>>()
                    .map { it to it.invoke(argsWithoutParams) }

                @Suppress("UNCHECKED_CAST")
                val command = Command(event as Event<T, P>, null, params)
                val argsWithParams = VoidEventArgs(command, context, reader)
                val withParams = rules.withParameters<VoidEventArgs<T, P, C, V>>().map { it to it.invoke(argsWithParams) }

                withoutParams.union(withParams).toList()
            }

            is InstanceEventNoParameters<T> -> {
                val command = Command(event, requireNotNull(id), null)
                val model = reader.get(id)
                val args = InstanceEventArgs(model, command, context, reader)
                rules.withoutParameters<InstanceEventArgs<T, Nothing?, C, V>>().map { it to it.invoke(args) }
            }

            is InstanceEventWithParameters<T, *> -> {
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, requireNotNull(id), null)
                val model = reader.get(id)
                val argsWithoutParams = InstanceEventArgs(model, commandWithoutParams, context, reader)
                val withoutParams = rules.withoutParameters<InstanceEventArgs<T, Nothing?, C, V>>()
                    .map { it to it.invoke(argsWithoutParams) }

                @Suppress("UNCHECKED_CAST")
                val command = Command(event as Event<T, P>, id, requireNotNull(params))
                val argsWithParams = InstanceEventArgs(model, command, context, reader)
                val withParams = rules.withParameters<InstanceEventArgs<T, P, C, V>>()
                    .map { it to it.invoke(argsWithParams) }
                withoutParams.union(withParams).toList()
            }

        }
        return results.mapNotNull { (rule, result) ->
            (result as? PropertyCollectionValidity.Invalid)?.toProblem(rule, context.translation)
        }
    }

    private fun validateWithContext(context: C, eventReference: EventReference): Collection<Problem> {
        val translation = context.translation
        return klerk.specification.rulesOf(eventReference).forContext<C>().mapNotNull { rule ->
            val result = rule.invoke(context)
            if (result is ContextValidity.Invalid) PreventedByRuleProblem(
                endUserTranslatedMessage = translation.klerk.preventedByRule(
                    functionName(rule) ?: translation.klerk.invalid,
                    result.translationInfo,
                ),
                violatedRule = RuleDescription(rule, RuleType.ContextValidation),
            ) else null
        }
    }

    /** Checks every [EnumContainer] in [parameters], also in collections and nested objects, against `validEnums`. */
    private fun validateEnums(eventReference: EventReference, parameters: Any?): Problem? {
        if (parameters == null) {
            return null
        }
        val validEnums = klerk.specification.validEnumsOf(eventReference)
        var problem: Problem? = null
        ObjectSchema.of(parameters::class).forEachLeaf(parameters) { leaf ->
            val container = leaf.value as? EnumContainer<*> ?: return@forEachLeaf
            val validValues = validEnums[leaf.field.key] ?: return@forEachLeaf
            if (problem == null && !validValues.contains(container.value)) {
                problem = InvalidPropertyProblem(
                    "'${container.value}' is not a valid value for parameter ${leaf.path}",
                    propertyName = leaf.path
                )
            }
        }
        return problem
    }

    /**
     * Checks every [ModelID] in [parameters], also in collections and nested objects, against `validReferences`. An id
     * in a property without a declaration is rejected.
     */
    private fun validateReferences(eventReference: EventReference, parameters: Any?, context: C): Problem? {
        if (parameters == null) {
            return null
        }
        val validReferences = klerk.specification.validReferencesOf(eventReference)
        val reader = ReaderWithoutAuth<C, V>(klerk)
        var problem: Problem? = null
        ObjectSchema.of(parameters::class).forEachLeaf(parameters) { leaf ->
            val id = leaf.value as? ModelID<*> ?: return@forEachLeaf
            if (problem != null) {
                return@forEachLeaf
            }
            if (!validReferences.containsKey(leaf.field.key)) {
                problem = InvalidPropertyProblem(
                    "There is no validReferences declared for ${leaf.field.key}",
                    propertyName = leaf.path
                )
                return@forEachLeaf
            }
            val view = validReferences[leaf.field.key] ?: return@forEachLeaf
            if (!view.internalContains(id, reader)) {
                problem = InvalidPropertyProblem(
                    "Did not find $id in ${view.id} for parameter ${leaf.path}",
                    propertyName = leaf.path
                )
            }
        }
        return problem
    }

    /** Validates every [DataContainer] in [instance], also in collections and nested objects. */
    fun validateDataContainers(instance: Any, translation: Translation): Set<InvalidPropertyProblem> {
        val problems = mutableSetOf<InvalidPropertyProblem>()
        ObjectSchema.of(instance::class).forEachLeaf(instance) { leaf ->
            (leaf.value as? DataContainer<*>)?.validate(leaf.path, translation)?.let { problems.add(it) }
        }
        return problems
    }

    fun <P> validateCommand(
        currentCommand: Command<out Any, P>,
        reader: ModelReader<C, V>,
        context: C
    ): List<Problem> {
        currentCommand.model?.let {
            if (reader.getOrNull(it) == null) {
                return listOf(NotFoundProblem("The model with id=$it could not be found"))
            }
        }
        val stateProblem = isEventPossibleGivenModelState(currentCommand, reader)
        if (stateProblem != null) return listOf(stateProblem)
        val eventValidationProblems = validateEvent(currentCommand, context, reader)
        if (eventValidationProblems.isNotEmpty()) return eventValidationProblems
        val authorizationProblem = checkAuthorization(
            currentCommand,
            reader,
            context
        )
        if (authorizationProblem != null) {
            return listOf(authorizationProblem)
        }
        return emptyList()
    }

    private fun <T : Any, P> checkAuthorization(
        command: Command<T, P>,
        reader: ModelReader<C, V>,
        context: C
    ): Problem? {
        val negativeAuthProblem =
            klerk.specification.authorization.eventNegativeRules.firstOrNull {
                it(
                    CommandRuleArgs(
                        command,
                        context,
                        reader
                    )
                ) == Deny
            }
        if (negativeAuthProblem != null) {
            return AuthorizationProblem(
                context.translation.klerk.unauthorized, RuleDescription(negativeAuthProblem, RuleType.Authorization),
                KlerkErrorCode.CommandNegativeAuthorizationExist
            )
        }
        if (klerk.specification.authorization.eventPositiveRules.none {
                it(
                    CommandRuleArgs(
                        command,
                        context,
                        reader
                    )
                ) == Allow
            }) {
            logger.info("Event '${command.event}' was not accepted since no rule explicitly permitted the operation")
            return AuthorizationProblem(
                context.translation.klerk.unauthorized,
                null,
                KlerkErrorCode.CommandPositiveAuthorizationMissing
            )
        }
        return null
    }

    /**
     * @return true if all rules passes
     */
    private fun evaluateContextAndParameterRules(
        eventReference: EventReference,
        parameters: Any?,
        context: C
    ): Boolean {
        if (validateWithContext(context, eventReference).any()) return false
        parameters?.let {
            if (validateDataContainers(it, context.translation).any()) return false
            if (validateReferences(eventReference, parameters, context) != null) return false
        }
        return true
    }

    private fun <T : Any, P> validateEvent(
        command: Command<T, P>,
        context: C,
        reader: ModelReader<C, V>
    ): List<Problem> {
        val problems = mutableListOf<Problem>()
        val stateMachine = getStateMachine(command, klerk.specification.managedModels)
        if (command.model != null) {
            val model = ModelCache.read(command.model).getOrThrow()
            if (model.props::class != stateMachine.type) {
                return listOf(
                    BadRequestProblem(
                        "The provided Reference refers to a model of type '${model.props::class}' but the state machine handles '${stateMachine.type}'",
                        KlerkErrorCode.ModelTypeMismatch
                    )
                )
            }
        }

        command.params?.let { p ->
            problems.addAll(validateDataContainers(p, context.translation))
            problems.addAll(validatePropertyCollection(p, context.translation))
        }

        problems.addAll(validateWithContext(context, command.event.id))
        validateReferences(command.event.id, command.params, context)?.let { problems.add(it) }
        validateEnums(command.event.id, command.params)?.let { problems.add(it) }

        if (problems.isNotEmpty()) {
            return problems
        }

        return validateEventRules(context, command.event, command.model, command.params, reader)
    }

    private fun validatePropertyCollection(params: Any, translation: Translation): Collection<Problem> {
        if (params !is Validatable) {
            return emptyList()
        }
        return params.validators()
            .onEach { requireNamedRule(it, "A validator of ${params::class.simpleName}") }
            .associateWith { it.invoke() }
            .filter { it.value is PropertyCollectionValidity.Invalid }
            .map { (it.value as PropertyCollectionValidity.Invalid).toProblem(it.key, translation) }
    }

    fun <T : Any> validateWithoutParameters(
        eventRef: EventReference,
        context: C,
        model: Model<T>?,
        readerWithoutAuth: ReaderWithoutAuth<C, V>
    ): Boolean {
        if (validateWithContext(context, eventRef).isNotEmpty()) {
            return false
        }

        @Suppress("UNCHECKED_CAST")
        return validateEventRulesWithoutParams(
            klerk.specification.getEvent(eventRef) as Event<T, Any?>,
            context,
            model,
            readerWithoutAuth
        )
            .filterIsInstance<PropertyCollectionValidity.Invalid>()
            .isEmpty()
    }

    private fun <T : Any> validateEventRulesWithoutParams(
        event: Event<T, Any?>,
        context: C,
        model: Model<T>?,
        reader: ReaderWithoutAuth<C, V>
    ): List<PropertyCollectionValidity> {
        val rules = klerk.specification.rulesOf(event.id)
        return when (event) {

            is VoidEventNoParameters<T> -> {
                val command = Command(event, null, null)
                val args = VoidEventArgs(command, context, reader)
                rules.withoutParameters<VoidEventArgs<T, Nothing?, C, V>>().map { it.invoke(args) }
            }

            is VoidEventWithParameters<T, *> -> {
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, null, null)
                val argsWithoutParams = VoidEventArgs(commandWithoutParams, context, reader)
                rules.withoutParameters<VoidEventArgs<T, Nothing?, C, V>>().map { it.invoke(argsWithoutParams) }
            }

            is InstanceEventNoParameters<T> -> {
                requireNotNull(model)
                val command = Command(event, model.id, null)
                val args = InstanceEventArgs(model, command, context, reader)
                rules.withoutParameters<InstanceEventArgs<T, Nothing?, C, V>>().map { it.invoke(args) }
            }

            is InstanceEventWithParameters<T, *> -> {
                requireNotNull(model)
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, model.id, null)
                val argsWithoutParams = InstanceEventArgs(model, commandWithoutParams, context, reader)
                rules.withoutParameters<InstanceEventArgs<T, Nothing?, C, V>>().map { it.invoke(argsWithoutParams) }
            }
        }
    }

}

/** The result of a single [DataContainer] validator function (see `DataContainer.validators`). */
public sealed class PropertyValidity {
    public data object Valid : PropertyValidity()

    /** @param translationInfo optional detail passed to [Translation] when building the end-user error message */
    public class Invalid(public val translationInfo: String? = null) : PropertyValidity()
}

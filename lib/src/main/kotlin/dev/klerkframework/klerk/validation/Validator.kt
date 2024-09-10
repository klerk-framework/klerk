package dev.klerkframework.klerk.validation

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.PositiveAuthorization.Allow
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.EventParameter
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.misc.getStateMachine
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.ModelCache
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

internal class Validator<C : KlerkContext, V>(private val klerk: KlerkImpl<C, V>) {

    fun isEventPossibleGivenModelState(
        currentCommand: Command<out Any, *>,
        reader: Reader<C, V>
    ): Problem? {
        val sm = klerk.config.getStateMachineForEvent(currentCommand.event)
        if (currentCommand.model == null) {
            val smState = sm.voidState
            if (smState.getEvents().none { it.name == currentCommand.event.name }) {
                return StateProblem("Event '${currentCommand.event}' is not possible in void state")
            }
        } else {
            val model = reader.get(currentCommand.model)
            val smState = sm.instanceStates.single { it.name == model.state }
            if (smState.getEvents().none { it.name == currentCommand.event.name }) {
                return StateProblem("Event '${currentCommand.event}' is not possible on model ${model.id} which is in state '${model.state}'")
            }
        }
        return null
    }

    private fun <T : Any, P> validateEventRules(
        context: C,
        event: Event<T, P>,
        id: ModelID<T>?,
        params: P,
        reader: Reader<C, V>
    ): Collection<Problem> {
        val validityList: List<Validity> = when (event) {

            is VoidEventNoParameters<T> -> {
                val command = Command(event, null, null)
                val args = ArgForVoidEvent(command, context, reader)
                event.getNoParamRulesForVoidEvent<C, V>().map { it.invoke(args) }
            }

            is VoidEventWithParameters<T, *> -> {
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, null, null)
                val argsWithoutParams = ArgForVoidEvent(commandWithoutParams, context, reader)
                val withoutParams = event.getNoParamRulesForVoidEvent<C, V>().map { it.invoke(argsWithoutParams) }

                val command = Command(event as Event<T, P>, null, params)
                val argsWithParams = ArgForVoidEvent(command, context, reader)
                @Suppress("UNCHECKED_CAST")
                val withParams =
                    (event as VoidEventWithParameters).getParamRules<C, V>().map { it.invoke(argsWithParams) }

                withoutParams.union(withParams).toList()
            }

            is InstanceEventNoParameters<T> -> {
                val command = Command(event, requireNotNull(id), null)
                val model = reader.get(id)
                val args = ArgForInstanceEvent(model, command, context, reader)
                event.getNoParamRulesForInstanceEvent<C, V>().map { it.invoke(args) }
            }

            is InstanceEventWithParameters<T, *> -> {
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, requireNotNull(id), null)
                val model = reader.get(id)
                val argsWithoutParams = ArgForInstanceEvent(model, commandWithoutParams, context, reader)
                val withoutParams = event.getNoParamRulesForInstanceEvent<C, V>().map { it.invoke(argsWithoutParams) }

                val command = Command(event as Event<T, P>, id, requireNotNull(params))
                val argsWithParams = ArgForInstanceEvent(model, command, context, reader)
                @Suppress("UNCHECKED_CAST")
                val withParams =
                    (event as InstanceEventWithParameters).getParamRules<C, V>().map { it.invoke(argsWithParams) }
                withoutParams.union(withParams).toList()
            }

        }
        return validityList.filterIsInstance<Validity.Invalid>().map { it.toProblem() }
    }

    private fun validateContext(context: C, eventReference: EventReference): Collection<Problem> {
        return klerk.config.getEvent(eventReference)._contextRules.mapNotNull {
            val result = it.invoke(context)
            if (result is Validity.Invalid) InvalidParametersProblem(
                violatedRule = RuleDescription(
                    it,
                    RuleType.ContextValidation
                )
            ) else null
        }
    }

    private fun validateReferences(eventReference: EventReference, parameters: Any?, context: C): Problem? {
        if (parameters == null) {
            return null
        }
        parameters::class.primaryConstructor!!.parameters.forEach { parameter ->
            if (parameter.type.isSubtypeOf(ModelID::class.starProjectedType)) {
                val reader = ReaderWithoutAuth<C, V>(klerk)
                val p = EventParameter(parameter)
                val collection = klerk.config.getValidationCollectionFor(eventReference, p)
                val prop = parameters::class.memberProperties.single { it.name == parameter.name }
                val value = prop.getter.call(parameters) as ModelID<*>
                if (collection != null && !collection.contains(value, reader)) {
                    logger.info { collection }
                    return InvalidParametersProblem(
                        "Did not find $value in ${collection.getFullId()} for parameter ${parameter.name}",
                        parameterName = parameter.name
                    )
                }
            }
        }
        return null
    }

    fun validateDataContainers(instance: Any): Set<InvalidParametersProblem> {
        val problems = mutableSetOf<InvalidParametersProblem>()
        instance::class.memberProperties.forEach { property ->
            if (property.returnType.isSubtypeOf(DataContainer::class.starProjectedType)) {
                val problem = (property.getter.call(instance) as DataContainer<*>).validate(property.name)
                if (problem != null) {
                    problems.add(problem)
                }
            }
        }
        return problems
    }

    fun <P> validateCommand(
        currentCommand: Command<out Any, P>,
        reader: Reader<C, V>,
        context: C
    ): Problem? {
        currentCommand.model?.let {
            if (reader.getOrNull(it) == null) {
                return NotFoundProblem("The model with id=$it could not be found")
            }
        }
        return isEventPossibleGivenModelState(currentCommand, reader)
            ?: validateEvent(currentCommand, context, reader)
            ?: checkAuthorization(
                currentCommand,
                reader,
                context
            )
    }

    private fun <T : Any, P> checkAuthorization(
        command: Command<T, P>,
        reader: Reader<C, V>,
        context: C
    ): Problem? {
        val negativeAuthProblem =
            klerk.config.authorization.eventNegativeRules.firstOrNull {
                it(
                    ArgCommandContextReader(
                        command,
                        context,
                        reader
                    )
                ) == Deny
            }
        if (negativeAuthProblem != null) {
            return AuthorizationProblem(extractNameFromFunction(negativeAuthProblem))
        }
        if (klerk.config.authorization.eventPositiveRules.none {
                it(
                    ArgCommandContextReader(
                        command,
                        context,
                        reader
                    )
                ) == Allow
            }) {
            return AuthorizationProblem("Event '${command.event}' was not created since no policy allowed the operation")
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
        if (validateContext(context, eventReference).any()) return false
        parameters?.let {
            if (validateDataContainers(it).any()) return false
            if (validateReferences(eventReference, parameters, context) != null) return false
        }
        return true
    }

    private fun <T : Any, P> validateEvent(
        command: Command<T, P>,
        context: C,
        reader: Reader<C, V>
    ): Problem? {
        val problems = mutableListOf<Problem>()
        val stateMachine = getStateMachine(command, klerk.config.managedModels)
        if (command.model != null) {
            val model = ModelCache.read(command.model).getOrThrow()
            if (model.props::class != stateMachine.type) {
                return BadRequestProblem("The provided Reference refers to a model of type '${model.props::class}' but the state machine handles '${stateMachine.type}'")
            }
        }

        command.params?.let {
            problems.addAll(validateDataContainers(it))
            problems.addAll(validateParametersAsAUnit(it))
        }

        problems.addAll(validateContext(context, command.event.id))
        validateReferences(command.event.id, command.params, context)?.let { problems.add(it) }

        if (problems.isNotEmpty()) {
            return problems.first()
        }

        return validateEventRules(context, command.event, command.model, command.params, reader).firstOrNull()
    }

    private fun validateParametersAsAUnit(params: Any): Collection<Problem> {
        if (params !is Validatable) {
            return emptyList()
        }
        return params.validators().filter { it.invoke() is Validity.Invalid }.map {
            StateProblem(
                "The parameters are invalid",
                violatedRule = RuleDescription(it, RuleType.ParametersValidation)
            )
        }
    }

    fun <T : Any> validateWithoutParameters(
        eventRef: EventReference,
        context: C,
        model: Model<T>?,
        readerWithoutAuth: ReaderWithoutAuth<C, V>
    ): Boolean {
        if (validateContext(context, eventRef).isNotEmpty()) {
            return false
        }

        @Suppress("UNCHECKED_CAST")
        return validateEventRulesWithoutParams(klerk.config.getEvent(eventRef) as Event<T, Any?>, context, model, readerWithoutAuth)
            .filterIsInstance<Validity.Invalid>()
            .isEmpty()
    }

    private fun <T : Any> validateEventRulesWithoutParams(
        event: Event<T, Any?>,
        context: C,
        model: Model<T>?,
        reader: ReaderWithoutAuth<C, V>
    ): List<Validity> {
        return when (event) {

            is VoidEventNoParameters<T> -> {
                val command = Command(event, null, null)
                val args = ArgForVoidEvent(command, context, reader)
                event.getNoParamRulesForVoidEvent<C, V>().map { it.invoke(args) }
            }

            is VoidEventWithParameters<T, *> -> {
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, null, null)
                val argsWithoutParams = ArgForVoidEvent(commandWithoutParams, context, reader)
                event.getNoParamRulesForVoidEvent<C, V>().map { it.invoke(argsWithoutParams) }
            }

            is InstanceEventNoParameters<T> -> {
                requireNotNull(model)
                val command = Command(event, model.id, null)
                val args = ArgForInstanceEvent(model, command, context, reader)
                event.getNoParamRulesForInstanceEvent<C, V>().map { it.invoke(args) }
            }

            is InstanceEventWithParameters<T, *> -> {
                requireNotNull(model)
                @Suppress("UNCHECKED_CAST")
                val commandWithoutParams = Command(event as Event<T, Nothing?>, model.id, null)
                val argsWithoutParams = ArgForInstanceEvent(model, commandWithoutParams, context, reader)
                event.getNoParamRulesForInstanceEvent<C, V>().map { it.invoke(argsWithoutParams) }
            }
        }
    }

}

package dev.klerkframework.webutils

import dev.klerkframework.klerk.AuthorizationConfig
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext

import dev.klerkframework.klerk.misc.AlgorithmDocumenter
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.misc.Node
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.*
import dev.klerkframework.klerk.statemachine.executables.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import kotlinx.html.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

internal suspend fun <C : KlerkContext, V> renderDocumentation(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    klerk: Klerk<C, V>,
    documentationPath: String
) {
    val showUpdateNotes = (call.request.queryParameters["showUpdateNotes"] ?: "false") == "true"

    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav { div { a(href = config.basePath) { +"Home" } } }
            }
            val forModel = call.request.queryParameters["model"]
            if (forModel == null) {
                h1 { +"Documentation" }
                ul {
                    klerk.config.managedModels.forEach { managedModel ->
                        li {
                            a(href = "$documentationPath?model=${managedModel.kClass.qualifiedName}") { +managedModel.kClass.simpleName.toString() }
                        }
                    }
                }
                apply(renderAuthorizationRules(klerk.config.authorization))
            } else {
                val model = klerk.config.managedModels.single { it.kClass.qualifiedName == forModel }
                h1 { +"Documentation for ${model.kClass.simpleName}" }
                pre(classes = "mermaid") {
                    unsafe {
                        +generateStateDiagram(model.stateMachine, showUpdateNotes)
                    }
                }
                if (!showUpdateNotes) {
                    a(href = call.request.uri.plus("&showUpdateNotes=true")) { +"Show updates" }
                } else {
                    a(href = call.request.uri.replace("&showUpdateNotes=true", "")) { +"Hide updates" }
                }
                apply(addMermaidScript())
                apply(renderEvents(model.stateMachine, klerk))
                // apply(renderStates(model.stateMachine, klerk, documentationPath))

            }
            apply(renderAlgorithms(documentationPath))
        }
    }
}

internal fun renderAlgorithms(documentationPath: String): BODY.() -> Unit = {
    h2 { +"Algorithms" }
    AlgorithmDocumenter.algorithms.forEach {
        val url = URLEncoder.encode(it::class.qualifiedName, Charset.defaultCharset())
        a(href = "$documentationPath/algorithms/${url}") { +it::class.qualifiedName.toString() }
    }
}

private const val noBullets = "list-style-type: none;"

private fun <C : KlerkContext, V> renderEvents(
    stateMachine: StateMachine<out Any, out Enum<*>, C, V>,
    klerk: Klerk<C, V>
): BODY.() -> Unit =
    {
        h2 { +"Events" }
        stateMachine.getExternalEvents().forEach { externalEvent ->
            val parameters = klerk.config.getParameters(externalEvent)
            details {
                summary { +externalEvent.id() }
                ul {
                    style = noBullets
                    li {
                        if (parameters == null) {
                            +"No parameters"
                        } else {
                            details {
                                summary { +"Parameters" }
                                ul {
                                    style = noBullets
                                    parameters.all.forEach { parameter ->
                                        details {
                                            summary { +parameter.name }
                                            table {
                                                tr {
                                                    td { +"Required" }
                                                    td { +if (parameter.isRequired) "yes" else "no" }
                                                }
                                                tr {
                                                    td { +"Nullable" }
                                                    td { +if (parameter.isNullable) "yes" else "no" }
                                                }
                                                tr {
                                                    td { +"Primitive type" }
                                                    td { +parameter.type.toString() }
                                                }
                                                tr {
                                                    td { +"Class" }
                                                    td { +parameter.valueClass.toString() }
                                                }
                                                parameter.validationRulesDescription().forEach { entry ->
                                                    tr {
                                                        td { +entry.key }
                                                        td { +entry.value }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    li {
                        details {
                            summary { +"Validation rules" }
                            ul {
                                klerk.config.getEvent(externalEvent)._contextRules.forEach {
                                    li {
                                        +"Context: ${extractNameFromFunction(it)}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

internal fun <V> renderAuthorizationRules(config: AuthorizationConfig<*, V>): BODY.() -> Unit = {
    h2 { +"Authorization rules" }
    details {
        summary { +"Events" }
        ul {
            style = noBullets
            li {
                details {
                    summary { +"Positive" }
                    ul {
                        config.eventPositiveRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }
            li {
                details {
                    summary { +"Negative" }
                    ul {
                        config.eventNegativeRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }
        }
    }

    details {
        summary { +"Read models" }
        ul {
            style = noBullets
            li {
                details {
                    summary { +"Positive" }
                    ul {
                        config.readModelPositiveRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }
            li {
                details {
                    summary { +"Negative" }
                    ul {
                        config.readModelNegativeRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }

        }
    }

    details {
        summary { +"Read model properties" }
        ul {
            style = noBullets
            li {
                details {
                    summary { +"Positive" }
                    ul {
                        config.readPropertyPositiveRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }
            li {
                details {
                    summary { +"Negative" }
                    ul {
                        config.readPropertyNegativeRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }

        }
    }

    details {
        summary { +"Event log" }
        ul {
            style = noBullets
            li {
                details {
                    summary { +"Positive" }
                    ul {
                        config.eventLogPositiveRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }
            li {
                details {
                    summary { +"Negative" }
                    ul {
                        config.eventLogNegativeRules.forEach {
                            li { +extractNameFromFunction(it) }
                        }
                    }
                }
            }

        }
    }

}

private fun <V> generateStateDiagram(
    stateMachine: StateMachine<out Any, out Enum<*>, *, V>,
    showUpdateNotes: Boolean
): String {
    var result = "stateDiagram-v2\n"
    stateMachine.states.filterNot { it.name == "void" }.forEach { result += generateStateVariable(it) }
    result += generateVoidTransitions(stateMachine.voidState)
    result += generateTransitions(stateMachine.instanceStates)
    if (showUpdateNotes) {
        result += generateUpdateNotes(stateMachine.instanceStates)
    }
    result += generateDeleteTransitions(stateMachine.states)
    return result
}

private fun <V> generateStateVariable(state: State<out Any, out Enum<*>, *, V>): String {
    return "${toVariable(state.name)}: ${state.name}\n"
}

private fun toVariable(name: String): String = name.replace(" ", "").lowercase()

private fun <V> generateVoidTransitions(initialState: VoidState<out Any, out Enum<*>, *, V>): String {
    var result = ""
    initialState.onEventBlocks.forEach { eventBlock ->
        eventBlock.second.executables.filterIsInstance<VoidEventCreateModel<*, *, *, *, V>>().forEach { createModel ->
            result += "[*] --> ${toVariable(createModel.initialState.name)}: ${eventBlock.first.name}\n"
        }
    }
    return result
}

private fun <V> generateTransitions(states: List<InstanceState<out Any, out Enum<*>, *, V>>): String {
    var result = ""
    states.forEach { state ->

        state.onEventBlocks.forEach { eventBlock ->
            eventBlock.second.executables.filterIsInstance<InstanceEventTransition<*, *, *, *, V>>()
                .forEach { transition ->
                    result += "${toVariable(state.name)} --> ${toVariable(transition.targetState.name)}: ${eventBlock.first.name}\n"
                }

            eventBlock.second.executables
                .filterIsInstance<InstanceEventTransitionWhen<*, *, *, *, *>>()
                .forEach { transition ->
                    transition.branches.forEach { branch ->
                        result += "${toVariable(state.name)} --> ${toVariable(branch.value.name)}: ${
                            extractNameFromFunction(
                                branch.key
                            )
                        }\n"
                    }
                }
        }

        state.enterBlock.let { enterBlock ->
            when (enterBlock) {
                is Block.InstanceNonEventBlock -> {
                    enterBlock.executables
                        .filterIsInstance<InstanceNonEventTransition<*, *, *, *>>()
                        .forEach { transition ->
                            result += "${toVariable(state.name)} --> ${toVariable(transition.targetState.name)}: [on enter]\n"
                        }

                    enterBlock.executables
                        .filterIsInstance<InstanceNonEventTransitionWhen<*, *, *, *>>()
                        .forEach { transition ->
                            transition.branches.forEach { branch ->
                                result += "${toVariable(state.name)} --> ${toVariable(branch.value.name)}: ${
                                    extractNameFromFunction(
                                        branch.key
                                    )
                                }\n"
                            }
                        }
                }

                is Block.VoidNonEventBlock -> {
                    // TODO
                }

                else -> error("Will not happens since enter/exit blocks are non-event blocks")
            }

        }

    }
    return result
}

private fun <V> generateUpdateNotes(states: List<InstanceState<out Any, out Enum<*>, *, V>>): String {
    var result = ""
    states.forEach { state ->
        var resultState = ""

        state.onEventBlocks.forEach { eventBlock ->
            eventBlock.second.executables.filterIsInstance<InstanceEventUpdateModel<*, *, *, *>>().forEach { _ ->
                resultState += "• ${eventBlock.first.name}\n"
            }
        }

        state.onEventBlocks.forEach { eventBlock ->
            eventBlock.second.executables.filterIsInstance<InstanceNonEventUpdateModel<*, *, *>>().forEach { _ ->
                resultState += "• ${eventBlock.first.name}\n"
            }
        }

        if (resultState.isNotEmpty()) {
            resultState =
                """note left of ${toVariable(state.name)}
                    | Events that updates properties:
                    | ${resultState}
                    | end note
                    | """.trimMargin()
            result += resultState
        }
    }
    return result
}

private fun <V> generateDeleteTransitions(states: List<State<out Any, out Enum<*>, *, V>>): String {
    var result = ""
    states.forEach { state ->

        if (state is InstanceState) {
            state.onEventBlocks.forEach { eventBlock ->
                eventBlock.second.executables
                    .filterIsInstance<InstanceEventDelete<*, *, *, *>>()
                    .forEach { _ ->
                        result += "${toVariable(state.name)} --> [*]: ${eventBlock.first.name}\n"
                    }

            }
        }

        state.enterBlock.let { enterBlock ->
            when (enterBlock) {
                is Block.InstanceNonEventBlock -> {
                    enterBlock.executables.filterIsInstance<InstanceEventDelete<*, *, *, *>>()
                        .forEach { _ ->
                            result += "${toVariable(state.name)} --> [*]: [on enter]\n"
                        }
                }

                is Block.VoidNonEventBlock -> {
                    // TODO
                }

                else -> error("Will not happens since enter/exit blocks are non-event blocks")
            }
        }

        // what about InstanceNonEventDelete ?
    }
    return result
}

internal suspend fun <C : KlerkContext, V> renderAlgorithm(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    klerk: Klerk<*, V>
) {
    val algorithmName =
        URLDecoder.decode(call.parameters["name"], Charset.defaultCharset()) ?: throw IllegalArgumentException()
    val algorithm = AlgorithmDocumenter.getAlgorithm(algorithmName)

    call.respondHtml {
        head {}
        body {
            apply(addMermaidScript())
            h1 { +algorithm.name }
            pre(classes = "mermaid") {
                unsafe {
                    +generateFlowChart(algorithm)
                }
            }
        }
    }
}

private fun generateFlowChart(algo: FlowChartAlgorithm<*, *>): String {
    return """flowchart TD
        ${algo.nodes.joinToString(separator = System.lineSeparator()) { renderNode(it) }}
    """.trimMargin()
}

private fun renderNode(node: Node<*, *>): String {
    var result = ""
    node.goTos.forEach { goTo ->
        result += "${node.id}[${node.humanReadable}] --> |${goTo.key}| ${goTo.value}${System.lineSeparator()}"
    }
    node.terminations.forEach { termination ->
        result += "${node.id}[${node.humanReadable}] --> |${termination.key}| ${termination.value}(Result: ${termination.value})${System.lineSeparator()}"
    }
    return result
}

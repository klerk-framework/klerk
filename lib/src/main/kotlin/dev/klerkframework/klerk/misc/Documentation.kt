import dev.klerkframework.klerk.KlerkTranslation
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.misc.Node

import dev.klerkframework.klerk.statemachine.Block
import dev.klerkframework.klerk.statemachine.InstanceState
import dev.klerkframework.klerk.statemachine.State
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.klerk.statemachine.executables.InstanceEventDelete
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransitionWhen
import dev.klerkframework.klerk.statemachine.executables.InstanceEventUpdateModel
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventTransitionWhen
import dev.klerkframework.klerk.statemachine.executables.InstanceNonEventUpdateModel
import dev.klerkframework.klerk.statemachine.executables.VoidEventCreateModel
import kotlin.collections.forEach

public fun <V> generateStateDiagram(
    stateMachine: StateMachine<out Any, out Enum<*>, *, V>,
    showUpdateNotes: Boolean,
    translation: KlerkTranslation
): String {
    var result = "stateDiagram-v2\n"
    stateMachine.states.filterNot { it.name == "void" }.forEach { result += generateStateVariable(it) }
    result += generateVoidTransitions(stateMachine.voidState)
    result += generateTransitions(stateMachine.instanceStates, translation)
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

private fun <V> generateTransitions(states: List<InstanceState<out Any, out Enum<*>, *, V>>, translation: KlerkTranslation): String {
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
                            translation.function(branch.key)
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
                                    translation.function(branch.key)
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

public fun generateFlowChart(algo: FlowChartAlgorithm<*, *>): String {
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

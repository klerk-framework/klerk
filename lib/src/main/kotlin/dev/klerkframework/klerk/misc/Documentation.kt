package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.ExperimentalKlerkApi
import dev.klerkframework.klerk.KlerkTranslation
import dev.klerkframework.klerk.statemachine.*
import dev.klerkframework.klerk.statemachine.executables.*

/**
 * Renders [stateMachine] as a [Mermaid](https://mermaid.js.org/) `stateDiagram-v2` definition, for
 * documentation/tooling. If [showUpdateNotes] is true, each state gets a note listing the events that update properties
 * without transitioning.
 */
public fun <V> generateStateDiagram(
    stateMachine: StateMachine<out Any, out Enum<*>, *, V>,
    showUpdateNotes: Boolean,
    translation: KlerkTranslation,
): String = buildString {
    appendLine("stateDiagram-v2")
    for (state in stateMachine.states) {
        if (state.name != "void") {
            appendLine("${toVariable(state.name)}: ${state.name}")
        }
    }
    appendVoidTransitions(stateMachine.voidState)
    appendTransitions(stateMachine.instanceStates, translation)
    if (showUpdateNotes) {
        appendUpdateNotes(stateMachine.instanceStates)
    }
    appendDeleteTransitions(stateMachine.states)
}

private fun toVariable(name: String): String = name.replace(" ", "").lowercase()

private fun <V> StringBuilder.appendVoidTransitions(initialState: VoidState<out Any, out Enum<*>, *, V>) {
    for ((event, block) in initialState.onEventBlocks) {
        for (createModel in block.executables.filterIsInstance<CreateModel<*, *, *, *, V>>()) {
            appendLine("[*] --> ${toVariable(createModel.initialState.name)}: ${event.name}")
        }
    }
}

private fun <V> StringBuilder.appendTransitions(
    states: List<InstanceState<out Any, out Enum<*>, *, V>>,
    translation: KlerkTranslation,
) {
    for (state in states) {
        val from = toVariable(state.name)
        for ((event, block) in state.onEventBlocks) {
            for (transition in block.executables.filterIsInstance<Transition<*, *, *, *, V>>()) {
                appendLine("$from --> ${toVariable(transition.targetState.name)}: ${event.name}")
            }
            for (transition in block.executables.filterIsInstance<TransitionWhen<*, *, *, *, *>>()) {
                for ((condition, target) in transition.branches) {
                    appendLine("$from --> ${toVariable(target.name)}: ${translation.function(condition)}")
                }
            }
        }

        when (val enterBlock = state.enterBlock) {
            is Block.InstanceLifecycleBlock -> {
                for (transition in enterBlock.executables.filterIsInstance<Transition<*, *, *, *, *>>()) {
                    appendLine("$from --> ${toVariable(transition.targetState.name)}: [on enter]")
                }
                for (transition in enterBlock.executables.filterIsInstance<TransitionWhen<*, *, *, *, *>>()) {
                    for ((condition, target) in transition.branches) {
                        appendLine("$from --> ${toVariable(target.name)}: ${translation.function(condition)}")
                    }
                }
            }

            is Block.VoidLifecycleBlock -> {
                // TODO
            }

            else -> error("Will not happens since enter/exit blocks are non-event blocks")
        }
    }
}

private fun <V> StringBuilder.appendUpdateNotes(states: List<InstanceState<out Any, out Enum<*>, *, V>>) {
    for (state in states) {
        val updatingEvents = state.onEventBlocks
            .filter { (_, block) -> block.executables.any { it is UpdateModel<*, *, *, *> } }
            .map { (event, _) -> event.name }
        if (updatingEvents.isEmpty()) {
            continue
        }
        appendLine("note left of ${toVariable(state.name)}")
        appendLine("    Events that updates properties:")
        for (name in updatingEvents) {
            appendLine("    • $name")
        }
        appendLine("end note")
    }
}

private fun <V> StringBuilder.appendDeleteTransitions(states: List<State<out Any, out Enum<*>, *, V>>) {
    for (state in states) {
        val from = toVariable(state.name)
        if (state is InstanceState) {
            for ((event, block) in state.onEventBlocks) {
                repeat(block.executables.count { it is DeleteModel<*, *, *, *> }) {
                    appendLine("$from --> [*]: ${event.name}")
                }
            }
        }

        when (val enterBlock = state.enterBlock) {
            is Block.InstanceLifecycleBlock -> {
                repeat(enterBlock.executables.count { it is DeleteModel<*, *, *, *> }) {
                    appendLine("$from --> [*]: [on enter]")
                }
            }

            is Block.VoidLifecycleBlock -> {
                // TODO
            }

            else -> error("Will not happens since enter/exit blocks are non-event blocks")
        }

        // what about InstanceLifecycleDelete ?
    }
}

/**
 * Renders [algo]'s nodes as a [Mermaid](https://mermaid.js.org/) `flowchart TD` definition, for documentation/tooling.
 */
@ExperimentalKlerkApi
public fun generateFlowChart(algo: FlowChartAlgorithm<*, *>): String = buildString {
    appendLine("flowchart TD")
    for (node in algo.nodes) {
        appendNode(node)
    }
}

@OptIn(ExperimentalKlerkApi::class)
private fun StringBuilder.appendNode(node: Node<*, *>) {
    val label = "${node.id}[${node.humanReadable}]"
    for ((condition, target) in node.goTos) {
        appendLine("$label --> |$condition| $target")
    }
    for ((condition, result) in node.terminations) {
        appendLine("$label --> |$condition| $result(Result: $result)")
    }
}

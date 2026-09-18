package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.KlerkTranslation
import dev.klerkframework.klerk.statemachine.executables.CreateModel
import dev.klerkframework.klerk.statemachine.executables.DeleteModel
import dev.klerkframework.klerk.statemachine.executables.Transition
import dev.klerkframework.klerk.statemachine.executables.TransitionWhen
import dev.klerkframework.klerk.statemachine.executables.UpdateModel

/**
 * One edge in a [StateMachineDiagram]. A null [from] or [to] represents the diagram's start/end pseudostate
 * (`[*]` in a Mermaid `stateDiagram-v2`).
 */
public data class StateDiagramTransition(public val from: String?, public val to: String?, public val label: String)

/** A plain description of a [StateMachine]'s states and transitions, for rendering as a diagram. */
public data class StateMachineDiagram(
    public val stateNames: List<String>,
    public val voidTransitions: List<StateDiagramTransition>,
    public val transitions: List<StateDiagramTransition>,
    public val updateNotesByState: Map<String, List<String>>,
    public val deleteTransitions: List<StateDiagramTransition>,
)

/** Builds a [StateMachineDiagram] describing this state machine's states and transitions. */
public fun <V> StateMachine<out Any, out Enum<*>, *, V>.toDiagram(translation: KlerkTranslation): StateMachineDiagram {
    val stateNames = states.filter { it.name != "void" }.map { it.name }

    val voidTransitions = mutableListOf<StateDiagramTransition>()
    for ((event, block) in voidState.onEventBlocks) {
        for (createModel in block.executables.filterIsInstance<CreateModel<*, *, *, *, V>>()) {
            voidTransitions.add(StateDiagramTransition(null, createModel.initialState.name, event.name))
        }
    }

    val transitions = mutableListOf<StateDiagramTransition>()
    for (state in instanceStates) {
        val from = state.name
        for ((event, block) in state.onEventBlocks) {
            for (transition in block.executables.filterIsInstance<Transition<*, *, *, *, V>>()) {
                transitions.add(StateDiagramTransition(from, transition.targetState.name, event.name))
            }
            for (transition in block.executables.filterIsInstance<TransitionWhen<*, *, *, *, *>>()) {
                for ((condition, target) in transition.branches) {
                    transitions.add(StateDiagramTransition(from, target.name, translation.function(condition)))
                }
            }
        }
        when (val enterBlock = state.enterBlock) {
            is Block.InstanceLifecycleBlock -> {
                for (transition in enterBlock.executables.filterIsInstance<Transition<*, *, *, *, *>>()) {
                    transitions.add(StateDiagramTransition(from, transition.targetState.name, "[on enter]"))
                }
                for (transition in enterBlock.executables.filterIsInstance<TransitionWhen<*, *, *, *, *>>()) {
                    for ((condition, target) in transition.branches) {
                        transitions.add(StateDiagramTransition(from, target.name, translation.function(condition)))
                    }
                }
            }

            is Block.VoidLifecycleBlock -> {
                // TODO
            }

            else -> error("Will not happens since enter/exit blocks are non-event blocks")
        }
    }

    val updateNotesByState = instanceStates.associate { state ->
        state.name to state.onEventBlocks
            .filter { (_, block) -> block.executables.any { it is UpdateModel<*, *, *, *> } }
            .map { (event, _) -> event.name }
    }.filterValues { it.isNotEmpty() }

    val deleteTransitions = mutableListOf<StateDiagramTransition>()
    for (state in states) {
        val from = state.name
        if (state is InstanceState) {
            for ((event, block) in state.onEventBlocks) {
                repeat(block.executables.count { it is DeleteModel<*, *, *, *> }) {
                    deleteTransitions.add(StateDiagramTransition(from, null, event.name))
                }
            }
        }

        when (val enterBlock = state.enterBlock) {
            is Block.InstanceLifecycleBlock -> {
                repeat(enterBlock.executables.count { it is DeleteModel<*, *, *, *> }) {
                    deleteTransitions.add(StateDiagramTransition(from, null, "[on enter]"))
                }
            }

            is Block.VoidLifecycleBlock -> {
                // TODO
            }

            else -> error("Will not happens since enter/exit blocks are non-event blocks")
        }

        // what about InstanceLifecycleDelete ?
    }

    return StateMachineDiagram(stateNames, voidTransitions, transitions, updateNotesByState, deleteTransitions)
}

package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.statemachine.StateMachine

internal fun <T : Any, P, C : KlerkContext, V> getStateMachine(
    command: Command<T, P>,
    managedModels: Set<ManagedModel<out Any, *, C, V>>,
): StateMachine<T, *, C, V> {
    val stateMachine =
        managedModels.find { it.stateMachine.knowsAboutEvent(command.event.id) }?.stateMachine
            ?: error("Can't find state machine for event '${command.event}'")
    @Suppress("UNCHECKED_CAST")
    return stateMachine as StateMachine<T, *, C, V>
}

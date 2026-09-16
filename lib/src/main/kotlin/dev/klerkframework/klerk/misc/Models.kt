package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.StateMachine
import java.util.*

internal fun <T : Any, P, C : KlerkContext, V> getStateMachine(
    command: Command<T, P>,
    managedModels: Set<ManagedModel<out Any, *, C, V>>,
): StateMachine<T, *, C, V> {
    val stateMachine =
        managedModels.find { it.stateMachine.knowsAboutEvent(command.event.id) }?.stateMachine
            ?: throw RuntimeException("Can't find state machine for event '${command.event}'")
    @Suppress("UNCHECKED_CAST")
    return stateMachine as StateMachine<T, *, C, V>
}

/** Checks that every [ModelID] in the model's props, also in collections and nested objects, refers to a model. */
internal fun <C : KlerkContext, V> verifyReferencesExist(model: Model<*>, reader: ModelReader<C, V>): Problem? {
    for (leaf in ObjectSchema.of(model.props::class).leaves(model.props)) {
        val id = leaf.value as? ModelID<*> ?: continue
        try {
            @Suppress("UNCHECKED_CAST")
            reader.get(id as ModelID<Any>)
        } catch (e: NoSuchElementException) {
            return NotFoundProblem(e.message ?: "Could not find the model with id $id")
        }
    }
    return null
}

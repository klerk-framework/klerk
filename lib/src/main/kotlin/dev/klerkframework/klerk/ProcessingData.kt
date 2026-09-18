package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.job.PendingJob
import dev.klerkframework.klerk.misc.IdProvider
import dev.klerkframework.klerk.statemachine.Block
import dev.klerkframework.klerk.statemachine.UnmanagedJob
import kotlin.time.Instant

/**
 * Note that lists are used when ordering matters and maps are used for models as it is possible that a model is
 * updated/transitioned more than once for the same command.
 *
 * @property primaryModel is the model that was created or manipulated by the primary command (i.e. the command that
 * the user initiated).
 * @property currentModel is the model that was created or manipulated by the current command. Will be the same as
 * primary unless the current command is a sub-command.
 * @property aggregatedModelState the models as they are after the last block was executed
 * @property createdModels models that were created. Note that they may have been modified after creation, which means
 * that the model in this map may NOT be the final outcome for this model. See modifiedModels instead.
 * @property updatedModels models that were updated. Note that they may have been modified after the update, which means
 * that the model in this map may NOT be the final outcome for this model. See modifiedModels instead.
 * @property transitions models that changed state. Note that they may have been modified after the transition, which
 * means that the model in this map may NOT be the final outcome for this model. See modifiedModels instead.
 */
internal data class ProcessingData<Primary : Any, C : KlerkContext, V>(
    val primaryModel: ModelID<Primary>? = null,
    val currentModel: ModelID<out Any>? = null,
    val unmanagedJobs: List<UnmanagedJob> = emptyList(),
    val createdModels: List<ModelID<out Any>> = emptyList(),
    val updatedModels: List<ModelID<out Any>> = emptyList(),
    val transitions: List<ModelID<out Any>> = emptyList(),
    val deletedModels: List<ModelID<out Any>> = emptyList(),
    val unFinalizedTransition: UnfinalizedTransition? = null,
    val aggregatedModelState: Map<ModelID<out Any>, Model<out Any>> = emptyMap(),
    val newJobs: List<PendingJob<C, V>> = emptyList(),
    val remainingBlocks: List<Block<*, *, C, V>> = emptyList(),
    val currentBlock: Block<*, *, C, V>? = null,
    val processedBlocks: List<Block<*, *, C, V>> = emptyList(),
    val remainingCommands: List<Command<out Any, out Any?>> = emptyList(),
    val currentCommand: Command<out Any, out Any?>? = null,
    val processedCommands: List<Command<out Any, out Any?>> = emptyList(),
    val log: List<String> = emptyList(),
    val functionsToUpdateViews: List<() -> Unit> = emptyList(),
    val problems: List<Problem> = emptyList(),
    val timeTriggers: Map<ModelID<out Any>, Instant?> = emptyMap(),
    val remainingTimeTrigger: Model<out Any>? = null,
) {

    override fun toString(): String = currentBlock?.name ?: "unknown"

    /**
     * If [subsequentIsExitBlock], an exit block has been processed. We thus know there was a transition, and can now
     * update the model with transition info.
     */
    internal fun merge(
        subsequent: ProcessingData<Primary, C, V>,
        subsequentIsExitBlock: Boolean = false,
    ): ProcessingData<Primary, C, V> {
        val (updatedModifiedModels, updatedTransitions, toFinalize) = calculateModifiedModels(
            subsequent,
            subsequentIsExitBlock,
        )
        return copy(
            currentModel = currentModel ?: subsequent.currentModel,
            unmanagedJobs = unmanagedJobs.plus(subsequent.unmanagedJobs),
            createdModels = createdModels.plus(subsequent.createdModels),
            updatedModels = updatedModels.plus(subsequent.updatedModels),
            deletedModels = deletedModels.plus(subsequent.deletedModels),
            transitions = updatedTransitions,
            unFinalizedTransition = toFinalize,
            aggregatedModelState = updatedModifiedModels,
            newJobs = newJobs.plus(subsequent.newJobs),
            remainingBlocks = remainingBlocks.plus(subsequent.remainingBlocks),
            processedBlocks = currentBlock?.let { processedBlocks.plus(it) } ?: processedBlocks,
            remainingCommands = remainingCommands.plus(subsequent.remainingCommands),
            processedCommands = currentCommand?.let { processedCommands.plus(it) } ?: processedCommands,
            functionsToUpdateViews = functionsToUpdateViews.plus(subsequent.functionsToUpdateViews),
            problems = problems.plus(subsequent.problems),
            remainingTimeTrigger = subsequent.remainingTimeTrigger,
            log = log.plus(subsequent.log),
        )
    }

    internal data class CalculatedStuff(
        val modified: Map<ModelID<out Any>, Model<out Any>>,
        val transitions: List<ModelID<out Any>>,
        val toFinalize: UnfinalizedTransition?,
    )

    /**
     * Calculates the modified models and transitions after the block that produced [subsequent].
     *
     * If [finalizeTransition], an exit block has been processed. We thus know there was a transition or creation, and
     * can now update the model with transition info.
     */
    private fun <Primary : Any, C : KlerkContext, V> calculateModifiedModels(
        subsequent: ProcessingData<Primary, C, V>,
        finalizeTransition: Boolean,
    ): CalculatedStuff {
        val modified = aggregatedModelState.toMutableMap()
        val newTransitions = transitions.toMutableList()
        modified -= subsequent.deletedModels

        if (finalizeTransition) {
            val id = requireNotNull(currentModel ?: subsequent.currentModel)
            require(subsequent.transitions.isEmpty()) { "There can be no transition in exit blocks" }
            // we will now apply the transition from the previous block instead
            val (newState, time) = requireNotNull(unFinalizedTransition)

            // we first look in subsequent (updates in exit-block).
            val inModified =
                subsequent.aggregatedModelState[currentModel] ?: modified[currentModel] ?: unFinalizedTransition.model

            modified[id] = inModified.copy(
                state = newState,
                lastStateTransitionAt = time,
            )

            newTransitions.add(id)
        } else {
            modified.putAll(subsequent.aggregatedModelState)
        }

        val toFinalize = if (finalizeTransition) null else subsequent.unFinalizedTransition ?: unFinalizedTransition
        return CalculatedStuff(modified, newTransitions, toFinalize)
    }

    internal fun withTimeTriggersOnModels(): ProcessingData<Primary, C, V> {
        val aggStates = aggregatedModelState.toMutableMap()
        for ((id, instant) in timeTriggers) {
            aggStates[id] = aggregatedModelState.getValue(id).copy(timeTrigger = instant)
        }
        return this.copy(
            aggregatedModelState = aggStates,
        )
    }
}

internal data class EventProcessingOptions(
    val storeEvent: Boolean = true,
    val performActions: Boolean,
    val notifyListeners: Boolean = true,
    val preventModelUpdates: Boolean,
    val idProvider: IdProvider,
    val disregardPreventingRules: Boolean,
)

/** A state change whose [time] and [state] are written to [model] once its exit blocks have run. */
internal data class UnfinalizedTransition(val state: String, val time: Instant, val model: Model<out Any>)

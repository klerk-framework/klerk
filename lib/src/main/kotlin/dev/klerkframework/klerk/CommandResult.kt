package dev.klerkframework.klerk

import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.read.isAuthorized
import dev.klerkframework.klerk.statemachine.GeneralAction

public sealed class CommandResult<T : Any, C : dev.klerkframework.klerk.KlerkContext, V> {

    public fun orThrow(): dev.klerkframework.klerk.CommandResult.Success<T, C, V> {
        return when (this) {
            is dev.klerkframework.klerk.CommandResult.Failure -> throw this.problem.asException()
            is dev.klerkframework.klerk.CommandResult.Success -> this
        }
    }

    public fun getOrHandle(default: (dev.klerkframework.klerk.CommandResult.Failure<T, C, V>) -> dev.klerkframework.klerk.CommandResult.Success<T, C, V>): dev.klerkframework.klerk.CommandResult.Success<T, C, V> {
        return when (this) {
            is dev.klerkframework.klerk.CommandResult.Failure -> default(this)
            is dev.klerkframework.klerk.CommandResult.Success -> this
        }
    }

    /**
     * Note that the reason this doesn't return the whole models is because it is easy to make a mistake and show the result to the user by mistake when the user does not have permission to see the models.
     * @property primaryModel the model that was created or updated directly by the command in contrast to by secondary events.
     * @property authorizedModels contains the models as they are after the command. Note that if the context doesn't
     * allow reading any of the models, that model will not be present.
     */
    public data class Success<T : Any, C : dev.klerkframework.klerk.KlerkContext, V>(
        val primaryModel: dev.klerkframework.klerk.ModelID<T>?,
        val createdModels: List<dev.klerkframework.klerk.ModelID<out Any>>,
        val modelsWithUpdatedProps: List<dev.klerkframework.klerk.ModelID<out Any>>,
        val deletedModels: List<dev.klerkframework.klerk.ModelID<out Any>>,
        val transitionedModels: List<dev.klerkframework.klerk.ModelID<out Any>>,
        val secondaryEvents: List<dev.klerkframework.klerk.EventReference>,
        val jobs: List<Job<C, V>>,
        val actions: List<GeneralAction>,
        val authorizedModels: Map<dev.klerkframework.klerk.ModelID<out Any>, dev.klerkframework.klerk.Model<out Any>>,
        val log: List<String>,
    ) : dev.klerkframework.klerk.CommandResult<T, C, V>()

    public data class Failure<T : Any, C : dev.klerkframework.klerk.KlerkContext, V>(val problem: dev.klerkframework.klerk.Problem) :
        dev.klerkframework.klerk.CommandResult<T, C, V>()

    internal companion object {
        fun <T : Any, V, C : dev.klerkframework.klerk.KlerkContext> from(
            delta: dev.klerkframework.klerk.ProcessingData<T, C, V>,
            reader: ReaderWithoutAuth<C, V>,
            context: C,
            config: dev.klerkframework.klerk.Config<C, V>
        ): dev.klerkframework.klerk.CommandResult<T, C, V> {
            return if (delta.problems.isEmpty()) {
                _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success(
                    primaryModel = delta.primaryModel as dev.klerkframework.klerk.ModelID<T>,
                    createdModels = delta.createdModels,
                    modelsWithUpdatedProps = delta.updatedModels,
                    deletedModels = delta.deletedModels,
                    transitionedModels = delta.transitions,
                    jobs = delta.newJobs,
                    secondaryEvents = emptyList(),
                    actions = delta.actions,
                    authorizedModels = delta.aggregatedModelState.filter {
                        isAuthorized(
                            it.value,
                            context,
                            config,
                            reader
                        )
                    },
                    log = delta.log
                )
            } else _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure(delta.problems.first())
        }
    }
}

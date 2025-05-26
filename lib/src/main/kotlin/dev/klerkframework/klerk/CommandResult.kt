package dev.klerkframework.klerk

import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.read.isAuthorized
import dev.klerkframework.klerk.statemachine.UnmanagedJob

public sealed class CommandResult<T : Any, C : KlerkContext, V> {

    public fun orThrow(): Success<T, C, V> {
        return when (this) {
            is Failure -> throw this.problems.firstOrNull()?.asException() ?: RuntimeException("Unknown problem")
            is Success -> this
        }
    }

    public fun getOrHandle(default: (Failure<T, C, V>) -> Success<T, C, V>): Success<T, C, V> {
        return when (this) {
            is Failure -> default(this)
            is Success -> this
        }
    }

    /**
     * Note that the reason this doesn't return the whole models is because it is easy to make a mistake and show the result to the user by mistake when the user does not have permission to see the models.
     * @property primaryModel the model that was created or updated directly by the command in contrast to by secondary events.
     * @property authorizedModels contains the models as they are after the command. Note that if the context doesn't
     * allow reading any of the models, that model will not be present.
     */
    public data class Success<T : Any, C : KlerkContext, V>(
        val primaryModel: ModelID<T>?,
        val createdModels: List<ModelID<out Any>>,
        val modelsWithUpdatedProps: List<ModelID<out Any>>,
        val deletedModels: List<ModelID<out Any>>,
        val transitionedModels: List<ModelID<out Any>>,
        val secondaryEvents: List<EventReference>,
        val jobs: List<Job<C, V>>,
        val unmanagedJobs: List<UnmanagedJob>,
        val authorizedModels: Map<ModelID<out Any>, Model<out Any>>,
        val log: List<String>,
    ) : CommandResult<T, C, V>()

    public data class Failure<T : Any, C : KlerkContext, V>(val problems: List<Problem>) :
        CommandResult<T, C, V>()

    internal companion object {
        fun <T : Any, V, C : KlerkContext> from(
            delta: ProcessingData<T, C, V>,
            reader: ReaderWithoutAuth<C, V>,
            context: C,
            config: Config<C, V>
        ): CommandResult<T, C, V> {
            return if (delta.problems.isEmpty()) {
                Success(
                    primaryModel = delta.primaryModel as ModelID<T>,
                    createdModels = delta.createdModels,
                    modelsWithUpdatedProps = delta.updatedModels,
                    deletedModels = delta.deletedModels,
                    transitionedModels = delta.transitions,
                    jobs = delta.newJobs,
                    secondaryEvents = emptyList(),
                    unmanagedJobs = delta.unmanagedJobs,
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
            } else Failure(delta.problems)
        }
    }
}

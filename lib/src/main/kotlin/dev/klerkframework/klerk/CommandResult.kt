package dev.klerkframework.klerk

import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.read.PropertyAuthScope
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.read.isAuthorized
import dev.klerkframework.klerk.statemachine.UnmanagedJob

/**
 * Outcome of `Klerk.handle`: either [Success] or [Failure]. Use a `when` on the sealed type, or [orThrow]/
 * [getOrHandle] for a terser call site.
 */
public sealed class CommandResult<T : Any, C : KlerkContext, V> {

    /**
     * @return this as [Success].
     * @throws Exception the first [Problem]'s [Problem.asException] (e.g. [AuthorizationException],
     * [IllegalStateException], [IllegalArgumentException]) if this is a [Failure].
     */
    public fun orThrow(): Success<T, C, V> {
        return when (this) {
            is Failure -> throw this.problems.firstOrNull()?.asException() ?: RuntimeException("Unknown problem")
            is Success -> this
        }
    }

    /** Returns this as [Success], or the [Success] produced by [default] from this [Failure] otherwise. */
    public fun getOrHandle(default: (Failure<T, C, V>) -> Success<T, C, V>): Success<T, C, V> {
        return when (this) {
            is Failure -> default(this)
            is Success -> this
        }
    }

    /**
     * The result of a successfully processed command.
     *
     * Only [ModelID]s are exposed here rather than full [Model]s (except via [authorizedModels]) so that it isn't
     * easy to accidentally leak a model's data to a caller who isn't authorized to read it; fetch data instead via
     * a [dev.klerkframework.klerk.read.Reader] using the same context, or from [authorizedModels].
     *
     * @property primaryModel the model that was created or updated directly by the command, as opposed to by
     * secondary events triggered as a consequence. Null if the command's event doesn't target/produce a model
     * that the caller can be told about (e.g. a dry run, or a void event with no created model).
     * @property createdModels all models created as part of processing this command (including secondary events).
     * @property modelsWithUpdatedProps all models whose properties were changed.
     * @property deletedModels all models deleted as part of processing this command.
     * @property transitionedModels all models that changed state machine state.
     * @property secondaryEvents events that were triggered as a consequence of this command (e.g. by `onEnter`/time
     * triggers), in addition to the command's own event.
     * @property jobs the ids of the managed jobs this command scheduled, in declaration order.
     * @property unmanagedJobs unmanaged jobs that were scheduled as a result of this command.
     * @property authorizedModels the affected models as they are after the command, keyed by ID. A model is present
     * only if [context][C] is authorized to read it — absence does not mean the model wasn't affected.
     * @property log human-readable trace of processing steps, populated when requested via
     * [dev.klerkframework.klerk.command.DebugOptions].
     */
    public data class Success<T : Any, C : KlerkContext, V>(
        val primaryModel: ModelID<T>?,
        val createdModels: List<ModelID<out Any>>,
        val modelsWithUpdatedProps: List<ModelID<out Any>>,
        val deletedModels: List<ModelID<out Any>>,
        val transitionedModels: List<ModelID<out Any>>,
        val secondaryEvents: List<EventReference>,
        val jobs: List<JobId>,
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
            specification: Specification<C, V>
        ): CommandResult<T, C, V> {
            if (delta.problems.isNotEmpty()) {
                return Failure(delta.problems)
            }

            // The models handed to the caller must have the property authorization applied, just like the models that
            // come out of a Reader.
            val propertyAuth = PropertyAuthScope(context, specification, reader)
            val authorized = delta.aggregatedModelState
                .mapValues { (_, model) -> propertyAuth.secure(model) }
                .filter { isAuthorized(it.value, context, specification, reader) }

            return Success(
                primaryModel = delta.primaryModel as ModelID<T>,
                createdModels = delta.createdModels,
                modelsWithUpdatedProps = delta.updatedModels,
                deletedModels = delta.deletedModels,
                transitionedModels = delta.transitions,
                jobs = delta.newJobs.map { it.id },
                secondaryEvents = emptyList(),
                unmanagedJobs = delta.unmanagedJobs,
                authorizedModels = authorized,
                log = delta.log
            )
        }
    }
}

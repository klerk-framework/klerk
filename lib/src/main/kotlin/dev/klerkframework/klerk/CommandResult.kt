package dev.klerkframework.klerk

import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.read.PropertyAuthScope
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.read.isAuthorized

/**
 * Outcome of `Klerk.handle`: either [Success] or [Failure]. Use a `when` on the sealed type, or [getOrThrow],
 * [getOrElse] or [fold] for a terser call site.
 */
public sealed class CommandResult<T : Any> {

    /**
     * @return this as [Success].
     * @throws Exception the first [Problem]'s [Problem.asException] (e.g. [AuthorizationException],
     * [IllegalStateException], [IllegalArgumentException]) if this is a [Failure].
     */
    public fun getOrThrow(): Success<T> {
        return when (this) {
            is Failure -> throw this.problems.firstOrNull()?.asException() ?: RuntimeException("Unknown problem")
            is Success -> this
        }
    }

    /**
     * Returns this as [Success], or calls [onFailure] otherwise. [onFailure] typically leaves the enclosing function
     * with `return` or `throw`; to turn either outcome into another value, use [fold].
     */
    public inline fun getOrElse(onFailure: (Failure<T>) -> Success<T>): Success<T> {
        return when (this) {
            is Failure -> onFailure(this)
            is Success -> this
        }
    }

    /** Returns the result of [onSuccess] or [onFailure], depending on the outcome. */
    public inline fun <R> fold(onSuccess: (Success<T>) -> R, onFailure: (Failure<T>) -> R): R {
        return when (this) {
            is Failure -> onFailure(this)
            is Success -> onSuccess(this)
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
     * secondary events triggered as a consequence. For an instance event it is the command's model; for a void event
     * it is the model the event created, or null if it created none or more than one. A dry run reports the same.
     * @property createdModels all models created as part of processing this command (including secondary events).
     * @property updatedModels all models whose properties were changed.
     * @property deletedModels all models deleted as part of processing this command.
     * @property transitionedModels all models that changed state machine state.
     * @property jobs the ids of the managed jobs this command scheduled, in declaration order.
     * @property unmanagedJobs the functions passed to `unmanagedJob(...)` that this command started, e.g. for
     * `assertContains(result.unmanagedJobs, ::sendWelcomeMail)` in a test.
     * @property authorizedModels the affected models as they are after the command, keyed by ID. A model is present
     * only if the context that issued the command is authorized to read it — absence does not mean the model wasn't
     * affected.
     * @property log human-readable trace of the processing steps, e.g. which function created a model or which
     * transition was made. Always populated.
     */
    public data class Success<T : Any>(
        val primaryModel: ModelID<T>?,
        val createdModels: Set<ModelID<out Any>>,
        val updatedModels: Set<ModelID<out Any>>,
        val deletedModels: Set<ModelID<out Any>>,
        val transitionedModels: Set<ModelID<out Any>>,
        val jobs: List<JobId>,
        val unmanagedJobs: List<Function<*>>,
        val authorizedModels: Map<ModelID<out Any>, Model<out Any>>,
        val log: List<String>,
    ) : CommandResult<T>() {

        /** The model with [id] from [authorizedModels], or null if it is absent. */
        @Suppress("UNCHECKED_CAST")
        public fun <M : Any> authorizedModel(id: ModelID<M>): Model<M>? = authorizedModels[id] as Model<M>?

        /** The [primaryModel] from [authorizedModels], or null if there is none or the context may not read it. */
        public val authorizedPrimaryModel: Model<T>? get() = primaryModel?.let { authorizedModel(it) }
    }

    public data class Failure<T : Any>(val problems: List<Problem>) :
        CommandResult<T>()

    internal companion object {
        fun <T : Any, V, C : KlerkContext> from(
            delta: ProcessingData<T, C, V>,
            reader: ReaderWithoutAuth<C, V>,
            context: C,
            specification: Specification<C, V>,
            allowBypassAuthRead: Boolean,
        ): CommandResult<T> {
            if (delta.problems.isNotEmpty()) {
                return Failure(delta.problems)
            }

            // The models handed to the caller must have the property authorization applied, just like the models that
            // come out of a Reader.
            val propertyAuth = PropertyAuthScope(context, specification, reader, allowBypassAuthRead)
            val authorized = delta.aggregatedModelState
                .filter { isAuthorized(it.value, context, specification, reader) }
                .mapValues { (_, model) -> propertyAuth.secure(model) }

            return Success(
                primaryModel = delta.primaryModel as ModelID<T>,
                createdModels = delta.createdModels.toSet(),
                updatedModels = delta.updatedModels.toSet(),
                deletedModels = delta.deletedModels.toSet(),
                transitionedModels = delta.transitions.toSet(),
                jobs = delta.newJobs.map { it.id },
                unmanagedJobs = delta.unmanagedJobs.map { it.function },
                authorizedModels = authorized,
                log = delta.log
            )
        }
    }
}

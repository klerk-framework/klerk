package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

internal class KlerkModelsImpl<C : KlerkContext, V>(
    private val klerk: KlerkImpl<C, V>,
    private val readWriteLock: ReadWriteLock,
) : KlerkModels<C, V> {

    private val modelsFlow: MutableSharedFlow<ModelModification> = MutableSharedFlow()

    override fun subscribe(
        context: C,
        id: ModelID<out Any>?
    ): Flow<ModelModification> {
        // Do we need a separate authorization for subscriptions?
        return modelsFlow
    }

    override suspend fun <T : Any> unsafeCreate(context: C, model: Model<T>) {
        check(klerk.settings.allowUnsafeOperations) { "The setting 'allowUnsafeOperations' must be enabled" }
        readWriteLock.withWrite {
            check(ModelCache.read(model.id).getOrNull() == null) { "There already exists a model with that ID" }
            ModelCache.store(model)
        }
    }

    override suspend fun <T : Any> unsafeUpdate(context: C, model: Model<T>) {
        check(klerk.settings.allowUnsafeOperations) { "The setting 'allowUnsafeOperations' must be enabled" }
        readWriteLock.withWrite {
            checkNotNull(ModelCache.read(model.id).getOrNull()) { "There is no model with that ID" }
            ModelCache.store(model)
        }
    }

    override suspend fun <T : Any> unsafeDelete(context: C, id: ModelID<T>) {
        check(klerk.settings.allowUnsafeOperations) { "The setting 'allowUnsafeOperations' must be enabled" }
        readWriteLock.withWrite {
            val original = ModelCache.read(id).getOrNull()
            checkNotNull(original) { "There is no model with that ID" }
            ModelCache.delete(original.id)
        }
    }

    internal suspend fun <T> read(context: C, readFunction: Reader<C, V>.() -> T): T {
        val reader = ReaderWithAuth(klerk, context)
        return readWriteLock.withRead {
            try {
                val result = ReadBlockGuard.withThreadMarker { reader.readFunction() }
                klerk.log.addReads(reader.modelsRead.distinctBy { it.id }, context)
                result
            } finally {
                reader.finishRead()
            }
        }
    }

    internal suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, V>.() -> T): T {
        val reader = ReaderWithAuth(klerk, context)
        return readWriteLock.withRead {
            try {
                withContext(ReadBlockGuard.Marker()) { reader.readFunction() }
            } finally {
                reader.finishRead()
            }
        }
    }

    suspend fun modelWasModified(modification: ModelModification) {
        modelsFlow.emit(modification)
    }

}

/**
 * An event emitted by [dev.klerkframework.klerk.KlerkModels.subscribe] describing how a model changed.
 */
public sealed class ModelModification(public val id: ModelID<out Any>) {
    public class Created(id: ModelID<out Any>) : ModelModification(id)
    public class PropsUpdated(id: ModelID<out Any>) : ModelModification(id)
    public class Transitioned(id: ModelID<out Any>) : ModelModification(id)
    public class Deleted(id: ModelID<out Any>) : ModelModification(id)
}

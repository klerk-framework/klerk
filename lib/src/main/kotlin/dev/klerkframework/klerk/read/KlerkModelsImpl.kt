package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

internal class KlerkModelsImpl<C : KlerkContext, V>(
    private val klerk: KlerkImpl<C, V>,
    private val readWriteLock: ReadWriteLock,
) : KlerkModelChanges<C, V>, KlerkUnsafe<C> {

    private val modelsFlow: MutableSharedFlow<ModelModification> = MutableSharedFlow()

    // A deleted model can no longer be authorized against, and a Deleted carries nothing but the id and class.
    override fun subscribe(
        id: ModelID<out Any>?,
        context: C,
    ): Flow<ModelModification> =
        modelsFlow
            .filter { id == null || it.id == id }
            .filter { it is ModelModification.Deleted || isReadable(it.id, context) }

    private suspend fun isReadable(id: ModelID<out Any>, context: C): Boolean =
        context.actor == SystemIdentity || readWriteLock.withRead {
            val model = ModelCache.getOrNull(id) ?: return@withRead false
            isAuthorized(model, context, klerk.specification, ReaderWithoutAuth(klerk))
        }

    override suspend fun <T : Any> create(model: Model<T>, context: C) {
        check(klerk.settings.allowUnsafeOperations) { "The setting 'allowUnsafeOperations' must be enabled" }
        readWriteLock.withWrite {
            check(ModelCache.read(model.id).getOrNull() == null) { "There already exists a model with that ID" }
            ModelCache.store(model)
        }
    }

    override suspend fun <T : Any> update(model: Model<T>, context: C) {
        check(klerk.settings.allowUnsafeOperations) { "The setting 'allowUnsafeOperations' must be enabled" }
        readWriteLock.withWrite {
            checkNotNull(ModelCache.read(model.id).getOrNull()) { "There is no model with that ID" }
            ModelCache.store(model)
        }
    }

    override suspend fun <T : Any> delete(id: ModelID<T>, context: C) {
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
                klerk.activityLogImpl.addReads(reader.modelsRead.distinctBy { it.id }, context)
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

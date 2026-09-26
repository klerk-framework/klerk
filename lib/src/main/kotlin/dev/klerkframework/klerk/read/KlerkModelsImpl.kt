package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.KlerkModelChanges
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

internal class KlerkModelsImpl<C : KlerkContext, V>(
    private val klerk: KlerkImpl<C, V>,
    private val readWriteLock: ReadWriteLock,
) : KlerkModelChanges<C, V> {

    // Buffered and never suspending: changes are published while the command mutex is held, so waiting for a
    // subscriber would stall every command, and deadlock one that issues a command itself.
    private val modelsFlow = MutableSharedFlow<ModelModification>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // A deleted model can no longer be authorized against, and a Deleted carries nothing but the id and class.
    override fun subscribe(id: ModelID<out Any>?, context: C): Flow<ModelModification> = modelsFlow
        .filter { id == null || it.id == id }
        .filter { it is ModelModification.Deleted || isReadable(it.id, context) }

    private suspend fun isReadable(id: ModelID<out Any>, context: C): Boolean = context.actor == SystemIdentity ||
        readWriteLock.withRead {
            val model = ModelCache.getOrNull(id) ?: return@withRead false
            isAuthorized(model, context, klerk.specification, ReaderWithoutAuth(klerk))
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

    fun modelWasModified(modification: ModelModification) {
        modelsFlow.tryEmit(modification)
    }
}

/** How many changes a subscriber may fall behind before it misses the oldest. */
internal const val CHANGE_BUFFER = 1024

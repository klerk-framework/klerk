package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.misc.ReadWriteLock
import kotlinx.coroutines.withContext

internal class KlerkModelsImpl<C : KlerkContext, V>(
    private val klerk: KlerkImpl<C, V>,
    private val readWriteLock: ReadWriteLock,
) {

    internal suspend fun <T> read(context: C, readFunction: Reader<C, V>.() -> T): T {
        val reader = ReaderWithAuth(klerk, context)
        return readWriteLock.withRead {
            try {
                ReadBlockGuard.withThreadMarker { reader.readFunction() }
            } finally {
                finish(reader, context)
            }
        }
    }

    internal suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, V>.() -> T): T {
        val reader = ReaderWithAuth(klerk, context)
        return readWriteLock.withRead {
            try {
                withContext(ReadBlockGuard.Marker()) { reader.readFunction() }
            } finally {
                finish(reader, context)
            }
        }
    }

    private fun finish(reader: ReaderWithAuth<C, V>, context: C) {
        reader.finishRead()
        klerk.activityLogImpl.addReads(reader.readModels, context)
    }
}

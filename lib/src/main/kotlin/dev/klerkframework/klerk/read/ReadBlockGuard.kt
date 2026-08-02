package dev.klerkframework.klerk.read

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Knows whether the caller is currently inside a read block (i.e. inside [dev.klerkframework.klerk.Klerk.read] or
 * [dev.klerkframework.klerk.Klerk.readSuspend]).
 *
 * This exists so that operations which must not run under the read lock can say so instead of deadlocking or silently
 * nesting. The read lock is held for the whole read block and serializes commands too, so
 * [dev.klerkframework.klerk.KlerkAttachedData.get] streaming a large value under it would stall the application.
 *
 * Two mechanisms are needed because a read block can be entered in two ways. A non-suspending read block cannot switch
 * threads, so a thread local catches anything it calls (including a nested `runBlocking`). A suspending read block can
 * switch threads, so it also marks its coroutine context.
 */
internal object ReadBlockGuard {

    private val depth = ThreadLocal.withInitial { 0 }

    class Marker : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Marker>
    }

    fun <T> withThreadMarker(block: () -> T): T {
        depth.set(depth.get() + 1)
        try {
            return block()
        } finally {
            val updated = depth.get() - 1
            if (updated == 0) depth.remove() else depth.set(updated)
        }
    }

    suspend fun isInsideReadBlock(): Boolean = depth.get() > 0 || coroutineContext[Marker] != null

    suspend fun checkNotInsideReadBlock(operation: String) {
        check(!isInsideReadBlock()) {
            "$operation must not be called inside a read block. The read lock is held for the whole block and " +
                    "serializes commands too, so reading large data under it would block the application. Read the " +
                    "id inside the read block and call $operation after it."
        }
    }
}

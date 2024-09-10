package dev.klerkframework.klerk.misc

import kotlinx.coroutines.sync.Mutex

/**
 * Guarantees that no reads are done when writing and that not more than one writer is mutating data at once. Also
 * establishes a happens-before relation (see https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5)
 */
internal class ReadWriteLock {

    // Unfortunately, there is no ReadWriteLock or similar for coroutines yet
    // (see https://github.com/Kotlin/kotlinx.coroutines/issues/94
    // and https://github.com/Kotlin/kotlinx.coroutines/pull/2045)
    // Until it arrives (or we write our own) we use a Mutex, which provides a happens-before relationship
    // (see https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/)

    private val mutex = Mutex()

    suspend fun acquireRead() {
        mutex.lock()
    }

    fun releaseRead() {
        mutex.unlock()
    }

    suspend fun acquireWrite() {
        mutex.lock()
    }

    fun releaseWrite() {
        mutex.unlock()
    }

}

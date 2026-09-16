package dev.klerkframework.klerk.misc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A readers-writer lock for coroutines: many readers may run concurrently, or one writer exclusively, never both.
 * Also establishes a happens-before relation (see
 * https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5).
 *
 * Writers are preferred: once a writer is waiting, no further readers are admitted. Klerk's commands are meant to be
 * prompt, so an unbroken stream of reads must not be able to starve a pending commit.
 *
 * A consequence of writer preference is that the lock is **not reentrant in any direction**. Taking a read lock while
 * already holding one deadlocks if a writer queued up in between, so nesting [withRead] or [withWrite] — directly or
 * through a call chain — is a bug. See `ReadBlockGuard`, which catches the common shapes of this with a useful message.
 */
internal class ReadWriteLock {

    // Kotlin coroutines still have no readers-writer lock in the standard library
    // (see https://github.com/Kotlin/kotlinx.coroutines/issues/94
    // and https://github.com/Kotlin/kotlinx.coroutines/pull/2045), hence this one.

    /**
     * Guards the fields below. A plain lock rather than a coroutine Mutex, so that releasing does not have to suspend:
     * releases happen in `finally` blocks, where suspending is where cancellation handling goes wrong. It is only ever
     * held for a few field updates and never across a suspension point.
     */
    private val state = ReentrantLock()
    private var activeReaders = 0
    private var writerActive = false
    private val waitingReaders = ArrayDeque<CompletableDeferred<Unit>>()
    private val waitingWriters = ArrayDeque<CompletableDeferred<Unit>>()

    /**
     * Runs [block] holding the read lock, on a dispatcher meant for blocking work. Reading a model may have to fetch it
     * from persistence, and that fetch is synchronous by design (see `Reader`), so read blocks must not occupy a
     * dispatcher sized for CPU-bound work.
     */
    suspend fun <T> withRead(block: suspend () -> T): T {
        acquireRead()
        try {
            return withContext(Dispatchers.IO) { block() }
        } finally {
            releaseRead()
        }
    }

    /**
     * Runs [block] holding the write lock, excluding all readers.
     *
     * Unlike [withRead] this does not move to another dispatcher: a commit has its model bodies in hand already and
     * never fetches from persistence while holding the lock.
     */
    suspend fun <T> withWrite(block: suspend () -> T): T {
        acquireWrite()
        try {
            return block()
        } finally {
            releaseWrite()
        }
    }

    private suspend fun acquireRead() {
        val waiter = state.withLock {
            if (!writerActive && waitingWriters.isEmpty()) {
                activeReaders++
                return
            }
            CompletableDeferred<Unit>().also { waitingReaders.add(it) }
        }
        await(waiter, waitingReaders, ::releaseRead)
    }

    private suspend fun acquireWrite() {
        val waiter = state.withLock {
            if (!writerActive && activeReaders == 0) {
                writerActive = true
                return
            }
            CompletableDeferred<Unit>().also { waitingWriters.add(it) }
        }
        await(waiter, waitingWriters, ::releaseWrite)
    }

    private fun releaseRead() {
        val granted = state.withLock {
            check(activeReaders > 0) { "releaseRead without a matching acquireRead" }
            activeReaders--
            grantLocked()
        }
        for (waiter in granted) {
            waiter.complete(Unit)
        }
    }

    private fun releaseWrite() {
        val granted = state.withLock {
            check(writerActive) { "releaseWrite without a matching acquireWrite" }
            writerActive = false
            grantLocked()
        }
        for (waiter in granted) {
            waiter.complete(Unit)
        }
    }

    /**
     * Waits until the lock is handed over. If the coroutine is cancelled after the hand-over but before it resumes, it
     * owns the lock without knowing it, so [release] undoes that rather than leaking a permit forever.
     */
    private suspend fun await(
        waiter: CompletableDeferred<Unit>,
        queue: ArrayDeque<CompletableDeferred<Unit>>,
        release: () -> Unit,
    ) {
        try {
            waiter.await()
        } catch (e: CancellationException) {
            // Still queued means it was never handed over; already dequeued means it was.
            val wasHandedOver = state.withLock { !queue.remove(waiter) }
            if (wasHandedOver) {
                release()
            }
            throw e
        }
    }

    /**
     * Picks who runs next. Must be called holding [state]; the returned waiters are completed by the caller *after*
     * releasing it, so that a continuation resuming inline cannot re-enter the lock.
     */
    private fun grantLocked(): List<CompletableDeferred<Unit>> {
        if (writerActive || activeReaders > 0) {
            return emptyList()
        }
        waitingWriters.removeFirstOrNull()?.let { writer ->
            writerActive = true
            return listOf(writer)
        }
        if (waitingReaders.isEmpty()) {
            return emptyList()
        }
        val readers = waitingReaders.toList()
        waitingReaders.clear()
        activeReaders += readers.size
        return readers
    }

}

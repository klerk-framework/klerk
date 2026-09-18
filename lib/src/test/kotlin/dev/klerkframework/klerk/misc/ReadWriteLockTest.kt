package dev.klerkframework.klerk.misc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadWriteLockTest {

    /**
     * Tracks the invariant the lock exists to uphold: any number of readers, or one writer, never both. Every
     * violation seen is counted rather than thrown, so a failing run reports how often it happened.
     */
    private class Invariant {
        private val readers = AtomicInteger(0)
        private val writers = AtomicInteger(0)
        val violations = AtomicInteger(0)
        val maxConcurrentReaders = AtomicInteger(0)

        fun enterRead() {
            val n = readers.incrementAndGet()
            maxConcurrentReaders.accumulateAndGet(n, ::maxOf)
            if (writers.get() != 0) violations.incrementAndGet()
        }

        fun exitRead() = readers.decrementAndGet()

        fun enterWrite() {
            if (writers.incrementAndGet() != 1) violations.incrementAndGet()
            if (readers.get() != 0) violations.incrementAndGet()
        }

        fun exitWrite() = writers.decrementAndGet()
    }

    @Test
    fun `readers run concurrently`() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteLock()
        val readerCount = 20
        val allInside = CompletableDeferred<Unit>()
        val arrived = AtomicInteger(0)

        // Each reader parks until every reader has entered. That can only complete if they are genuinely inside the
        // lock at the same time, so it deadlocks (and the test times out) if reads are serialized.
        withTimeout(10_000) {
            (1..readerCount).map {
                launch {
                    lock.withRead {
                        if (arrived.incrementAndGet() == readerCount) allInside.complete(Unit)
                        allInside.await()
                    }
                }
            }.joinAll()
        }
        assertEquals(readerCount, arrived.get())
    }

    @Test
    fun `writer excludes readers under load`() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteLock()
        val invariant = Invariant()

        withTimeout(30_000) {
            val readers = (1..16).map {
                launch {
                    repeat(300) {
                        lock.withRead {
                            invariant.enterRead()
                            yield()
                            invariant.exitRead()
                        }
                    }
                }
            }
            val writers = (1..4).map {
                launch {
                    repeat(100) {
                        lock.withWrite {
                            invariant.enterWrite()
                            yield()
                            invariant.exitWrite()
                        }
                    }
                }
            }
            (readers + writers).joinAll()
        }

        assertEquals(0, invariant.violations.get(), "readers and writers overlapped")
        assertTrue(invariant.maxConcurrentReaders.get() > 1, "reads never actually ran concurrently")
    }

    @Test
    fun `a writer is not starved by continuous reads`() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteLock()
        val stop = AtomicInteger(0)

        // Readers that immediately re-acquire. Without writer preference the writer would never get in.
        val readers = (1..8).map {
            launch {
                while (stop.get() == 0) {
                    lock.withRead { yield() }
                }
            }
        }
        try {
            repeat(20) {
                withTimeout(5_000) {
                    lock.withWrite { }
                }
            }
        } finally {
            stop.set(1)
            readers.joinAll()
        }
    }

    @Test
    fun `an exception in the block releases the lock`() = runBlocking {
        val lock = ReadWriteLock()
        assertFailsWith<IllegalStateException> { lock.withWrite { error("boom") } }
        assertFailsWith<IllegalStateException> { lock.withRead { error("boom") } }
        // Would hang if either lock leaked.
        withTimeout(5_000) {
            lock.withWrite { }
            lock.withRead { }
        }
    }

    @Test
    fun `cancelling a queued reader does not leak a permit`() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteLock()
        val writerInside = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()

        val writer = launch {
            lock.withWrite {
                writerInside.complete(Unit)
                releaseWriter.await()
            }
        }
        writerInside.await()

        // These all queue behind the writer, then are cancelled before it releases.
        val reached = AtomicInteger(0)
        val queued = (1..10).map {
            launch {
                reached.incrementAndGet()
                lock.withRead { }
            }
        }
        while (reached.get() < queued.size) yield()
        queued.forEach { it.cancel() }
        queued.joinAll()

        releaseWriter.complete(Unit)
        writer.join()

        withTimeout(5_000) {
            lock.withWrite { }
            lock.withRead { }
        }
    }

    /**
     * The nastiest case: a coroutine cancelled after the lock was handed to it but before it resumes owns the lock
     * without knowing it. Rather than trying to hit that window deterministically, this cancels readers at randomly
     * varying moments many times over and then checks the lock is still usable.
     */
    @Test
    fun `cancelling around the hand-over does not leak a permit`() = runBlocking(Dispatchers.Default) {
        repeat(200) { round ->
            val lock = ReadWriteLock()
            val writerInside = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()

            val writer = launch {
                lock.withWrite {
                    writerInside.complete(Unit)
                    releaseWriter.await()
                }
            }
            writerInside.await()
            val queued = (1..4).map { launch { lock.withRead { } } }

            // Release the writer and cancel the readers at about the same time, so some cancellations land before the
            // hand-over and some after.
            val canceller = launch {
                repeat(round % 5) { yield() }
                queued.forEach { it.cancel() }
            }
            releaseWriter.complete(Unit)

            canceller.join()
            queued.joinAll()
            writer.join()

            withTimeout(5_000) {
                lock.withWrite { }
                lock.withRead { }
            }
        }
    }
}

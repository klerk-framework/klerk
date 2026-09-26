package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.storage.CommitBatch
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CommandCancellationTest {

    private class SignallingStorage : RamStorage() {
        val stored = CompletableDeferred<CommitBatch>()

        override fun store(batch: CommitBatch) {
            super.store(batch)
            if (batch.createdModels.isNotEmpty()) stored.complete(batch)
        }
    }

    @Test
    fun `a command cancelled after it was persisted still reaches memory`() = runBlocking<Unit> {
        val storage = SignallingStorage()
        val bc = BookViews()
        val klerk = Klerk.create(createConfig(Views(bc, AuthorViews(bc.all))), testSettings(storage))
        klerk.meta.start(installShutdownHook = false)

        // A read block keeps the commit waiting for the write lock after it has been persisted.
        val releaseRead = CompletableDeferred<Unit>()
        val readerStarted = CompletableDeferred<Unit>()
        val reader = launch(Dispatchers.Default) {
            klerk.readSuspend(Ctx.system()) {
                readerStarted.complete(Unit)
                releaseRead.await()
            }
        }
        readerStarted.await()

        val handling = async(Dispatchers.Default) {
            klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system())
        }
        val batch = storage.stored.await()
        delay(200)
        handling.cancel()
        releaseRead.complete(Unit)
        reader.join()
        val result = runCatching { handling.await() }

        assertIs<CancellationException>(result.exceptionOrNull())
        val id = batch.createdModels.single().id
        assertNotNull(klerk.read(Ctx.system()) { getOrNull(id) })
        klerk.meta.stop()
    }
}

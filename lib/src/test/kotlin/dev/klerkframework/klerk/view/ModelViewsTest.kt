package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCollectionsTest {

    @Test
    fun `Delete should update collections`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)
            klerk.read(Ctx.system()) {
                assertTrue { collections.authors.all.contains(astrid) }
            }

            klerk.handle(
                Command(DeleteAuthor, astrid),
                Ctx.system(),
            )
            klerk.read(Ctx.system()) {
                assertFalse { collections.authors.all.contains(astrid) }
            }
        }

    }
}

package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DeleteAuthor
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createKlerk
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

package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCollectionsTest {

    @Test
    fun `Delete should update collections`() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)
            klerk.read(Context.system()) {
                assertTrue { collections.authors.all.contains(astrid, this) }
            }

            klerk.handle(Command(DeleteAuthor, astrid, null), Context.system(), ProcessingOptions(CommandToken.simple()))
            klerk.read(Context.system()) {
                assertFalse { collections.authors.all.contains(astrid, this) }
            }
        }

    }
}

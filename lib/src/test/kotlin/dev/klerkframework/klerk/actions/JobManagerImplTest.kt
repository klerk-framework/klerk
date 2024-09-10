package dev.klerkframework.klerk.actions

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class JobManagerImplTest {

    @Test
    fun job() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()
            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)
            val result = klerk.handle(
                Command(ChangeName, rowling, ChangeNameParams(FirstName("a"), LastName("b"))),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )
            assertEquals(1, result.orThrow().jobs.size)
        }
    }
}

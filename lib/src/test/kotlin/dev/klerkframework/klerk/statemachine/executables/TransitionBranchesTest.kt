package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class transitionWhenTest {

    @Test
    fun branches() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()), KlerkSettings())
            klerk.meta.start()

            val rowlingId = createAuthorJKRowling(klerk)
        }
    }
}

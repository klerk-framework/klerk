package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class transitionWhenTest {

    @Test
    fun branches() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val rowlingId = createAuthorJKRowling(klerk)
        }
    }
}

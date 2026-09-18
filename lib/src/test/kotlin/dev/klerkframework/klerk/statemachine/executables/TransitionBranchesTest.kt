package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorJKRowling
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class TransitionWhenTest {

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

package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorAstrid
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ReaderTest {

    @Test
    fun `Cannot bypass auth rules by reading a model as another type`() {
        runBlocking {
            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections, RamStorage())
            klerk.meta.start()

            val astrid = createAuthorAstrid(klerk)
            klerk.read(Ctx.unauthenticated()) {
                try {
                    get(astrid)
                    fail()
                } catch (e: Exception) {
                    assertTrue { e is AuthorizationException }
                }

                try {
                    val badId = ModelID<Book>(astrid.value)
                    get(badId)
                    fail()
                } catch (e: Exception) {
                    assertTrue { e is AuthorizationException }
                }
            }
        }
    }
}

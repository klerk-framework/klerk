package dev.klerkframework.klerk

import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.klerkframework.klerk.view.*


class MutationTest {

    @Test
    fun event() {

        runBlocking {

            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val specification = SpecificationBuilder<Ctx, Views>(collections).build {
                managedModels {
                    model(Author::class, authorStateMachine(collections), collections.authors)
                    model(Book::class, bookStateMachine(collections), collections.books)
                }
                authorization {
                    readModels {
                        positive(::`Everybody can read`)
                    }
                    readProperties {
                        positive(::canReadAllProperties)
                    }
                    commands {
                        positive(::`Everybody can do everything`)
                    }
                }
                systemContextProvider { Ctx(SystemIdentity) }
            }
            val klerk = Klerk.create(specification, testSettings())
            klerk.meta.start()
            val jk = createAuthorJKRowling(klerk)
            val astrid = createAuthorAstrid(klerk)
            val harryPotter1 = createBookHarryPotter2(
                klerk,
                author = jk,
                coAuthors = setOf(astrid),
                previousBooksInSameSeries = listOf(),
            )
            klerk.read(Ctx.unauthenticated()) {
                val name = get(astrid).props.firstName
                println(name.value)
                val hp = get(harryPotter1).props
                assertEquals(1, hp.coAuthors.size)
                assertEquals("Astrid", get(hp.coAuthors.first()).props.firstName.value)
            }
        }
    }
}

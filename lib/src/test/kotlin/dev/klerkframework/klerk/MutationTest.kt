package dev.klerkframework.klerk

import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals


class MutationTest {

    @Test
    fun event() {

        runBlocking {

            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val config = ConfigBuilder<Context, MyCollections>(collections).build {
                managedModels {
                    model(Author::class, authorStateMachine(collections), collections.authors)
                    model(Book::class, bookStateMachine(collections.authors.all, collections), collections.books)
                }
                authorization {
                    readModels {
                        positive {
                            rule(::`Everybody can read`)
                        }
                        negative {
                        }
                    }
                    readProperties {
                        positive {
                            rule(::canReadAllProperties)
                        }
                        negative {
                        }
                    }
                    commands {
                        positive {
                            rule(::`Everybody can do everything`)
                        }
                        negative {
                        }
                    }
                }
                persistence(RamStorage())
            }
            val klerk = Klerk.create(config)
            klerk.meta.start()
            val jk = createAuthorJKRowling(klerk)
            val astrid = createAuthorAstrid(klerk)
            val harryPotter1 = createBookHarryPotter2(
                klerk,
                author = jk,
                coAuthors = setOf(astrid),
                previousBooksInSameSeries = listOf()
            )
            klerk.read(Context.unauthenticated()) {
                val name = get(astrid).props.firstName
                println(name.value)
                val hp = get(harryPotter1).props
                assertEquals(1, hp.coAuthors.size)
                assertEquals("Astrid", get(hp.coAuthors.first()).props.firstName.value)
            }
        }
    }
}

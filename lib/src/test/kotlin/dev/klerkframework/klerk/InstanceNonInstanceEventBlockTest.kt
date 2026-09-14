package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import dev.klerkframework.klerk.collection.*

class InstanceNonInstanceEventBlockTest {

    @Test
    fun unmanagedJobOrder() {

        runBlocking {

            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val persistence = RamStorage()
            val specification = SpecificationBuilder<Ctx, Views>(collections).build {
                managedModels {
                    model(Book::class, createStateMachine(collections.authors), collections.books)
                    model(Author::class, authorStateMachine(collections), collections.authors)
                }
                authorization {
                    readModels {
                        positive {
                            rule(::`Everybody can read`)
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
                systemContextProvider { Ctx(SystemIdentity) }
            }
            val klerk = Klerk.create(specification, testSettings(persistence))
            klerk.meta.start()

            klerk.read(Ctx.system()) {
                println("is empty: ${collections.authors.all.isEmpty(this)}")
            }

            val author = createAuthorJKRowling(klerk)
            println(author)

            klerk.read(Ctx.system()) {

                println("is empty: ${collections.authors.all.withReader(this).count()}")

            }

            val harryPotter1 = createBookHarryPotter1(klerk, author)

            val result2 = klerk.handle(
                Command(
                    PublishBook,
                    harryPotter1
                ),
                Ctx.system(),
            )

            assert(result2 is CommandResult.Success)

        }
    }

    fun createStateMachine(authors: AuthorViews<Views>): StateMachine<Book, BookStates, Ctx, Views> =

        stateMachine {
            event(CreateBook) {
                validReferences(CreateBookParams::author, authors.all)
                validReferences(CreateBookParams::coAuthors, authors.all)
                validReferences(CreateBookParams::previousBooksInSameSeries, null)
            }

            event(PublishBook) {}

            voidState {
                onEvent(CreateBook) {
                    createModel(BookStates.Draft, ::newBook)
                }
            }

            state(BookStates.Draft) {
                onEvent(PublishBook) {
                    update(::updateModelFunction)
                }
            }

            state(BookStates.Published) {}

        }

}

fun updateModelFunction(args: ArgForInstanceEvent<Book, Nothing?, Ctx, Views>): Book {
    return args.model.props.copy(title = BookTitle("something else"))
}

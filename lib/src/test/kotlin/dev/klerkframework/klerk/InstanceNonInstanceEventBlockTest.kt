package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class InstanceNonInstanceEventBlockTest {

    @Test
    fun unmanagedJobOrder() {

        runBlocking {

            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val persistence = RamStorage()
            val config = ConfigBuilder<Context, MyCollections>(collections).build {
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
                persistence(persistence)
            }
            val klerk = Klerk.create(config)
            klerk.meta.start()

            klerk.read(Context.system()) {
                println("is empty: ${collections.authors.all.isEmpty(this)}")
            }

            val author = createAuthorJKRowling(klerk)
            println(author)

            klerk.read(Context.system()) {

                println("is empty: ${collections.authors.all.withReader(this).count()}")

            }

            val harryPotter1 = createBookHarryPotter1(klerk, author)

            val result2 = klerk.handle(
                Command(
                    event = PublishBook,
                    model = harryPotter1,
                    params = null
                ),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )

            assert(result2 is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success)

        }
    }

    fun createStateMachine(authors: AuthorCollections<MyCollections>): StateMachine<Book, BookStates, Context, MyCollections> =

        stateMachine {
            event(CreateBook) {
                validReferences(CreateBookParams::author, authors.all)
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

        }

}

fun updateModelFunction(args: ArgForInstanceEvent<Book, Nothing?, Context, MyCollections>): Book {
    return args.model.props.copy(title = BookTitle("something else"))
}


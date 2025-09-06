package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class ActionTest {


    @Test
    fun actionTest() {
        runBlocking {
            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))

            val config = ConfigBuilder<Context, MyCollections>(collections).build {
                managedModels {
                    model(Book::class, bookStateMachine(collections.authors.all, collections), collections.books)
                }
                apply(generousAuthRules())
                persistence(RamStorage())
            }
            val klerk = Klerk.create(config)
            klerk.meta.start()
        }
    }

    @Test
    fun throwingAction() {
        runBlocking {
            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val config = ConfigBuilder<Context, MyCollections>(collections).build {
                managedModels {
                    model(Book::class, throwingStateMachine(collections), collections.books)
                    model(Author::class, authorStateMachine(collections), collections.authors)
                }
                apply(generousAuthRules())
                persistence(RamStorage())
            }
            val klerk = Klerk.create(config)
            klerk.meta.start()
            val author = createAuthorJKRowling(klerk)
            klerk.read(Context.system()) {
                val all = collections.authors.all.withReader(this, null).toList()
                println(all.size)
            }
            val result = klerk.handle(
                Command(
                    event = CreateBook,
                    model = null,
                    params = CreateBookParams(
                        title = BookTitle("Harry Potter and the Philosopher's Stone"),
                        author = author,
                        coAuthors = emptySet(),
                        previousBooksInSameSeries = emptyList(),
                        tags = setOf(BookTag("Fiction"), BookTag("Children")),
                        averageScore = AverageScore(0f)
                    ),
                ),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            ).orThrow()
            println(result)

        }
    }

    private fun throwingStateMachine(collections: MyCollections): StateMachine<Book, BookStates, Context, MyCollections> =
        stateMachine {
            event(CreateBook) {
                validReferences(CreateBookParams::author, collections.authors.all)
            }

            event(PublishBook) {}

            voidState {
                onEvent(CreateBook) {
                    unmanagedJob(::throwSomething)
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

fun throwSomething(args: ArgForVoidEvent<Book, CreateBookParams, Context, MyCollections>) {
    throw IllegalStateException("This didn't work")
}

fun generousAuthRules(): ConfigBuilder<Context, MyCollections>.() -> Unit = {
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
}

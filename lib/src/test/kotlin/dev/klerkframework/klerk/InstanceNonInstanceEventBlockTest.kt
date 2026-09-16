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
import dev.klerkframework.klerk.view.*

class InstanceNonInstanceEventBlockTest {

    @Test
    fun unmanagedJobOrder() {

        runBlocking {

            val bc = BookViews()
            val collections = Views(bc, AuthorViews(bc.all))
            val persistence = RamStorage()
            val specification = SpecificationBuilder<Ctx, Views>(collections).build {
                managedModels {
                    model(Book::class, createStateMachine(collections), collections.books)
                    model(Author::class, authorStateMachine(collections), collections.authors)
                }
                authorization {
                    readModels {
                        positive(::`Everybody can read`)
                    }
                    commands {
                        positive(::`Everybody can do everything`)
                    }
                }
                systemContextProvider { Ctx(SystemIdentity) }
            }
            val klerk = Klerk.create(specification, testSettings(persistence))
            klerk.meta.start()

            klerk.read(Ctx.system()) {
                println("is empty: ${collections.authors.all.isEmpty()}")
            }

            val author = createAuthorJKRowling(klerk)
            println(author)

            klerk.read(Ctx.system()) {

                println("is empty: ${collections.authors.all.asSequenceOrThrow().count()}")

            }

            val harryPotter1 = createBookHarryPotter1(klerk, author)

            val result2 = klerk.handle(
                Command(
                    PublishBook,
                    harryPotter1,
                ),
                Ctx.system(),
            )

            assert(result2 is CommandResult.Success)

        }
    }

    fun createStateMachine(views: Views): StateMachine<Book, BookStates, Ctx, Views> =

        stateMachine {
            event(CreateBook) {
                validReferences(CreateBookParams::author, views.authors.all)
                validReferences(CreateBookParams::coAuthors, views.authors.all)
                validReferences(CreateBookParams::previousBooksInSameSeries, views.books.all)
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

fun updateModelFunction(args: InstanceEventArgs<Book, Nothing?, Ctx, Views>): Book {
    return args.model.props.copy(title = BookTitle("something else"))
}

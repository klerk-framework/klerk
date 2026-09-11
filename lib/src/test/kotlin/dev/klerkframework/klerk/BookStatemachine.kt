package dev.klerkframework.klerk

import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine

enum class BookStates {
    Draft,
    Published,
}

fun bookStateMachine(collections: Views): StateMachine<Book, BookStates, Ctx, Views> =
    stateMachine {

        event(CreateBook) {
            validReferences(CreateBookParams::author, collections.authors.all)
            validReferences(CreateBookParams::coAuthors, collections.authors.all)
            validReferences(CreateBookParams::previousBooksInSameSeries, collections.books.all)
            validEnums(CreateBookParams::genre, BookGenre.entries.toSet())
        }

        event(PublishBook) {}

        event(DeleteBook) {}

        event(UpdateBook) {
            validReferences(Book::author, collections.authors.all)
            validReferences(Book::coAuthors, collections.authors.all)
            validReferences(Book::previousBooksInSameSeries, collections.books.all)
        }

        voidState {
            onEvent(CreateBook) {
                createModel(BookStates.Draft, ::newBook)
            }
        }

        state(BookStates.Draft) {
            onEnter {
                //action(`Send email to editors`)
            }

            onEvent(PublishBook) {
                update(::setPublishTime)
                transitionTo(BookStates.Published)
            }

            onEvent(DeleteBook) {
                delete()
            }

            onEvent(UpdateBook) {
                update(::updateBook)
            }

        }

        state(BookStates.Published) {

            onEvent(DeleteBook) {
                delete()
            }
        }

    }

fun setPublishTime(args: ArgForInstanceEvent<Book, Nothing?, Ctx, Views>): Book {
    return args.model.props.copy(publishedAt = BookWrittenAt(args.context.time))
}

package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine

enum class BookStates {
    Draft,
    Published,
}

fun bookStateMachine(allAuthors: ModelView<Author, Context>, collections: MyCollections): StateMachine<Book, BookStates, Context, MyCollections> =
    stateMachine {

        event(CreateBook) {
            validReferences(CreateBookParams::author, collections.authors.all)
        }

        event(PublishBook) {}

        event(DeleteBook) {}



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

        }

        state(BookStates.Published) {

            onEvent(DeleteBook) {
                delete()
            }
        }

    }

fun setPublishTime(args: ArgForInstanceEvent<Book, Nothing?, Context, MyCollections>): Book {
    return args.model.props.copy(publishedAt = BookWrittenAt(args.context.time))
}

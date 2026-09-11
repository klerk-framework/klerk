# State machines

Every managed model has exactly one state machine. It declares every state the model can be in, every event that can
happen to it, and what happens when a state is entered, exited, or a certain time passes. This is where the business
logic of your application lives — Klerk will refuse to change a model in any way that isn't described here.

A state machine is built with the `stateMachine { }` DSL and wired to its model type in the specification:

```kotlin
fun bookStateMachine(collections: Views): StateMachine<Book, BookStates, Ctx, Views> =
    stateMachine {
        // ...
    }

SpecificationBuilder<Ctx, Views>(views).build {
    managedModels {
        model(Book::class, bookStateMachine(collections), collections.books)
        model(Author::class, authorStateMachine(collections), collections.authors)
    }
    // ...
}
```

`ModelStates` is your own enum, one value per instance state (e.g. `enum class BookStates { Draft, Published }`).
There's always one extra, implicit state that has no enum value: the **void state** — where a model is before it exists.

## Declaring events

Every event a model can react to must be declared with `event(...)` before it is used anywhere in `onEvent`:

```kotlin
event(CreateBook) {
    validReferences(CreateBookParams::author, collections.authors.all)
    validEnums(CreateBookParams::genre, BookGenre.entries.toSet())
}

event(PublishBook) {}

event(DeleteBook) {}
```

This block is where you attach the event's validation rules (`validate`, `validateWithParameters`,
`validateWithContext`, `validReferences`, `validEnums`) — see [validation.md](validation.md). Declaring the event
separately from where it's used means all its rules are visible in one place instead of scattered across every state
that reacts to it. It's also a safety net: Klerk throws `IllegalConfigurationException` at startup if an event is
referenced in `onEvent` without having been declared, or if a parameter of type `ModelID` has no `validReferences`. The
event objects themselves (`CreateBook`, `PublishBook`, ...) and their parameter classes are defined separately —
see [events-and-commands.md](events-and-commands.md).

## The void state

`voidState { }` is where a model doesn't exist yet. The only thing that can happen here is creation — turning a
"no model" into a model, which is done with `createModel`:

```kotlin
voidState {
    onEvent(CreateBook) {
        createModel(BookStates.Draft, ::newBook)
    }
}

fun newBook(args: ArgForVoidEvent<Book, CreateBookParams, Ctx, Views>): Book {
    val params = args.command.params
    return Book(title = params.title, author = params.author, /* ... */)
}
```

`createModel(initialState, function)` calls `function` to build the model's properties and puts the new instance
directly into `initialState`. There is no other way to create a model — you cannot construct a `Model<T>` yourself
outside of a state machine (aside from the `unsafeCreate` escape hatch on `KlerkModels`, which bypasses the state
machine entirely and should be avoided).

## Instance states

`state(SomeEnumValue) { }` declares what can happen while a model is in that state:

```kotlin
state(BookStates.Draft) {
    onEnter {
        // runs every time a Book enters Draft — from creation or from a transition
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
```

- `onEnter { }` runs whenever a model enters this state, whether that's because it was just created into it
  (`createModel`) or because something transitioned it here (`transitionTo`/`transitionWhen`).
- `onExit { }` runs whenever a model leaves this state, right before the transition takes effect.
- `onEvent(SomeEvent) { }` declares what happens when that event is received while the model is in this state. An event
  that isn't listed for the current state is rejected — you'll get a `StateProblem` telling you the event isn't possible
  in that state.

Only events declared with `event(...)` at the top of the state machine can be referenced in `onEvent`, and only
`InstanceEvent`s (not void events) can appear inside `state { }` — void events only make sense in `voidState { }`.

## Time triggers

A state can also react to the passage of time instead of an event — useful for timeouts, reminders, or scheduled
cleanup. Each state may have **at most one** time trigger, either `after` or `atTime`:

```kotlin
state(AuthorStates.Amateur) {
    after(30.seconds) {
        transitionTo(AuthorStates.Established)
        update(::someUpdate)
        unmanagedJob(::sayHello)
    }
}

state(AuthorStates.Established) {
    atTime(::later) {
        delete()
    }
}

fun later(args: ArgForInstanceNonEvent<Author, Ctx, Views>): Instant = args.time.plus(30.seconds)
```

- `after(duration) { }` fires `duration` after the model entered the current state.
- `atTime(function) { }` fires at the instant returned by `function`, which is recomputed based on the model's current
  data (so it can depend on model properties, not just a fixed offset).

There's no guarantee the block runs at the exact instant — expect a few seconds of variation — and if the block would
violate a rule (e.g. `delete()` is blocked because another model still refers to this one), it silently does not run,
and won't be retried. Avoid triggers that create loops (e.g. a state whose time trigger transitions back into a state
with the same trigger), as this can put continuous load on the system.

A time trigger fires on its own, in the background, with no command and no caller-supplied context — notice
`ArgForInstanceNonEvent` has no `context` field. To even have `after`/`atTime` (or jobs) in your specification, Klerk
needs a way to manufacture a `Ctx` for this situation, which is what `systemContextProvider` is for — see
[context.md](context.md#systemcontextprovider).

## Executables

Inside `onEvent`, `onEnter`, `onExit`, `after`, and `atTime` blocks you can call:

| Executable                                                                  | Effect                                                                                                                        |
|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `update(::fn)`                                                              | Replaces the model's properties with whatever `fn` returns. At most one per block.                                            |
| `delete(onCondition = ...)`                                                 | Deletes the model. At most one per block.                                                                                     |
| `transitionTo(State, onCondition = ...)`                                    | Moves the model to `State`. At most one per block.                                                                            |
| `transitionWhen(linkedMapOf(::decision to State, ...), otherwise = State?)` | Evaluates each decision function in order and transitions to the first match; `otherwise` if none match.                      |
| `createCommands(::fn)`                                                      | Returns a `List<Command<*, *>>` to submit as part of the same transaction — e.g. cascading an author deletion to their books. |
| `job(::fn)` / `jobs(::fn)` / `unmanagedJob(::fn, onCondition = ...)`        | Schedule background work — `job` for a single job, `jobs` for a list; see [jobs.md](jobs.md) for the distinction from `unmanagedJob`. |

All of these accept an optional `onCondition` predicate with the same argument type as the main function — if it returns
`false`, the executable is skipped. `transitionWhen` bakes this idea into its own branching form, evaluating functions
like `::hasTalent` or `::isAnImpostor` against the same arguments:

```kotlin
state(AuthorStates.Improving) {
    onEnter {
        transitionWhen(
            linkedMapOf(
                ::isAnImpostor to AuthorStates.Amateur,
                ::hasTalent to AuthorStates.Established,
            )
        )
    }
}
```

`createCommands` is how one event cascades into others. Here, deleting an author also deletes all their books, as part
of the same command:

```kotlin
onEvent(DeleteAuthorAndBooks) {
    createCommands(::eventsToDeleteAuthorAndBooks)
}

fun eventsToDeleteAuthorAndBooks(args: ArgForInstanceEvent<Author, Nothing?, Ctx, Views>): List<Command<Any, Any>> {
    args.reader.apply {
        val books = getRelated(Book::class, requireNotNull(args.model.id))
        return books.map { Command(event = DeleteBook, model = it.id, null) } +
                Command(event = DeleteAuthor, model = requireNotNull(args.model.id), null)
    }
}
```

A state machine will refuse to configure a transition that targets the state the model is already in — Klerk rejects
this at startup ("State X has a transition to itself"), so model a self-loop as two states or as an update without a
transition instead.

## The function arguments

Every function you pass to `event`, `createModel`, `update`, `transitionTo`'s `onCondition`, etc. receives one argument
describing everything available at that point:

- `ArgForVoidEvent<T, P, C, V>` — used in `voidState`. Has `command` (the incoming `Command<T, P>`), `context`
  ([context.md](context.md)), and `reader` ([reading.md](reading.md)). There is no `model` yet — it doesn't exist.
- `ArgForInstanceEvent<T, P, C, V>` — used in `onEvent` inside `state { }`. Adds `model: Model<T>`
  ([models.md](models.md)), the (uncommitted) model the event is acting on.
- `ArgForInstanceNonEvent<T, C, V>` — used in `onEnter`, `onExit`, `after`, `atTime`, i.e. anywhere there's no
  triggering event. Has `model`, `time`, and `reader`, but no `command`.

`P` is `Nothing?` for events without parameters. The reader you get here reads data as it was *before* the current
event/trigger started processing — the full mechanics of events, commands and parameters are covered in
[events-and-commands.md](events-and-commands.md).

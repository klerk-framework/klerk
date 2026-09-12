# Events and commands

Data is never mutated directly. To change something, you submit a `Command` describing an `Event` to
`klerk.handle(...)`. Klerk validates it, runs it through the [state machine](state-machines.md), and returns a result
describing what happened.

## Declaring events

An event is a Kotlin `object` extending one of four base classes, chosen along two axes:

|                                     | No parameters                  | With parameters                     |
|-------------------------------------|--------------------------------|-------------------------------------|
| **Void** (model doesn't exist yet)  | `VoidEventNoParameters<T>`     | `VoidEventWithParameters<T, P>`     |
| **Instance** (model already exists) | `InstanceEventNoParameters<T>` | `InstanceEventWithParameters<T, P>` |

```kotlin
object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(
    Book::class,
    EXTERNAL, CreateBookParams::class
)

object PublishBook : InstanceEventNoParameters<Book>(Book::class, EXTERNAL)

object ChangeName : InstanceEventWithParameters<Author, ChangeNameParams>(
    Author::class,
    EXTERNAL, ChangeNameParams::class
)
```

A "Void" event is one that isn't tied to an existing model instance — the archetypal example is creating one.
"Instance" events act on a specific, already-existing model and require its `ModelID` in the command.

Every event must be declared inside the model's [state machine](state-machines.md) with `event(...) { }` before it can
be referenced in `onEvent(...)`; this is where you attach validation rules (see [validation.md](validation.md)).

### EventVisibility

The second constructor argument controls where the event may be triggered from:

| Level                   | Can be triggered from                                                                                                               |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `STATEMACHINE_INTERNAL` | Only from within the same state machine (e.g. `createCommands`, a secondary event fired by another event's handler).                |
| `INTER_STATEMACHINE`    | From any state machine.                                                                                                             |
| `SYSTEM`                | From any state machine, and from application code — intended for events triggered by the system itself, e.g. from a [job](jobs.md). |
| `CODE`                  | From any state machine, and from application code.                                                                                  |
| `EXTERNAL`              | Same as `CODE`, but signals to other tooling (generated UI/API) that this event is meant to be exposed to end users.                |

Each level implies everything below it. `klerk.handle(...)` rejects any command whose event has a visibility lower than
`CODE` (`KlerkErrorCode.EventVisibilityTooLow`) — `STATEMACHINE_INTERNAL` and `INTER_STATEMACHINE` events can only be
produced by the state machine itself (e.g. via `createCommands`), never submitted directly.

## Building a Command

```kotlin
public data class Command<T : Any, P>(
    val event: Event<T, P>,
    val model: ModelID<T>?,
    val params: P,
)
```

* `model` is `null` for Void events, and the target model's id for Instance events.
* `params` is `null`/`Nothing?` for the `NoParameters` variants.

```kotlin
val command = Command(
    event = CreateBook,
    model = null,
    params = CreateBookParams(
        title = BookTitle("Harry Potter and the Philosopher's Stone"),
        author = author,
        averageScore = AverageScore(0f),
        readingTime = ReadingTime(2.hours),
    ),
)
```

## Submitting a command: `klerk.handle`

```kotlin
val result: CommandResult<Book, Ctx, Views> = klerk.handle(
    command,
    Ctx.system(),
)
```

The third parameter, `ProcessingOptions`, controls how the command is processed. It defaults to
`ProcessingOptions(CommandToken.simple())` — a command guarded only against being submitted twice.

```kotlin
public data class ProcessingOptions(
    val token: CommandToken,
    val dryRun: Boolean = false,
    val debugOptions: Map<DebugOptions, Level> = defaultDebugOptions
)
```

* **`dryRun`** — runs every validation and authorization check and computes what *would* happen, but doesn't persist
  anything or trigger jobs/effects. Useful for e.g. a "preview" UI action.
* **`debugOptions`** — per-category log levels (`sequence`, `misc`, `result`) if you need to trace processing.

### CommandToken

Every command carries a `CommandToken`, which guarantees the command is only ever applied once and, optionally, that the
target model (s) haven't changed since the token was created:

```kotlin
CommandToken.simple()                                     // no idempotency guarantee beyond single-use
CommandToken.requireUnmodifiedModel(bookId)                // fails if `bookId` changed after the token was created
CommandToken.requireUnmodifiedModels(setOf(bookId, authorId))
```

A token can only be used once — reusing one fails with `IdempotenceProblem`. If a `requireUnmodified...` token's model
(s) were changed by another command in the meantime, handling fails with a `StateProblem`
(`ModelModifiedSinceTokenCreation`). This is the mechanism for optimistic-concurrency-style "the record you're editing
has since changed" checks. `CommandToken` also round-trips through a compact string via `.toString()` /
`CommandToken.from(string)`, so a client can hold on to one across a request/response cycle.

## Handling the result

```kotlin
public sealed class CommandResult<T : Any, C : KlerkContext, V> {
    public data class Success<T : Any, C : KlerkContext, V>(
        val primaryModel: ModelID<T>?,
        val createdModels: Set<ModelID<out Any>>,
        val updatedModels: Set<ModelID<out Any>>,
        val deletedModels: Set<ModelID<out Any>>,
        val transitionedModels: Set<ModelID<out Any>>,
        val jobs: List<JobId>,
        val unmanagedJobs: List<String>,
        val authorizedModels: Map<ModelID<out Any>, Model<out Any>>,
        val log: List<String>,
    ) : CommandResult<T, C, V>()

    public data class Failure<T : Any, C : KlerkContext, V>(val problems: List<Problem>) : CommandResult<T, C, V>()
}
```

`primaryModel` is the model directly created/updated by your command (as opposed to models affected only as a side
effect, e.g. via `createCommands` in the state machine). `authorizedModels` contains the resulting models the *current
context* is allowed to read — anything it isn't authorized for is simply absent, so it is safe to hand this map to a
caller without leaking data.

In tests and scripts, `.getOrThrow()` is the common shortcut — it returns `Success` or throws the first `Problem` as an
exception:

```kotlin
val bookId = klerk.handle(command, Ctx.system())
    .getOrThrow()
    .primaryModel!!
```

`getOrElse { failure -> ... }` is the non-throwing equivalent when you want to recover instead.

### Problems

A `Failure` carries one or more `Problem`s. The concrete subclass tells you what went wrong and maps to a recommended
HTTP status if you're exposing this over an API:

| Problem                                                       | HTTP      | Meaning                                                                                          |
|---------------------------------------------------------------|-----------|--------------------------------------------------------------------------------------------------|
| `NotFoundProblem`                                             | 404       | The referenced model doesn't exist.                                                              |
| `InvalidPropertyProblem` / `InvalidPropertyCollectionProblem` | 400       | A `DataContainer` or `Validatable` rule rejected the input — see [validation.md](validation.md). |
| `StateProblem`                                                | 409       | The event isn't valid for the model's current state, or a `CommandToken` precondition failed.    |
| `AuthorizationProblem`                                        | 403       | An authorization rule rejected the command — see [authorization.md](authorization.md).           |
| `BadRequestProblem`                                           | 400       | Malformed request, e.g. event visibility too low.                                                |
| `IdempotenceProblem`                                          | 400       | The `CommandToken` was already used.                                                             |
| `InternalProblem` / `ServerStateProblem`                      | 500 / 503 | Framework-internal failure.                                                                      |

Every problem also carries a `code` (`KlerkErrorCode`), and `asException()` turns it into the matching exception. The
exceptions Klerk throws itself — `AuthorizationException`, `InternalException`, `IllegalConfigurationException` — share
the `KlerkException` base class, so `code` is available on all of them.

```kotlin
when (val result = klerk.handle(command, context)) {
    is CommandResult.Success -> println("Created ${result.primaryModel}")
    is CommandResult.Failure -> result.problems.forEach { println(it.endUserTranslatedMessage) }
}
```

## The event log

Every successfully processed command is recorded. Reading it takes two steps: `eventLog(...)` inside a read block gives
you a query, and `get()` on that query — after the block — reads the entries from storage.

```kotlin
val query = klerk.read(context) {
    eventLog(id = bookId)   // omit (or pass null) to get entries for all models
}
val entries = query.get()
```

The query is a snapshot of the read block that created it: it only ever returns entries whose command was already
visible there, so the log never shows an event that hasn't happened yet. Because the database is queried by `get()`,
outside the block, nothing is read while the read lock is held — calling `get()` inside a read block is an error.

`eventLog` also takes `after`/`before` to limit the [time](context.md) range, and `sequenceNumber` to fetch one specific
entry:

```kotlin
public data class EventLogEntry(
    val sequenceNumber: Long,   // identifies the entry and orders the log
    val time: Instant,          // the context.time of the command
    val eventReference: EventReference,
    val reference: Int,         // the model the command acted on
    val actorType: Byte,
    val actorReference: Int?,
    val actorExternalId: Long?,
    val params: String,
    val extra: String?,         // context.eventLogExtra
)
```

Entries come back ordered by `sequenceNumber`, oldest first. Use it, not `time`, to order or identify an entry —
`time` comes from the command's context and is neither unique nor necessarily increasing.

Reading is subject to its own authorization rules (`eventLog` in [authorization.md](authorization.md)) — `eventLog(...)`
throws `AuthorizationException` if the context isn't allowed to see the event log.

## Declaring the DSL before writing the functions

Every function referenced from the DSL — `event(...)`'s validation rules, `createModel`, `update`, `transitionTo`'s
`onCondition`, and so on (see [state-machines.md](state-machines.md#the-function-arguments) and
[validation.md](validation.md)) — is passed in by reference (`::functionName`). Nothing requires the function body to be
finished for the DSL itself to compile and for `Klerk.create(specification, settings)` to build successfully; a stub
that throws `TODO()` is enough. This makes it practical to design top-down: sketch the full shape of a state machine
(states, events, validation rules) first, get it reviewed, and only then fill in each function body — including letting
an IDE generate the stub's signature from the `::functionName` reference before you've written anything.

Function bodies passed to the DSL should be pure — the same input always produces the same output, with no side effects.
This isn't just a style preference: `ProcessingOptions.dryRun` computes a result by calling the exact same functions as
a real command, without persisting anything. If a function performing a side effect (e.g. calling an external API) is
used to compute `update`/`createModel`'s return value, a dry run triggers that side effect for real. `require()`/
`check()` assertions are fine to keep in these functions — in a correctly configured system they should never actually
throw, since anything they'd catch should already have been rejected by
[validation](validation.md) or [authorization](authorization.md) first; if one does throw, it's a bug in an earlier
layer rather than an expected outcome.

## Putting it together

```kotlin
suspend fun createBookHarryPotter1(klerk: Klerk<Ctx, Views>, author: ModelID<Author>): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Philosopher's Stone"),
                author = author,
                averageScore = AverageScore(0f),
                readingTime = ReadingTime(2.hours),
            ),
        ),
        Ctx.system(),
    )
    return requireNotNull(result.getOrThrow().primaryModel)
}
```

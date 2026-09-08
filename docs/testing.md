# Testing

Because a Klerk specification *is* your business logic, testing it usually means testing your `Specification`
end-to-end:
start a real `Klerk` instance backed by in-memory storage, submit commands, and assert on the resulting models and
`CommandResult`s. There's no need to mock Klerk itself.

## Setting up an instance

```kotlin
runBlocking {
    val bookViews = BookViews()
    val collections = Views(bookViews, AuthorViews(bookViews.all))
    val klerk = Klerk.create(createSpecification(collections), KlerkSettings(persistence = RamStorage()))
    klerk.meta.start()

    // ... submit commands, read data, assert
}
```

`RamStorage()` (see [persistence.md](persistence.md)) keeps everything in memory, so each test gets a clean, throwaway
instance — build your `Specification` the same way your application does (typically via a shared
`createSpecification` helper) and change only the settings, pointing `persistence` at `RamStorage` instead of a real
database.

If you specifically need to test persistence/reload behavior, create the instance against a real
`SqlPersistence`, run some commands, then build a **second** `Klerk` instance around the same underlying database and
verify the data is still there — this is how `LibraryTest.read()` in this repo verifies that a restart re-reads existing
models from storage rather than losing them.

## Issuing commands

Use `Ctx.system()` (or another actor via `ActorIdentity`, see [context.md](context.md)) and
`CommandToken.simple()` when the test doesn't care about idempotency:

```kotlin
val result = klerk.handle(
    Command(event = CreateAuthor, model = null, params = createAstridParameters),
    Ctx.system(),
    ProcessingOptions(CommandToken.simple()),
)
```

Two common ways to assert on the result:

```kotlin
// fail the test with the first Problem's message if the command was rejected
val authorId = result.orThrow().primaryModel!!

// or branch explicitly when you want to assert on the failure itself
when (result) {
    is CommandResult.Success -> assertEquals(authorId, result.primaryModel)
    is CommandResult.Failure -> fail(result.problems.first().toString())
}
```

See [events-and-commands.md](events-and-commands.md) for the full shape of `CommandResult` and `Problem`.

## Asserting on model state

Read back through the normal `klerk.read` API (see [reading.md](reading.md)):

```kotlin
val updated = klerk.read(Ctx.system()) { get(authorId) }
assertEquals("a", updated.props.firstName.value)
```

## Testing side effects (jobs, onEnter, transitions)

State-machine callbacks (`onEnter`, `job`, `unmanagedJob`, `after`, ...) are ordinary functions, so the direct way to
observe that one ran is to give it an observable side effect for the duration of the test — e.g. a mutable top-level
var/callback the test sets before acting and asserts on afterward:

```kotlin
var amateurTriggered = false
onEnterAmateurStateActionCallback = { amateurTriggered = true }

val result =
    klerk.handle(Command(ImproveAuthor, rowling, null), Ctx.system(), ProcessingOptions(CommandToken.simple()))

when (result) {
    is CommandResult.Failure -> fail(result.problems.first().toString())
    is CommandResult.Success -> {
        assertTrue(amateurTriggered)
        assertEquals(1, result.jobs.size) // jobs scheduled by this command, not yet executed
    }
}
```

Note that `result.jobs`/`result.unmanagedJobs` tell you what was *scheduled*, not that it has *finished running*.

For jobs, do not poll or sleep. Configure manual execution and drive the scheduler yourself, so the test is
deterministic under repeat runs:

```kotlin
KlerkSettings(jobs = JobSettings(execution = JobExecution.Manual))

// ... handle the command that schedules the job ...
klerk.jobs.runUntilIdle()
assertEquals(JobStatus.Succeeded, klerk.jobs.getJob(result.jobs.single(), Ctx.system()).status)
```

Anything time-dependent — `scheduleAt`, retry backoff, cron — is driven by the settings clock, so set a `MutableClock`
and advance it rather than waiting. See [jobs.md](jobs.md#testing) and [time.md](time.md).

## Testing specification mistakes

Misconfigurations (undeclared events used in `onEvent`, a `Ref` parameter missing `validReferences`, a state
transitioning to itself, model classes with `var` properties, ...) are caught by `SpecificationBuilder.build()` itself,
so they can be asserted on directly without starting Klerk:

```kotlin
assertFailsWith<IllegalConfigurationException> {
    SpecificationBuilder<Ctx, Views>(views).build {
        // ... the invalid specification
    }
}
```

## Translation

If your application supports multiple languages (see [translation](translation.md)), pass the `Translation` you want to
test through the `Ctx` (e.g. `Ctx.swedishUnauthenticated()` in this repo's test suite) and assert on
`Problem.endUserTranslatedMessage` rather than on the underlying rule.

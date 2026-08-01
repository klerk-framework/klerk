# Jobs

State machines (see [state-machines.md](state-machines.md)) often need to trigger side effects — sending an email,
calling an external API, notifying another system — when an event is handled or a state is entered. Klerk gives you two
ways to do this, with very different guarantees:

|                    | `unmanagedJob`                                                 | `job`                                                         |
|--------------------|----------------------------------------------------------------|---------------------------------------------------------------|
| Runs               | Synchronously, right after the triggering command is committed | Asynchronously, picked up by a background worker              |
| Persisted          | No                                                             | Yes — inserted into `Persistence` before it runs              |
| Survives a restart | No                                                             | Yes — reloaded from storage on `klerk.meta.start()`           |
| Retries on failure | No                                                             | Yes, up to `maxRetries` (default 3), with exponential backoff |

Rule of thumb: reach for `unmanagedJob` for cheap, best-effort actions where losing one on a crash is fine (a log line,
an in-memory notification). Reach for `job` for anything that must actually happen — sending an email, charging a card,
calling a webhook — since it is durable and retried.

## unmanagedJob

Declared in a state machine block as an executable, taking a plain function with no return value:

```kotlin
state(Amateur) {
    onEnter {
        unmanagedJob(::onEnterAmateurStateAction)
    }

    onEvent(ImproveAuthor) {
        unmanagedJob(::showNotification, onCondition = ShouldSendNotificationAlgorithm::execute)
        transitionTo(Improving)
    }
}

fun onEnterAmateurStateAction(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    // fire-and-forget
}

fun showNotification(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>) {
    println("It was decided that we should show a notification")
}
```

`onCondition` is an optional predicate with the same argument type as the action; when it returns `false` the action is
skipped entirely. This is how `ShouldSendNotificationAlgorithm::execute` above gates whether the notification actually
fires.

Any exception thrown inside the action is caught by Klerk — it will not fail the command that triggered it, but it also
means the failure is easy to miss if you don't log it yourself.

## job

Declared the same way, but the function returns a list of `RunnableJob` instances instead of performing the action
directly:

```kotlin
onEvent(ChangeName) {
    update(::changeNameOfAuthor)
    job(::notifyBookStores)
}

fun notifyBookStores(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyCollections>): List<RunnableJob<Context, MyCollections>> {
    return listOf(MyJob2())
}
```

Each returned job is persisted and queued before `klerk.handle(...)` returns; `CommandResult.Success.jobs` lists the
`RunnableJob` instances that were scheduled as a result of the command.

### Defining a RunnableJob

```kotlin
class MyJob2 : RunnableJob<Context, MyCollections>() {

    override val parameters: String
        get() = "Hej"

    override fun getRunFunction() = Companion::run

    companion object {
        suspend fun run(metadata: JobMetadata, klerk: Klerk<Context, MyCollections>): JobResult {
            println("Job started")
            // ... do the actual work, using klerk.handle/klerk.read as needed ...
            return JobResult.Success()
        }
    }
}
```

A few things the implementation relies on, so they aren't optional in practice:

- **`getRunFunction()` must return a reference to a function declared in the job's `companion object`.** The job manager
  persists `parameters` (a `String`) plus the class name and method name, and re-resolves the companion method
  reflectively — including after a process restart — so the function cannot be a lambda or an instance method.
- **`parameters` is the only state that survives a restart.** Since only a `String` is stored, put whatever the job
  needs to do its work into that string yourself (e.g. JSON-encode a small payload, or store a `ModelID`'s string form
  and look the model up again inside the run function).
- The run function receives the live `Klerk<C, V>` instance, so it can call `klerk.read`/`klerk.readSuspend`/
  `klerk.handle` to interact with the rest of the system.

### JobResult

The run function must return one of:

- `JobResult.Success(state, log)` — done; the job is marked `Success` and not retried.
- `JobResult.Fail(state, log)` — failed this attempt. If `failedAttempts` is still below `maxRetries`, the job moves to
  `JobStatus.Backoff` and is retried after an exponentially growing delay (base 3 seconds, growing with each failed
  attempt); otherwise it's marked `Failed` permanently.
- `JobResult.Yield(state, log)` — not finished, but not a failure either; the job is re-queued immediately for another
  attempt without counting against `maxRetries`. Useful for jobs that need to run in several steps.

An uncaught exception thrown by the run function is treated the same as returning `Fail`.

The `state` string on `JobResult` is stored back onto the job's `JobMetadata.state` field, so a job can use it to
remember progress between attempts (e.g. "step 2 of 3 completed").

### Other Job properties you can override

```kotlin
public interface Job {
    public val maxRetries: Int get() = 3
    public val delivery: JobDelivery get() = JobDelivery.AtLeastOnce   // or AtMostOnce
    public val requiredWorkerTags: Set<String> get() = emptySet()
    public val tags: Set<String> get() = emptySet()
    public val parameters: String
    public val scheduleAt: Instant? get() = null                       // null = run as soon as possible
}
```

`scheduleAt` lets a job be scheduled for the future instead of immediately — see `FutureJob` below. `delivery`,
`requiredWorkerTags` and `tags` are declared on the `Job` interface but aren't yet acted on by the current
`JobManager` implementation — there is a single in-process worker, so nothing currently reads or filters on them.

### Scheduling a job manually

Outside of a state machine, schedule a `RunnableJob` directly via `klerk.jobs`:

```kotlin
val id: JobId = klerk.jobs.schedule(FutureJob())
```

```kotlin
class FutureJob : RunnableJob<Context, MyCollections>() {
    override val parameters: String = ""
    override val scheduleAt: Instant = Clock.System.now().plus(10.seconds)

    companion object {
        suspend fun myJobFunction(metadata: JobMetadata, klerk: Klerk<Context, MyCollections>): JobResult {
            // ...
            return JobResult.Success()
        }
    }

    override fun getRunFunction() = Companion::myJobFunction
}
```

### Inspecting jobs

```kotlin
val meta: JobMetadata = klerk.jobs.getJob(id)
val all: List<JobMetadata> = klerk.jobs.getAllJobs()
```

`JobMetadata` includes `status` (`Scheduled`, `Running`, `Success`, `Failed`, `Backoff`), `failedAttempts`,
`lastAttemptStarted`/`lastAttemptFinished`, `nextAttempt`, and the accumulated `log` of messages returned by each
attempt.

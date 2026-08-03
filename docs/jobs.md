# Jobs

> **Status:** this describes the reworked job module. The current implementation in
> `dev.klerkframework.klerk.job` predates it. See `implement-jobs.md` for the migration plan.

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
    onEvent(ImproveAuthor) {
        unmanagedJob(::showNotification)
        transitionTo(Improving)
    }
}

fun showNotification(args: ArgForInstanceEvent<Author, Nothing?, Ctx, Views>) {
    // fire-and-forget
}
```

Any exception thrown inside the action is caught by Klerk — it will not fail the command that triggered it, but it also
means the failure is easy to miss if you don't log it yourself.

## job

A managed job is a **step machine**. Klerk invokes your `step` function; it does some work and returns a result. The
result may *yield*: "I am not done, here is my new cursor, and here is (at most) one command to apply on my behalf."
Klerk applies the command and stores the cursor **in a single transaction**, then re-queues the job.

Everything else in this document follows from three rules:

1. **A job never mutates Klerk directly.** It returns a command; Klerk applies it. This is what makes retries safe and
   what will let jobs run on remote worker nodes later.
2. **At most one command per step.** If your job needs to do three mutations, it takes three steps.
3. **A step's command and the job's new cursor commit together, or not at all.** A resumed job never re-emits a command
   that was already applied.

### Anatomy

```kotlin
@Serializable
data class ImportCursor(val remaining: List<FileName>, val target: ModelID<Library>)

object ImportBooks : JobType.Local<ImportCursor, Ctx, Views>() {

    override val name = JobName("import-books")
    override val agent = JobAgent.System
    override val priority = JobPriority.Bulk

    override suspend fun step(args: JobStepArgs<ImportCursor, Ctx, Views>): JobResult<ImportCursor> {
        val cursor = args.cursor
        val next = cursor.remaining.firstOrNull() ?: return JobResult.Success()

        val parsed = parseBookFile(next)          // ordinary work: IO, parsing, an HTTP call

        return JobResult.Yield(
            cursor = cursor.copy(remaining = cursor.remaining.drop(1)),
            command = Command(CreateBook, model = null, params = parsed),
            progress = JobProgress(
                completed = cursor.done.toLong(),
                total = cursor.total.toLong(),
                message = TranslatedText { it.importingFile(next) },
            ),
        )
    }
}
```

Register the type in `ConfigBuilder` so Klerk can resolve it by name after a restart:

```kotlin
jobs {
    register(ImportBooks)
    register(NotifyBookStores)
}
```

The persisted record holds `JobName` — a stable string you own — not a class or method name. Renaming the Kotlin object
is safe; changing `name` is not (see [Restarts and deploys](#restarts-and-deploys)).

### Scheduling

From a state machine:

```kotlin
onEvent(ChangeName) {
    update(::changeNameOfAuthor)
    job(::notifyBookStores)
}

fun notifyBookStores(args: ArgForInstanceEvent<Author, ChangeNameParams, Ctx, Views>): List<ScheduledJob<Ctx, Views>> =
    listOf(NotifyBookStores.schedule(NotifyCursor(author = args.model.id)))
```

Or directly:

```kotlin
klerk.jobs.schedule(ImportBooks.schedule(ImportCursor(...), scheduleAt = tomorrow))
```

Jobs scheduled by a command are persisted in that command's transaction. If the command fails, no job is scheduled.
`CommandResult.Success.jobs` lists what was scheduled.

### What a step returns

| Result                                                      | Meaning                                                                                                                                                         |
|-------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Yield(cursor, command?, spawn?, awaitSpawned?, progress?)` | Not done. Command, spawned children and cursor commit atomically; job re-queued at the tail of its priority class, or moved to `Waiting` if it awaits children. |
| `Success(command?, progress?, result?)`                     | Done. Not retried. A command here commits with the terminal status. `result` is delivered to the parent, if any.                                                |
| `Fail(reason)`                                              | This attempt failed. Retried with exponential backoff until `maxRetries`, then dead-lettered.                                                                   |
| `Abort(reason, runHook = true)`                             | This will never work. Straight to dead letter, no retries. Set `runHook = false` when there is deliberately nothing to compensate.                              |

An uncaught exception is treated as `Fail`.

`Fail` and `Abort` are the important distinction: `Fail` means "the API timed out, try again"; `Abort` means "this
account no longer exists, stop." Without `Abort` the only way to give up is to fail `maxRetries` times, which wastes
time and misreports the reason.

Putting a command on `Success` saves a round trip, but you never see its result. If you need to react to the outcome,
`Yield` instead and inspect it on the next step.

### Seeing the previous command's result

`args.previousResult` is the `CommandResult` of the command your last step emitted (`null` on the first step, or if the
last step emitted none).

**A rejected command is data, not a job failure.** The model may have moved on while your job was waiting — that is
normal, not exceptional:

```kotlin
val previous = args.previousResult
if (previous is CommandResult.Failure) {
    // The order was cancelled while we were importing. Stop; this is not an error.
    return JobResult.Success()
}
```

The step still counts as completed. Retries and backoff are for `Fail`, not for commands Klerk declined to apply.

To make a step conditional on the model not having changed, use the existing optimistic-concurrency token:

```kotlin
JobResult.Yield(
    cursor = ...,
command = Command(AddRow, model = target, params = ...),
options = ProcessingOptions(CommandToken.requireUnmodifiedModel(target)),
)
```

### Fan-out and fan-in

A job spawns children by **declaring them in its yield**, never by calling `klerk.jobs.schedule` itself:

```kotlin
JobResult.Yield(
    cursor = cursor.copy(phase = AwaitingFiles),
    spawn = files.map { ImportOneFile.schedule(FileCursor(it)) },
    awaitSpawned = true,
)
```

Children commit in the same transaction as the parent's cursor, so a retried step never spawns them twice. This is also
why it is a declaration rather than a call: `klerk.jobs.schedule(child)` inside a step that later throws would spawn the
children again on the next attempt, with nothing able to detect it. Declaring them works for `JobType.Portable` too — a
remote worker returns "spawn these" as JSON like anything else.

**Waiting.** With `awaitSpawned = true` the parent moves to `Waiting` and is re-queued once **every** child has reached
a terminal state — `Succeeded`, `DeadLettered` or `Cancelled`. A child that dies wakes the parent; it never hangs.

The next step receives the outcomes:

```kotlin
override suspend fun step(args: JobStepArgs<ImportCursor, Ctx, Views>): JobResult<ImportCursor> {
    val failed = args.children.filter { it.status != JobStatus.Succeeded }
    if (failed.isNotEmpty()) {
        return JobResult.Abort("${failed.size} of ${args.children.size} files failed")
    }
    ...
}
```

`ChildOutcome` carries the child's id, terminal status, and the `result` value from its `Success` — so fan-in reads the
children's own report rather than reconstructing what happened from model state.

Four rules keep this from becoming a footgun:

- **A job may only await children it spawned.** Awaiting an arbitrary `JobId` would let two jobs await each other and
  hang forever, undetectably. Descendants-only makes cycles impossible by construction.
- **A `Waiting` parent does not occupy a dispatch slot** and does not count toward `maxConcurrent`. Otherwise parents
  fill the pool waiting for children that can never be dispatched.
- **`Waiting` is not stepping**, so it does not trip the [stuck-job guard](#stuck-jobs).
- **Children bypass admission control but not `maxDescendants`.** Children are scheduled by already-accepted work, so
  rejecting them would strand the parent mid-job. Instead each root job has a budget — `maxDescendants` (default 10 000)
  and `maxDepth` (default 8) — and exceeding it aborts the step. Without this, one job spawning children in a loop
  evades every protection in [Priority, backpressure and overload](#priority-backpressure-and-overload).

### Reading: Local vs Portable jobs

Two base classes, differing only in whether the step gets a `Reader`:

- **`JobType.Local`** — `args.reader` is available. Runs on the master node. Use this by default.
- **`JobType.Portable`** — no `Reader`. Everything the job needs is in its cursor. These will be eligible to run on
  remote worker nodes (a later milestone); today they run on the master like any other job.

Writing a job as `Portable` is a promise about *where it may run*, not only about the `Reader` — a job that needs a
machine-local file, a JVM type from your app, or a node-local secret is `Local` even if it never reads.

### Progress

Progress is structured so a UI can render a progress bar without parsing strings:

```kotlin
JobProgress(
    completed = 312,
    total = 500,          // null when the job doesn't know yet
    message = TranslatedText { it.importingFile(name) },   // optional, translatable
)
```

Progress is stored with the cursor, in the same transaction, and is visible through `klerk.jobs.getJob(id)` subject to
[authorization](#who-can-see-a-job).

`log` is separate and is for diagnostics, not for progress.

### Failure, retries and dead letters

- `Fail` → `Backoff`, retried with exponential backoff (base 3 s) until `maxRetries`, then **dead-lettered**.
- `Abort` → dead-lettered immediately.
- A dead-lettered job keeps its cursor, progress and log, and keeps its claim on any attached data it created.
- `deadLetterRetention: Duration?` (config) deletes dead letters after that long; `null` keeps them forever.

The full set of statuses:

| Status               | Terminal | Meaning                                                   |
|----------------------|----------|-----------------------------------------------------------|
| `Scheduled`          | no       | Waiting for `scheduleAt`                                  |
| `Ready`              | no       | Queued, waiting for a dispatch slot                       |
| `Running`            | no       | A step is executing                                       |
| `Waiting`            | no       | Awaiting spawned children; holds no dispatch slot         |
| `Backoff`            | no       | Failed, waiting to retry                                  |
| `Cancelling`         | no       | Cancellation requested; unwinding is in progress          |
| `Succeeded`          | yes      | Done                                                      |
| `Cancelled`          | yes      | Cancelled at a step boundary; `onCancelled` ran           |
| `DeadLettered`       | yes      | Gave up; `onDeadLettered` ran (or was skipped, see below) |
| `CompensationFailed` | yes      | Dead *and* the end-of-life hook could not complete        |

A dead-lettered job can be **resumed from its checkpoint** once you have fixed the cause. It cannot be restarted from
step 0: the commands from steps 1..n are already applied, and Klerk has no way to recognise re-emitted ones (see
[Remote workers](#remote-workers-and-what-blocks-them)).

### Cancellation and end-of-life hooks

```kotlin
override suspend fun onCancelled(args: JobEndArgs<Cursor, C, V>): JobResult<Cursor>
override suspend fun onDeadLettered(args: JobEndArgs<Cursor, C, V>): JobResult<Cursor>
```

Both hooks are **step machines themselves** — same `Yield`/`Success`/`Fail`/`Abort` vocabulary, same
one-command-per-step rule, same atomic checkpointing. So compensation that needs three mutations takes three steps and
survives a restart halfway through, which matters because compensation is exactly the kind of work that fails partway.

`args.failedAtCursor` is the job's cursor at the moment it died, preserved read-only for the life of the job. The hook
checkpoints its own progress separately, so unwinding never destroys the record of where the job got to.

**Use the cursor, not the audit log**, to decide what needs undoing. The cursor is a record your own code designed; the
audit log is authorization-gated and may have been erased by retention rules long before anyone looks at the dead
letter.

If a hook only needs to hand off to something bigger, it can spawn a compensation job like any other step
(`spawn = listOf(UndoImport.schedule(...))`) — but it rarely needs to, since it already has everything a job has.

Rules:

- **Cancellation takes effect at a step boundary, never mid-step.** Interrupting a running step would break the
  command-plus-checkpoint atomicity that everything else rests on. For long steps, check `args.cancellationRequested`
  and return early, cooperatively.
- **`Cancelling` is a distinct, non-terminal status.** `klerk.jobs.cancel(id)` returns as soon as the request is
  recorded; the job moves to `Cancelling` and reaches `Cancelled` only after the in-flight step returns, the cascade
  completes and `onCancelled` finishes. **Cancel latency is therefore the slowest step in the subtree** — a parent whose
  grandchild is halfway through a ten-minute step takes ten minutes to cancel. A UI should render `Cancelling` as its
  own state ("Cancelling…") rather than showing a button that appears to do nothing.
- **Cancelling a parent cascades to its children.** Children are cancelled first; the parent's `onCancelled` runs once
  they are all terminal.
- **Cancelling requires the same authorization as reading the job** — so a user watching their own progress bar can
  cancel their own job.
- **Hooks are not cancellable** and are not subject to admission control. Bound them with `maxSteps`/`maxDuration`.
- **A hook that exhausts its retries lands the job in `CompensationFailed`** — a distinct terminal status meaning "dead
  *and* the unwind didn't work." That is the queue a human must actually look at.
- **`Abort(runHook = false)`** skips the hook, for the case where aborting *is* the correct end state and there is
  nothing to compensate.
- **A job dead-lettered by [`UnloadableJobPolicy.DeadLetter`](#restarts-and-deploys) runs no hook**, because the thing
  that failed is deserializing the cursor the hook would need. This is the one dead-letter path with no compensation.

### Stuck jobs

A step counts as progress if it changed the **cursor** or the **progress**. Three consecutive steps that change neither
are treated as a livelock and the job is aborted to the dead letter. A job reporting "file 312 of 500" is by definition
alive; a job re-emitting a command that keeps being rejected, forever, is not.

Optional explicit caps, both `null` by default:

```kotlin
override val maxSteps = 10_000
override val maxDuration = 6.hours
```

### Priority, backpressure and overload

Klerk is single-writer. Every yielded step is a command against the one node that can write, so a large job competes
with interactive traffic. The job module therefore sheds load rather than letting the queue grow without bound.

**Priority classes**, each with a queueing-delay budget:

| Class         | Default budget | For                         |
|---------------|----------------|-----------------------------|
| `Interactive` | 5 s            | The user is watching        |
| `High`        | 1 min          | Should happen promptly      |
| `Normal`      | 10 min         | Default                     |
| `Bulk`        | 2 h            | Imports, backfills, cleanup |

`priority = null` (the default) inherits the priority of the command that scheduled the job.

**Admission control is delay-based, not depth-based.** Klerk tracks how long the *oldest ready job* in each class has
been waiting. Queue depth is a poor signal — one entry may be a one-step webhook and another a 500-step import, and a
deep queue that is draining fast is healthier than a shallow one that has not moved in ten minutes. Waiting time
captures both.

The default policy, `AdmissionPolicy.DelayBudget`, sheds a class when its oldest ready job has exceeded the class budget
continuously for a short interval. You can replace it with a named function in config:

```kotlin
jobs {
    admission(::myAdmissionPolicy)
}

fun myAdmissionPolicy(args: AdmissionArgs<Ctx>): AdmissionDecision =
    when {
        args.queue.oldestReadyAge(JobPriority.Interactive) > 10.seconds -> AdmissionDecision.Downgrade(JobPriority.Bulk)
        else -> AdmissionDecision.Allow
    }
```

`AdmissionDecision` is `Allow`, `Downgrade(priority)`, `Delay(until)` or `Deny(Problem)`. **Prefer `Downgrade`.** The
best answer to load is almost always "accept this as `Bulk`", not "fail the user's checkout"; `Deny` is a last resort
and fails the scheduling command with `KlerkErrorCode.JobQueueOverloaded`.

`args.queue` exposes per-class oldest-ready-age, depth and running counts; `args.job` is the candidate; `args.context`
is the scheduling actor's context.

Four rules:

- **The policy runs inside command processing, on the single writer. Do no IO in it.** A database lookup here makes
  every command in the system slower.
- **Only new work goes through admission.** Yields, retries, spawned children and end-of-life hooks never do. A backlog
  of retrying jobs — because a payment provider is down — must not start failing unrelated user writes.
- **`dryRun` does not run the policy.** Otherwise pre-validation in a UI would show an error that appears and vanishes
  with the queue. Command handling can therefore fail with `JobQueueOverloaded` even though `dryRun` passed.
- **The hard queue cap is not overridable.** A policy that always returns `Allow` must still not be able to exhaust
  memory.

`maxConcurrent` caps how many instances of a job type **run at once**. It does not cap how many may be scheduled — that
would fail the 51st user's command for reasons they cannot perceive.

### Recurring jobs

Recurring schedules are **declared statically in config**, next to the job types they run:

```kotlin
jobs {
    register(NightlyCleanup)
    cron(NightlyCleanup, "0 3 * * *") {
        catchUp = CatchUp.RunOnce      // RunOnce | RunAll | Skip
        overlap = Overlap.Skip         // Skip | Queue | Allow
        jitter = 5.minutes
        cursor = CleanupCursor(...)
    }
}
```

- **Times are UTC.** No timezone handling, deliberately: a daily 02:30 local job does not exist on the spring-forward
  day and happens twice in autumn, and there is no answer to that which is correct for everyone.
- **`catchUp`** decides what happens to fires missed while the node was down. Four hours of downtime on an hourly
  schedule: `RunOnce` fires once on recovery, `RunAll` fires four times, `Skip` fires none. `RunOnce` is the default
  because it is what people mean by "the nightly cleanup should still run."
- **`overlap`** decides what happens when the previous run is still going as the next fires. `Skip` is the default;
  `Queue` runs it afterwards; `Allow` runs both. This is a property of the schedule, distinct from the job type's
  `maxConcurrent`.
- **`jitter`** spreads the fire time over a random window to avoid the thundering herd problem.
- **Cron fires are new work** and go through admission control like anything else.
- A cron whose `JobName` is not registered at startup obeys the same
  [`UnloadableJobPolicy`](#restarts-and-deploys) as a persisted job.

**Cron or a state-machine time trigger?** Time triggers (see [state-machines.md](state-machines.md)) belong to a *model
instance* and are part of its lifecycle — "cancel this booking if unconfirmed after 48 h". Crons are system-wide
recurring work with no model behind them — "delete expired sessions every night".

### Attached data

A job may create [attached data](attached-data.md) before a command references it. Such data is **claimed by the job**:

- The orphan reaper deletes attached data only when it has **no model reference and no job claim**.
- Deleting a dead-lettered job releases its claims, but never deletes data a committed command attached to a live model.

A long-running job's working set is therefore safe from the reaper for as long as the job lives, including while
dead-lettered and awaiting a human.

### Who can see a job

A job runs as an **agent**, declared on the job type:

- `JobAgent.System` — full authority. Config is trusted code, so declaring this is a deliberate, privileged act.
- `JobAgent.Scheduler` — the actor that scheduled the job. If that actor loses permission mid-job, subsequent commands
  simply fail; your step sees it in `previousResult` and decides whether to `Success`, `Abort`, or do something else.

Job metadata (status, progress, log) is authorization-checked.

```kotlin
authorization {
    jobs {
        positive {
            rule(::usersCanSeeTheirOwnJobs)
            rule(::adminsCanSeeAllJobs)
        }
        negative { }
    }
}
```

### Restarts and deploys

On `klerk.meta.start()` Klerk reloads persisted jobs. Two things can go wrong, both governed by one setting:

```kotlin
jobs {
    onUnloadableJob = UnloadableJobPolicy.FailToStart   // default
    // or UnloadableJobPolicy.DeadLetter
}
```

- **A job whose `JobName` is no longer registered** — you deleted or renamed a job type while instances were pending.
- **A cursor that no longer deserializes** — you changed the cursor type while instances were checkpointed against the
  old shape.

`FailToStart` is the default. It is recommended that you control this setting via an environment variable so that you
don't have to rebuild the software to change this setting. As an alternative, if you use klerk-web to generate an admin
UI, you can delete the jobs from there.

The practical rule: **treat cursor types as a persisted schema.** Add optional fields; do not remove or retype fields
while jobs may be in flight.

### Testing

Three levels, easiest first.

**1. Drive the step function directly.** The step's effect on Klerk state is a *return value*, so you can run a 500-step
job with no Klerk instance, no scheduler and no threads — feeding each returned cursor back in and asserting on the
commands it would have emitted. Fake whatever client the job talks to.

```kotlin
@Test
fun `import emits one CreateBook per file`() = runTest {
        var cursor = ImportCursor(remaining = listOf(FileName("a"), FileName("b")), target = libraryId)
        val emitted = mutableListOf<Command<*, *>>()

        while (true) {
            when (val result = ImportBooks.step(JobStepArgs(cursor = cursor, previousResult = null, ...))) {
                is JobResult.Yield -> { result.command?.let(emitted::add); cursor = result.cursor }
                is JobResult.Success -> break
                else -> fail("unexpected $result")
            }
        }

        assertEquals(2, emitted.size)
    }
```

**2. Run the scheduler deterministically.** In tests, configure manual execution and drive it yourself — no background
thread, no sleeping:

```kotlin
jobs { execution = JobExecution.Manual }

klerk.jobs.runUntilIdle(maxSteps = 10_000)
klerk.jobs.step()      // exactly one step
```

**3. Time-travel.** The scheduler takes its time from a clock on the config rather than `Clock.System`, so `scheduleAt`,
backoff, cron and delay-based admission are all controllable in tests. See [time.md](time.md) — this clock is new; time
in Klerk today comes either from a caller's `Context` or, for background work, from a non-injectable system call.

### Remote workers, and what blocks them

`JobType.Portable` exists so that jobs can later run on worker nodes, possibly written in other languages: a worker
receives a cursor as JSON, does the work, and returns a command as JSON for the master to apply.

That milestone is **blocked on idempotent command tokens.** Locally, a step's command and cursor commit in one
transaction, so a resumed job can never re-emit a committed command — no deduplication is needed and none exists. Over a
network the response can be lost after the master committed, so the worker retries a step the master already applied.
Detecting that requires `CommandToken` to carry an explicit identity (`jobId` + step number) separate from its freshness
timestamp, and requires used tokens to be persisted rather than held in memory. Neither exists today.

Until then, jobs run only on the master node.

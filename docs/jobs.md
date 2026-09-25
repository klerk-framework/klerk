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
    onEvent(ImproveAuthor) {
        unmanagedJob(::showNotification)
        transitionTo(Improving)
    }
}

fun showNotification(args: InstanceEventArgs<Author, Nothing?, Ctx, Views>) {
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
data class ImportCursor(val remaining: List<FileName>, val target: ModelID<Library>, val done: Int, val total: Int)

object ImportBooks : JobType.Local<ImportCursor, Ctx, Views>() {

    override val name = JobName("import-books")
    override val agent = JobAgent.System
    override val priority = JobPriority.Bulk

    override suspend fun step(args: JobStepArgs.Local<ImportCursor, Ctx, Views>): JobResult<ImportCursor, Ctx, Views> {
        val cursor = args.cursor
        val next = cursor.remaining.firstOrNull() ?: return JobResult.Success()

        val parsed = parseBookFile(next)          // ordinary work: IO, parsing, an HTTP call

        return JobResult.Yield(
            cursor = cursor.copy(remaining = cursor.remaining.drop(1), done = cursor.done + 1),
            command = Command(CreateBook, parsed),
            progress = JobProgress(
                completed = cursor.done,
                total = cursor.total,
                message = "Importing $next",
            ),
            log = listOf(args.info("Parsed $next")),
        )
    }
}
```

Register the type in `SpecificationBuilder` so Klerk can resolve it by name after a restart:

```kotlin
jobs {
    register(ImportBooks)
    register(NotifyBookStores)
}
```

The persisted record holds `JobName`. Renaming the Kotlin object is safe; changing `name` is not
(see [Restarts and deploys](#restarts-and-deploys)).

### The cursor is a persisted schema

The cursor is serialized with `kotlinx.serialization`, so a cursor class must be `@Serializable`. Klerk derives the
serializer from the type argument you declared, so there is normally nothing to write; a job type whose cursor cannot be
serialized fails at **specification build time**, not on its first run.

`ModelID`, `AttachedBlobID` and `AttachedStringID` are serializable out of the box — a model's own props class needs no
annotation for its id to appear in a cursor. For an `Instant`, use
`@Serializable(with = KlerkInstantSerializer::class)`, which stores it at the same microsecond precision as everything
else in Klerk so that it survives a round-trip unchanged.

If `@Serializable` does not suit — a legacy format, a type you cannot annotate — override `codec`:

```kotlin
override val codec = object : CursorCodec<ImportCursor> { ... }
```

### Scheduling

From a state machine:

```kotlin
onEvent(ChangeName) {
    update(::changeNameOfAuthor)
    job(::notifyBookStores)
}

fun notifyBookStores(args: InstanceEventArgs<Author, ChangeNameParams, Ctx, Views>): DeclaredJob<Ctx, Views> =
    NotifyBookStores.declare(NotifyCursor(author = args.model.id))
```

Use `jobs` instead of `job` when a single event should schedule more than one:

```kotlin
onEvent(ChangeName) {
    update(::changeNameOfAuthor)
    jobs(::notifyBookStoresAndPartners)
}

fun notifyBookStoresAndPartners(args: InstanceEventArgs<Author, ChangeNameParams, Ctx, Views>): List<DeclaredJob<Ctx, Views>> =
    listOf(NotifyBookStores.declare(NotifyCursor(author = args.model.id)), NotifyPartners.declare(...))
```

Or directly, for work no command is responsible for:

```kotlin
val id = klerk.jobs.schedule(ImportBooks.declare(ImportCursor(...), scheduleAt = tomorrow), context)
```

The context is what decides the job's **owner** — the actor the [authorization rules](#who-can-see-and-control-a-job)
see — and what the [admission policy](#priority-backpressure-and-overload) is given, so scheduling this way can be
refused when the queue is not draining — `schedule` then throws `JobRejectedException`, whose `code` and `problem` say why.

Jobs scheduled by a command are persisted in that command's transaction. If the command fails, no job is scheduled.
`CommandResult.Success.jobs` lists the ids of what was scheduled.

### What a step returns

| Result                                                      | Meaning                                                                                                                                                         |
|-------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Yield(cursor, command?, spawn?, awaitSpawned?, progress?)` | Not done. Command, spawned children and cursor commit atomically; job re-queued at the tail of its priority class, or moved to `Waiting` if it awaits children. |
| `Success(command?, progress?, result?)`                     | Done. Not retried. A command here commits with the terminal status. `result` reaches the parent, if any.                                        |
| `Fail(reason)`                                              | This attempt failed. Retried with exponential backoff until `maxRetries`, then dead-lettered.                                                                   |
| `Abort(reason, runHook = true)`                             | This will never work. Straight to dead letter, no retries. Set `runHook = false` when there is deliberately nothing to compensate.                              |

An uncaught exception is treated as `Fail`. Its reason names only the exception type, since the reason is shown to
everyone who may see the job; the exception itself is logged. The reason passed to `Fail` or `Abort` is shown as it is.

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

**`previousResult` does not survive a restart.** A `CommandResult` cannot be faithfully rebuilt from storage, and Klerk
would rather hand you `null` than a lossy stand-in. If your job must know the outcome even across a restart, record what
you need in the cursor — the next step's cursor is written in the same transaction as the command, so whatever you put
there is exactly as durable as the command itself.

To make a step conditional on the model not having changed, use the existing optimistic-concurrency token:

```kotlin
JobResult.Yield(
    cursor = ...,
command = Command(AddRow, target, ...),
options = ProcessingOptions(CommandToken.requireUnmodifiedModel(target)),
)
```

### Fan-out and fan-in

A job spawns children by **declaring them in its yield**, never by calling `klerk.jobs.schedule` itself:

```kotlin
JobResult.Yield(
    cursor = cursor.copy(phase = AwaitingFiles),
    spawn = files.map { ImportOneFile.declare(FileCursor(it)) },
    awaitSpawned = true,
)
```

Children commit in the same transaction as the parent's cursor, so a retried step never spawns them twice. This is also
why it is a declaration rather than a call: `klerk.jobs.schedule(child)` inside a step that later throws would spawn the
children again on the next attempt, with nothing able to detect it. Declaring them works for `JobType.Portable` too — a
remote worker returns "spawn these" as JSON like anything else.

**Waiting.** With `awaitSpawned = true` the parent moves to `Waiting` and is re-queued once **every** child has reached
a terminal state — `Succeeded`, `DeadLettered` or `Cancelled`. A child that dies wakes the parent.

The next step receives the outcomes:

```kotlin
override suspend fun step(args: JobStepArgs.Local<ImportCursor, Ctx, Views>): JobResult<ImportCursor, Ctx, Views> {
    val failed = args.children.filter { it.status != JobStatus.Succeeded }
    if (failed.isNotEmpty()) {
        return JobResult.Abort("${failed.size} of ${args.children.size} files failed")
    }
    ...
}
```

`ChildOutcome` carries the child's id, terminal status, and the `result` value from its `Success`. A result is a
string on the wire, encoded and decoded with one codec — the same JSON cursors use:

```kotlin
// in the child
JobResult.Success(result = encodeJobResult(ImportSummary(imported = 3)))

// in the parent, which is the one that knows what its children return
val summaries = args.children.mapNotNull { it.resultAs<ImportSummary>() }
```

### Reading: Local vs Portable jobs

Two base classes, differing only in whether the step gets a `Reader`:

- **`JobType.Local`** — the step takes a `JobStepArgs.Local`, so `args.reader` and `args.klerk` are available. Runs on
  the master node. Use this by default.
- **`JobType.Portable`** — the step takes a `JobStepArgs.Portable`, which has no `Reader`. Everything the job needs is
  in its cursor. These will be eligible to run on remote worker nodes (a later milestone); today they run on the master
  like any other job.

The split is in the types rather than in a runtime check, so a `Portable` job cannot read by accident.

`args.klerk` is the framework itself, for the subsystems a step may need — `attachedData` above all. It is **not** for
issuing commands: return the command from the step instead, so that it commits together with the checkpoint. Read
through `args.reader`, not `klerk.read`.

Writing a job as `Portable` is a promise about *where it may run*, not only about the `Reader` — a job that needs a
machine-local file, a JVM type from your app, or a node-local secret is `Local` even if it never reads.

### Progress

Progress is structured so a UI can render a progress bar without parsing strings:

```kotlin
JobProgress(
    completed = 312,
    total = 500,                        // null when the job doesn't know yet
    message = "Importing $name",        // optional
)
```

Progress is stored with the cursor, in the same transaction, and is visible through `klerk.jobs.get(id, context)`
subject to [authorization](#who-can-see-and-control-a-job).

### Reading jobs inside a read block

`klerk.jobs.get(id, context)` and `klerk.jobs.all(context)` take the read lock themselves, so they are for use
*outside* a read block and fail with an explanatory error if called inside one. Inside a read block, use `jobs` on the
reader:

```kotlin
klerk.read(context) {
    val job = jobs.get(id)
    val all = jobs.all()
}
```

What you read there is part of the block's snapshot, exactly like a model: nothing can change it while the block runs,
so a command and the jobs it scheduled are always seen together. The cost is that a long read block delays job dispatch,
since the dispatcher takes the write lock to claim a job.

`message` is a plain string without any specific meaning. It will typically not be shown to the user, but may appear in
an admin UI.

`log` is separate and is for diagnostics, not for progress. Build entries with the helpers on the step's args, which
stamp them with the step's time:

```kotlin
return JobResult.Fail("timeout", log = listOf(args.warn("The book API did not answer within 30 s")))
```

The log is capped at the most recent 200 entries per job, so a long-running job cannot grow without bound.

### Failure, retries and dead letters

- `Fail` → `Backoff`, retried with exponential backoff (base 3 s) until `maxRetries`, then **dead-lettered**.
- `Abort` → dead-lettered immediately.
- A dead-lettered job keeps its cursor, progress and log, and keeps its claim on any attached data it created.

Terminal jobs are cleaned up automatically. Three settings in `KlerkSettings.jobs`, each `Duration` and each defaulting
to 30 days, delete a terminal job once it has aged past their value:

| Setting               | Governs                              |
|-----------------------|--------------------------------------|
| `succeededRetention`  | `Succeeded`                          |
| `cancelledRetention`  | `Cancelled`                          |
| `deadLetterRetention` | `DeadLettered`, `CompensationFailed` |

A succeeded job has already released its attached-data claims (see below), so its retention is only about bounding
storage and event log history. A cancelled or dead-lettered job keeps its claims until it is deleted, so these settings
also bound how long that data can leak.

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

A dead-lettered job can be **resumed from its checkpoint** with `klerk.jobs.resume(id, context)` once you have fixed the
cause. It cannot be restarted from step 0: the commands from steps 1..n are already applied, and Klerk has no way to
recognise re-emitted ones (see [Remote workers](#remote-workers-and-what-blocks-them)).

`klerk.jobs.delete(id, context)` removes a terminal job and releases any [claim](#attached-data) it holds on attached
data.

### Cancellation and end-of-life hooks

```kotlin
// on JobType.Local — the Portable variants take JobEndArgs.Portable, which has no Reader
override suspend fun onCancelled(args: JobEndArgs.Local<Cursor, C, V>): JobResult<Cursor, C, V>
override suspend fun onDeadLettered(args: JobEndArgs.Local<Cursor, C, V>): JobResult<Cursor, C, V>
```

Both hooks are **step machines themselves** — same `Yield`/`Success`/`Fail`/`Abort` vocabulary, same
one-command-per-step rule, same atomic checkpointing. So compensation that needs three mutations takes three steps and
survives a restart halfway through, which matters because compensation is exactly the kind of work that fails partway.

`args.failedAtCursor` is the job's cursor at the moment it died, preserved read-only for the life of the job. The hook
checkpoints its own progress separately, so unwinding never destroys the record of where the job got to.

**Use the cursor, not the event log**, to decide what needs undoing. The cursor is a record your own code designed; the
event log is authorization-gated and may have been erased by retention rules long before anyone looks at the dead
letter.

If a hook only needs to hand off to something bigger, it can spawn a compensation job like any other step
(`spawn = listOf(UndoImport.declare(...))`) — but it rarely needs to, since it already has everything a job has.

Rules:

- **Cancellation takes effect at a step boundary, never mid-step.** Interrupting a running step would break the
  command-plus-checkpoint atomicity that everything else rests on. For long steps, check `args.cancellationRequested`
  and return early, cooperatively.
- **`Cancelling` is a distinct, non-terminal status.** `klerk.jobs.cancel(id, context)` returns as soon as the request
  is recorded; the job moves to `Cancelling` and reaches `Cancelled` only after the in-flight step returns, the cascade
  completes and `onCancelled` finishes. **Cancel latency is therefore the slowest step in the subtree** — a parent whose
  grandchild is halfway through a ten-minute step takes ten minutes to cancel. A UI should render `Cancelling` as its
  own state ("Cancelling…") rather than showing a button that appears to do nothing.
- **Cancelling a parent cascades to its children.** Children are cancelled first; the parent's `onCancelled` runs once
  they are all terminal.
- **Cancelling, resuming and deleting are gated by the `controlJobs` rules**, not by the rules that let an actor see the
  job (see [Who can see and control a job](#who-can-see-and-control-a-job)).
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

A job type's `priority` defaults to `Normal`. Setting it to `null` means "inherit": a spawned child takes its parent's
class, and anything else lands in `Normal`. An individual instance can override the type with
`MyJob.declare(cursor, priority = JobPriority.High)`.

**Admission control is delay-based, not depth-based.** Klerk tracks how long the *oldest ready job* in each class has
been waiting. Queue depth is a poor signal — one entry may be a one-step webhook and another a 500-step import, and a
deep queue that is draining fast is healthier than a shallow one that has not moved in ten minutes. Waiting time
captures both.

The default policy, `AdmissionPolicy.DelayBudget`, sheds a class when its oldest ready job has exceeded the class budget
continuously for a short interval. You can replace it with a named function in the specification:

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
and fails the scheduling command with `KlerkErrorCode.JobQueueOverloaded` (`AdmissionDecision.Deny.overloaded(...)`
builds one).

The default policy does exactly that: a class that has been over budget for ten seconds straight is *downgraded* one
class, and only `Bulk` — the bottom of the ladder, over its two-hour budget — is ever refused. `args.queue
.overBudgetSince(priority)` is what gives the policy hysteresis without keeping state of its own, so it can stay a pure
function.

`args.queue` exposes per-class oldest-ready-age, depth and running counts; `args.job` is the candidate; `args.context`
is the scheduling actor's context.

Four rules:

- **The policy runs inside command processing, on the single writer. Do no IO in it.** A database lookup here makes
  every command in the system slower. If the policy throws, the job is refused with `KlerkErrorCode.Internal`.
- **Only new work goes through admission.** Yields, retries, spawned children and end-of-life hooks never do. A backlog
  of retrying jobs — because a payment provider is down — must not start failing unrelated user writes.
- **`dryRun` does not run the policy.** Otherwise pre-validation in a UI would show an error that appears and vanishes
  with the queue. Command handling can therefore fail with `JobQueueOverloaded` even though `dryRun` passed.
- **The hard queue cap is not overridable.** A policy that always returns `Allow` must still not be able to exhaust
  memory.

`maxConcurrent` caps how many instances of a job type **run at once**. It does not cap how many may be scheduled — that
would fail the 51st user's command for reasons they cannot perceive.

### Recurring jobs

Recurring schedules are **declared statically in the specification**, next to the job types they run:

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

### Jobs from a plugin

A plugin registers its own job types and crons from `mergeSpecification`:

```kotlin
override fun mergeSpecification(previous: Specification<C, V>): Specification<C, V> =
    previous.withJobs {
        register(sweepStagingArea)
        cron(sweepStagingArea, "0 * * * *") { cursor = "" }
    }
```

A plugin can add work, not change how the job module runs: `execution`, `pollInterval`, `hardQueueLimit` and the rest
live in `KlerkSettings.jobs`, which a plugin never sees. Job names are global, so prefix a plugin's names with the
plugin's own — registering a name the application already used fails when the specification is built.

Some of the official Klerk plugins (and Klerk itself) register their own jobs and crons.

### Attached data

A job may create [attached data](attached-data.md) before a command references it. Such data is **claimed by the job**,
automatically — `klerk.attachedData.prepare(...)` called from inside a step records the claim, so there is nothing to
declare:

- The orphan reaper deletes attached data only when it has **no model reference and no job claim**.
- A job that **succeeds** releases its claims: its work is done, and whatever it prepared but never attached goes back
  to being governed by its lease.
- A job that died or was cancelled keeps them, so that a human can still see what it was working on. Deleting the job
  releases them, but never deletes data a committed command attached to a live model.
- Deletion normally happens on its own once `cancelledRetention` or `deadLetterRetention`
  (see [Failure, retries and dead letters](#failure-retries-and-dead-letters)) has elapsed.

A long-running job's working set is therefore safe from the reaper for as long as the job is running, including while
dead-lettered and awaiting a human. The flip side is that a job that ended without succeeding holds its claim until it
is deleted, which is what `deadLetterRetention` is for.

### Who can see and control a job

A job runs as an **agent**, declared on the job type:

- `JobAgent.System` — full authority, and the default: the job's commands bypass the authorization rules, but are still
  validated. Specification is trusted code, so declaring this is a deliberate, privileged act.
- `JobAgent.Scheduler` — the actor that scheduled the job. If that actor loses permission mid-job, subsequent commands
  simply fail; your step sees it in `previousResult` and decides whether to `Success`, `Abort`, or do something else.

`JobAgent.Scheduler` requires `jobContextProvider(...)` in the specification, since Klerk cannot construct a context for
an arbitrary actor of your own context type. Omitting it is a configuration error, caught at startup.

The actor is rebuilt from what was persisted, so an actor identified by a model arrives as a `ModelReferenceIdentity` —
its id, not the model. `jobContextProvider` is an ordinary function without a reader, so a context that normally
carries the loaded model cannot fill that in:

```kotlin
jobContextProvider(::jobContext)

fun jobContext(request: JobContextRequest): Ctx = Ctx(actor = request.actor, time = request.time)
```

Write the rules a job's commands must pass against the actor's **id** rather than against a model your context only
holds when a request loaded it:

```kotlin
class Ctx(override val actor: ActorIdentity, val user: Model<User>? = null, ...) : KlerkContext {
    val userId: ModelID<*>? get() = user?.id ?: actor.id
}

fun onlyByOwner(args: InstanceEventArgs<Order, Nothing?, Ctx, Views>): PropertyCollectionValidity =
    if (args.model.props.owner == args.context.userId) Valid else Invalid()
```

A rule that reads `context.user` instead rejects every command the job emits, and the job still reports **Succeeded** —
a rejected command is data, not a job failure (see [What a step returns](#what-a-step-returns)). Nothing looks broken
except that the model never moves.

Job metadata (status, progress, log) is gated by the `readJobs` rules. Cancelling, resuming and deleting a job is gated
separately by the `controlJobs` rules, which also get the `operation`:

```kotlin
authorization {
    readJobs {
        positive(::usersCanSeeTheirOwnJobs, ::adminsCanSeeAllJobs)
    }
    controlJobs {
        positive(::usersCanCancelTheirOwnJobs, ::adminsCanControlAllJobs)
    }
}

fun usersCanSeeTheirOwnJobs(args: JobReadRuleArgs<Ctx, Views>): PositiveAuthorization =
    if (args.isOwnedByActor()) PositiveAuthorization.Allow else PositiveAuthorization.NoOpinion

fun usersCanCancelTheirOwnJobs(args: JobControlRuleArgs<Ctx, Views>): PositiveAuthorization =
    if (args.operation == JobOperation.Cancel && args.isOwnedByActor()) {
        PositiveAuthorization.Allow
    } else {
        PositiveAuthorization.NoOpinion
    }
```

Resuming a `JobAgent.System` job runs its remaining steps with full authority, so allow `Resume` only for trusted
actors; `args.job.agent` tells which agent a job has.

The owner is the actor whose context scheduled the job, recorded at scheduling time and available as `args.job.owner`.
Only the id survives storage, so an actor that was a `ModelIdentity` comes back as a `ModelReferenceIdentity`;
`isOwnedByActor()` uses `ActorIdentity.isSameAs`, so the same user is recognised either way. An `Unauthenticated` actor
never owns a job (see [context.md](context.md)).

### Restarts and deploys

On `klerk.meta.start()` Klerk reloads persisted jobs. Two things can go wrong, both governed by one setting:

```kotlin
KlerkSettings(
    jobs = JobSettings(
        onUnloadableJob = UnloadableJobPolicy.FailToStart,   // default
        // or UnloadableJobPolicy.DeadLetter
    ),
)
```

- **A job whose `JobName` is no longer registered** — you deleted or renamed a job type while instances were pending.
- **A cursor that no longer deserializes** — you changed the cursor type while instances were checkpointed against the
  old shape.

`FailToStart` is the default. It is recommended that you control this setting via e.g. an environment variable so that
you don't have to rebuild the software to change this setting. As an alternative, if you use klerk-web to generate an
admin UI, you can delete the jobs from there.

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
            val args =
                JobStepArgs.Local(
                    cursor,
                    previousResult = null,
                    job = someJobInfo,
                    context = ctx,
                    reader = reader,
                    klerk = klerk
                )
            when (val result = ImportBooks.step(args)) {
                is JobResult.Yield -> {
                    result.command?.let(emitted::add); cursor = result.cursor
                }
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
import dev.klerkframework.klerk.testing.runUntilIdle   // step() and runUntilIdle() live in klerk.testing
import dev.klerkframework.klerk.testing.step

KlerkSettings(jobs = JobSettings(execution = JobExecution.Manual))

klerk.jobs.runUntilIdle(maxSteps = 10_000)   // returns how many steps ran
klerk.jobs.step()                            // exactly one step; false if nothing was ready
```

`runUntilIdle` stops when nothing is *ready* — a job waiting for a `scheduleAt` or a backoff that has not arrived on the
configured clock is not ready, so it returns rather than spinning. Advance the clock and call it again.

**3. Time-travel.** All background work — job scheduling, retry backoff, cron, delay-based admission and state-machine
time triggers — reads its time from `KlerkSettings.clock` rather than `Clock.System`:

```kotlin
val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

val settings = KlerkSettings(
    persistence = RamStorage(),
    clock = clock,
    jobs = JobSettings(execution = JobExecution.Manual),
)

val specification = SpecificationBuilder<Ctx, Views>(views).build {
    jobContextProvider(::jobContext)     // so a step's own context.time follows the clock too
    ...
}

fun jobContext(request: JobContextRequest): Ctx = Ctx(actor = request.actor, time = request.time)

// ...
clock += 3.seconds                       // the first retry backoff has now elapsed
klerk.jobs.runUntilIdle()
```

See [time.md](time.md) for how this relates to the time a command carries in its `Ctx`.

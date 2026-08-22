# Time

Time enters a Klerk system in more than one way, and picking the wrong one is easy. This page is the map.

## The one rule

**Never call `Clock.System.now()` in business logic.** Read the time from the `Ctx`
([context.md](context.md)) instead. Everything else on this page follows from that rule, and every testing story in
Klerk depends on it.

Use `kotlin.time.Clock` and `kotlin.time.Instant` — not the `kotlinx.datetime` equivalents.

## Where the current time comes from

| Situation                                                        | Source of "now"                                                      |
|------------------------------------------------------------------|----------------------------------------------------------------------|
| Handling a command                                               | `Context.time`, supplied by the caller                               |
| Reading                                                          | `Context.time`                                                       |
| Validation, authorization, `onEnter`/`onExit`/`onEvent`          | `args.time`, which is the context's time                             |
| A state-machine time trigger firing                              | The **settings clock**; the context comes from `systemContextProvider` |
| Deciding a job is ready, a backoff has elapsed, a cron has fired | The **settings clock**                                                 |
| A job step running                                               | The **settings clock**, via `jobContextProvider`                       |

The split matters: **actor-driven work carries its own time; background work is given one.** A test controls
actor-driven time by constructing a `Ctx` with a fixed `time`, and background time by setting the settings clock.

## The settings clock

In tests, you may want to control the time of the system.

```kotlin
val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

val settings = KlerkSettings(
    persistence = RamStorage(),
    clock = clock,                        // defaults to Clock.System
)

SpecificationBuilder<Ctx, Views>(views).build {
    jobContextProvider(::jobContext)      // gives a job step a context whose time is the clock's
    ...
}

fun jobContext(request: JobContextRequest): Ctx = Ctx(actor = request.actor, time = request.time)
```

Everything Klerk does on its own initiative reads from that clock, so `clock += 1.hours` is how a test travels forward
without sleeping. It deliberately does **not** affect commands and reads: those carry the caller's time, and a test that
wants to control them constructs the context it wants.

`jobContextProvider` is optional. Without it a job step runs with the context from `systemContextProvider`, whose time
is whatever your own context type decides — usually the wall clock, which is exactly the thing a test cannot control.
Configure it if you want a job's own view of time to follow the clock, or if any job type uses `JobAgent.Scheduler`
(which needs a context for an actor other than the system, and is a configuration error without it).

## Choosing a mechanism for "do this later"

Four mechanisms can make something happen at a future time. They are not interchangeable.

| Mechanism                            | Belongs to                    | Use it for                                                  |
|--------------------------------------|-------------------------------|-------------------------------------------------------------|
| **Time trigger** (`after`, `atTime`) | One model instance, one state | "Cancel this booking if it is still unconfirmed after 48 h" |
| **`scheduleAt` on a job**            | One job instance              | "Send this reminder email tomorrow morning"                 |
| **Cron** (`cron(...)` in the specification)     | The system                    | "Delete expired sessions every night at 03:00"              |
| **A yielding job**                   | One job instance              | "Work through these 10 000 files, a bit at a time"          |

The distinctions that actually decide it:

- **Is there a model whose lifecycle this belongs to?** If yes, it is a time trigger. A time trigger lives in a state,
  fires relative to when the model entered that state, and is cancelled automatically when the model leaves it. Nothing
  else gives you that.
- **Is it recurring and system-wide, with no model behind it?** That is a cron.
- **Is it one deferred piece of work that must survive a restart?** That is a job with `scheduleAt`.
- **Is it long work that should not occupy the system in one go?** That is a yielding job — the passage of time is
  incidental; what you actually want is to be interruptible. See [jobs.md](jobs.md).

A time trigger that needs to do durable, retryable work should fire a **job**, not do the work inline. Triggers are not
retried (see [state-machines.md](state-machines.md)); jobs are.

## Testing time

- **Actor-driven time** — construct a `Ctx` with the `time` you want. Business logic reading `args.time` sees it.
- **Deferred work** — set a `MutableClock` as the settings clock and advance it. Combined with
  `KlerkSettings(jobs = JobSettings(execution = JobExecution.Manual))` and `klerk.jobs.runUntilIdle()`, this makes `scheduleAt`, retry backoff,
  cron and delay-based admission fully deterministic with no sleeping. See [jobs.md](jobs.md#testing).
- **Time triggers** follow the settings clock too, but the thread that polls them still wakes on real time, so advancing
  the clock makes a trigger *eligible* rather than making it fire immediately.

## Related

- [context.md](context.md) — where `time` lives and how `systemContextProvider` supplies one to background work
- [state-machines.md](state-machines.md) — `after` and `atTime`
- [jobs.md](jobs.md) — `scheduleAt`, cron, yielding, and the job testing story
- [testing.md](testing.md) — controlling time in tests

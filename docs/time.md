# Time

Time enters a Klerk system in more than one way, and picking the wrong one is easy. This page is the map.

## The one rule

**Never call `Clock.System.now()` in business logic.** Read the time from the `Context`
([context.md](context.md)) instead. Everything else on this page follows from that rule, and every testing story in
Klerk depends on it.

Use `kotlin.time.Clock` and `kotlin.time.Instant` — not the `kotlinx.datetime` equivalents.

## Where the current time comes from

| Situation                                               | Source of "now"                                              |
|---------------------------------------------------------|--------------------------------------------------------------|
| Handling a command                                      | `Context.time`, supplied by the caller                       |
| Reading                                                 | `Context.time`                                               |
| Validation, authorization, `onEnter`/`onExit`/`onEvent` | `args.time`, which is the context's time                     |
| A state-machine time trigger firing                     | Klerk itself; the context comes from `systemContextProvider` |
| A job step running                                      | Klerk itself; the context comes from `systemContextProvider` |

The split matters: **actor-driven work carries its own time; background work is given one.** A test can therefore
control actor-driven time simply by constructing a `Context` with a fixed `time`.

## Choosing a mechanism for "do this later"

Four mechanisms can make something happen at a future time. They are not interchangeable.

| Mechanism                            | Belongs to                    | Use it for                                                  |
|--------------------------------------|-------------------------------|-------------------------------------------------------------|
| **Time trigger** (`after`, `atTime`) | One model instance, one state | "Cancel this booking if it is still unconfirmed after 48 h" |
| **`scheduleAt` on a job**            | One job instance              | "Send this reminder email tomorrow morning"                 |
| **Cron** (`cron(...)` in config)     | The system                    | "Delete expired sessions every night at 03:00"              |
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

- **Actor-driven time** — construct a `Context` with the `time` you want. Business logic reading `args.time` sees it.
- **Deferred work** — see [testing.md](testing.md) and [jobs.md](jobs.md#testing). The job module's manual execution
  mode lets a test drive the scheduler directly instead of waiting.
- **Time triggers** currently require real elapsed time; this is the limitation noted above.

## Related

- [context.md](context.md) — where `time` lives and how `systemContextProvider` supplies one to background work
- [state-machines.md](state-machines.md) — `after` and `atTime`
- [jobs.md](jobs.md) — `scheduleAt`, cron, yielding, and the job testing story
- [testing.md](testing.md) — controlling time in tests

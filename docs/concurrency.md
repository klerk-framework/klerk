# Concurrency

Klerk values consistency and predictability over performance. Commands are therefore processed one at a time. If two
callers call `klerk.handle(...)` concurrently, the second call waits until the first has fully committed (or failed)
before it starts — there is no interleaving. This gives you the same guarantees as serializable isolation without having
to reason about it: business logic can assume nothing else changes the data mid-command.

Reads (`klerk.read`/`klerk.readSuspend`, see [reading.md](reading.md)) use a readers-writer lock:

- **Reads run concurrently with each other.** Any number of read blocks can be in progress at the same time.
- **A command commit excludes every read.** A command's mutations are applied to the model cache, views and job state
  while holding the lock exclusively, so a read never observes a half-committed command.
- **Writers are preferred.** Once a command is waiting to commit, no further read blocks are admitted until it is done.
  A steady stream of reads therefore cannot starve a command.

The lock is held for the whole duration of your read block, not just for a single `get`/`list` call.

Job state is part of the same snapshot, read through `jobs` on the reader:

```kotlin
klerk.read(context) {
    val order = get(orderId)
    val jobs = jobs.all()   // consistent with `order`, including the jobs that command scheduled
}
```

The [event log](events-and-commands.md#the-event-log) is part of the same snapshot, but is reached in two steps because
it lives in storage rather than in memory: `eventLog(...)` inside the block captures the snapshot, and `get()` reads the
entries afterwards, so no database query happens under the read lock.

```kotlin
val query = klerk.read(context) {
    val order = get(orderId)
    eventLog(id = orderId)   // consistent with `order` -- no later command can appear in it
}
val entries = query.get()
```

`klerk.jobs.get(...)` and `klerk.jobs.all(...)` take the read lock themselves, so they are for use *outside* a
read block and fail with an explanatory error if called inside one. Because the dispatcher has to take the write lock to
claim a job, a long read block delays job dispatch in the same way it delays a command.

## Multiple reads that must be consistent with each other

Because the lock is held for the whole block, reading several things inside one `klerk.read { }` call guarantees no
command can be processed in between:

```kotlin
val author = klerk.read(context) {
    val book = get(bookId)
    get(book.props.author) // guaranteed to still be the same book/author pair
}
```

Doing the equivalent with two separate `klerk.read` calls does not give you this guarantee — a command could be
processed between them.

Models returned from a read are snapshots, so they remain perfectly usable after the lock is released — they just may
already be stale by the time you look at them again, since another command could have been processed in the meantime.

## Read blocks must not be nested

Calling `klerk.read` (or `readSuspend`) from inside another read block is a bug and will deadlock: the inner read queues
behind any command that started waiting in the meantime, while the outer read holds the lock that command is waiting
for. Klerk detects the common cases and fails with an explanatory message instead.

Read what you need in one block rather than nesting. This also applies indirectly — a function called from inside a read
block must not itself open one.

## Keep read locks short

`readSuspend` lets you call suspending functions (e.g. a network request) while still holding the reader, but the lock
is held for as long as your block runs — including any `await`. Other reads can proceed meanwhile, but a slow suspending
call inside `readSuspend` delays every command commit until it returns. Prefer reading everything you need first, then
releasing the lock before doing slow work:

```kotlin
// Avoid: holds the lock for the duration of the HTTP call
klerk.readSuspend(context) {
    val book = get(bookId)
    httpClient.post(book.props.title.value) // blocks all commands meanwhile
}

// Prefer: read, release, then do the slow work with a possibly-stale snapshot
val book = klerk.read(context) { get(bookId) }
httpClient.post(book.props.title.value)
```

This matters most for [jobs](jobs.md), which routinely call out to external systems — don't wrap the network call itself
in a read lock.

## Code you give Klerk

Rules, selectors and callbacks declared in the specification are called by Klerk, on Klerk's threads. Where each one
runs decides what a slow one costs and what a thrown exception does.

| What you declare | Where it runs | If it throws |
|---|---|---|
| Validation rules (`validate`, `validateWithParameters`, `validateWithContext`) | On the calling coroutine, inside the command mutex — one command at a time | Propagates out of `handle`; nothing is committed |
| Command authorization rules | Same as validation rules | Propagates out of `handle`; nothing is committed |
| Read authorization rules (model, property, event log, attached data, job) | Inside the read block, under the read lock, on `Dispatchers.IO` | Propagates out of `read`/`readSuspend` |
| `ModelView.filter` predicates and `sorted` selectors | Inside the read block, under the read lock, on `Dispatchers.IO` | Propagates out of `read`/`readSuspend` |
| Block executables (`commands`, `jobs`, `job`, `transitionTo`, `update`, `createModel`, `delete`, and their `onCondition`) | On the calling coroutine, inside the command mutex | Propagates out of `handle`; nothing is committed |
| `ModelViews.didCreate`/`didUpdate`/`didDelete` | Under the write lock, **after** the commit has been written to storage | Propagates out of `handle`, leaving the in-memory cache and views inconsistent with storage |
| `unmanagedJob` | After the lock is released, in the background | Logged and swallowed |
| Admission policy | On the single writer, inside command processing | Propagates out of `handle`; nothing is committed |

Two consequences worth keeping in mind:

- **Don't throw from `didCreate`/`didUpdate`/`didDelete`.** They run after the data is already durable, so an exception
  there cannot undo the command — it only leaves memory out of step with storage until the next restart. Anything that
  can fail belongs in a validation rule, which runs before anything is written.
- **Don't do IO in any of them.** Everything above except `unmanagedJob` holds either the command mutex or the read
  lock. A database lookup or an HTTP call inside a rule makes every other command in the system wait for it. Read what
  you need from the args, or schedule a [job](jobs.md).

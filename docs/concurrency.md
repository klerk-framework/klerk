# Concurrency

Klerk processes one command at a time. If two callers call `klerk.handle(...)` concurrently, the second call waits until
the first has fully committed (or failed) before it starts — there is no interleaving. This gives you the same
guarantees as serializable isolation without having to reason about it: business logic can assume nothing else changes
the data mid-command.

Reads (`klerk.read`/`klerk.readSuspend`, see [reading.md](reading.md)) are serialized against this too — a command's
mutations are applied to the model cache and views under the same lock reads use, so a read never observes a
half-committed command. Reads themselves are cheap (everything lives in memory), so this doesn't cost you much in
practice, but it does mean **the lock is held for the whole duration of your read block**, not just for a single
`get`/`list` call.

## Multiple reads that must be consistent with each other

Because the lock is held for the whole block, reading several things inside one `klerk.read { }` call guarantees no
command can be processed in between:

```kotlin
val (book, author) = klerk.read(context) {
    val book = get(bookId)
    val author = get(book.props.author) // guaranteed to still be the same book/author pair
    book to author
}
```

Doing the equivalent with two separate `klerk.read` calls does not give you this guarantee — a command could be
processed between them.

Models returned from a read are snapshots, so they remain perfectly usable after the lock is released — they just may
already be stale by the time you look at them again, since another command could have been processed in the meantime.

## Keep read locks short

`readSuspend` lets you call suspending functions (e.g. a network request) while still holding the reader, but the lock
is held for as long as your block runs — including any `await`. A slow suspending call inside `readSuspend`
blocks every other read and every command commit until it returns. Prefer reading everything you need first, then
releasing the lock before doing slow work:

```kotlin
// Avoid: holds the lock for the duration of the HTTP call
klerk.readSuspend(context) {
    val book = get(bookId)
    httpClient.post(book.props.title.value) // blocks all other reads/writes meanwhile
}

// Prefer: read, release, then do the slow work with a possibly-stale snapshot
val book = klerk.read(context) { get(bookId) }
httpClient.post(book.props.title.value)
```

This matters most for [jobs](jobs.md), which routinely call out to external systems — don't wrap the network call itself
in a read lock.

# Performance

Klerk keeps all model data in memory (see [persistence.md](persistence.md) — the database backend is only for
durability, not for querying), so reads are typically fast without any tuning: no query planner, no round trip, no
serialization on the read path. Favor clear code over premature optimization; the places below are where it's worth
looking if you actually hit a bottleneck.

## Keep read locks short

A read (`klerk.read`/`klerk.readSuspend`) blocks command commits for as long as it runs. `readSuspend` is the usual way
this gets expensive — a slow suspending call (e.g. an HTTP request) inside the block holds up everything else meanwhile.
See [concurrency.md](concurrency.md#keep-read-locks-short) for how to structure this: read what you need, release the
lock, then do the slow work.

## Slow queries: build a custom view instead of filtering at read time

A `filter`/`sorted` view (see [views.md](views.md)) is a lazy `Sequence` evaluated fresh on every read — filtering a
large `all` this way on every request costs as much as doing the equivalent scan yourself. If a particular query pattern
is hot (e.g. a join across two model types, or a lookup you do on every request), implement `ModelView`
directly and back it with whatever data structure makes the lookup cheap — a `Map` you maintain yourself, for instance —
instead of scanning. `AuthorsWithAtLeastTwoBooks`
in [views.md](views.md#views-that-need-more-than-a-property-initializer)
is a starting point for this shape; you're free to make the `withReader` implementation as clever as the query needs.

# Performance

Klerk keeps all model data in memory by default (see [persistence.md](persistence.md) — the database backend is only
for durability, not for querying), so reads are typically fast without any tuning: no query planner, no round trip, no
serialization on the read path. If the data no longer fits, [eviction.md](eviction.md) covers keeping only part of it
resident, and what that costs. Favor clear code over premature optimization; the places below are where it's worth
looking if you actually hit a bottleneck.

## Keep read locks short

Reads run concurrently with each other, but a read (`klerk.read`/`klerk.readSuspend`) blocks command commits for as
long as it runs. `readSuspend` is the usual way this gets expensive — a slow suspending call (e.g. an HTTP request)
inside the block holds up every command meanwhile. See [concurrency.md](concurrency.md#keep-read-locks-short) for how
to structure this: read what you need, release the lock, then do the slow work.

## Slow queries: reach for `filter` first, a custom view second

A `filter`/`filterStates` view (see [views.md](views.md)) is indexed: it is evaluated once, then kept current one model
at a time as commands are processed, so querying it reads only the models it contains. That makes it the right tool for
a narrow view over a large `all`, and it is no longer something to avoid on hot paths.

Two cases still cost a full pass over the view's contents:

- **`sorted`**, which has to read every model in the view to order them.
- **A custom `ModelView`**, whose `withReader` may depend on anything and so is evaluated on every query — as is any
  view derived from one.

If a custom view is hot (e.g. a join across two model types), back it with whatever data structure makes the lookup
cheap — a `Map` you maintain yourself from the `didCreate`/`didUpdate`/`didDelete` hooks on `ModelViews`, for instance —
and answer `memberIds` from it. Because a view answers in ids, such a view reads no models at all until a caller asks
for them, and `count`/`contains`/`isEmpty` never do. `AuthorsWithAtLeastTwoBooks` in
[views.md](views.md#views-that-need-more-than-a-property-initializer) is a starting point for this shape.

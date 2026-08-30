# Querying

A query reads a [view](views.md) through a [`Reader`](reading.md). The view decides *what is in the list and
in which order*; the reader decides *how much of it you get*.

## Reading a whole view

```kotlin
val authors = klerk.read(context) { list(views.authors.all) }
```

`list` returns every model in the view. Use it when you know the view is small. `firstOrNull(view, filter)`
and `getFirstWhere(view, filter)` fetch a single model without materializing the rest.

## Reading a page

```kotlin
val page = klerk.read(context) {
    query(views.authors.all, QueryOptions(maxItems = 20))
}
page.items          // List<Model<Author>>
page.hasNextPage    // true if there is more after this page
page.cursorNextPage // pass back in QueryOptions to get it
```

`QueryResponse` carries the page and four cursors — `cursorFirstPage`, `cursorPreviousPage`, `cursorNextPage`,
`cursorLastPage`. **Each is null when there is no such page**, so a pagination control renders a link for
exactly the cursors it was given.

```kotlin
val next = page.cursorNextPage?.let {
    klerk.read(context) { query(views.authors.all, QueryOptions(maxItems = 20, cursor = it)) }
}
```

`cursorLastPage` and `totalCount` require counting the whole view, so they are populated only on request:

```kotlin
QueryOptions(maxItems = 20, countTotal = true)
```

`cursorAt(index)` gives the cursor of one item of the page rather than of the page as a whole — what a
GraphQL edge cursor needs.

## Filtering

`query` takes a filter, applied *before* the page is cut, so a page is full whenever there are enough matching
models:

```kotlin
query(views.authors.all, options) { it.props.isAlive.value }
```

A filter you reuse belongs on the view instead (`views.authors.all.filter { … }`, see [views.md](views.md)) —
a registered view can be indexed, a lambda passed to `query` cannot.

## Ordering

A query returns models in the view's own order. The `all` view is in creation order; `sorted(selector)` gives
any other. Pagination follows whatever order the view defines, including a custom `ModelView`'s.

```kotlin
val newestFirst = views.authors.all.sorted({ it.createdAt }, ascending = false).register("newestFirst")
```

## Cursors

A `QueryListCursor` is an opaque position in a view. It is URL-safe and printable, so it can go straight into
a query string:

```kotlin
val param = page.cursorNextPage?.toString()      // e.g. "bzoyMCxhOjE4NA"
val cursor = QueryListCursor.fromString(param)   // throws IllegalArgumentException if malformed
```

Do not construct one yourself, and do not read anything into its contents — the encoding is not part of the
API. `QueryListCursor.first` is the one cursor you can name: the start of the view.

`QueryOptions.direction` says where the cursor sits relative to the page:

| | |
|---|---|
| `FROM` (default) | the page starts at the cursor — what the cursors in a `QueryResponse` are for |
| `AFTER` | the page starts immediately after the cursor |
| `BEFORE` | the page ends immediately before the cursor |

`AFTER` and `BEFORE` are there for APIs whose cursors point at an item rather than at a page, such as
GraphQL's `after`/`before` arguments.

A cursor is a *position*, not a snapshot. If models are created or deleted between two page reads, the cursor
still resolves to the item it was cut at whenever that item still exists, so ordinary churn does not skip or
repeat a row. If that item is gone, the position is used as-is and a row may shift by one. A view whose order
changes wholesale between two requests gives no such guarantee.

## Authorization

| | Not authorized to read a match |
|---|---|
| `list(view)` / `query(view, options)` | throws `AuthorizationException` |
| `listIfAuthorized(view)` / `queryIfAuthorized(view, options)` | silently skips it |

`queryIfAuthorized` applies the authorization check before the page is cut, so its pages are full and its
cursors are correct. Use it when the actor is expected to see only part of a view — `query` would turn the
whole page into a 500. The `IfAuthorized` variants only work inside a `klerk.read` block; inside DSL functions
the reader does not enforce authorization, so use `list`/`query` there.

## Cost

A query walks the view from the start to the end of the page. Models are read only for the rows returned,
unless a filter or an authorization check forces earlier rows to be read too. Paging far into a large view
therefore gets linearly more expensive, and `countTotal` walks the whole view. Both are fine for the
collection sizes Klerk holds in memory, but a deep page is not free.

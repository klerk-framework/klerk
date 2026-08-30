# Reading data

## Getting a Reader

To read data managed by Klerk, you first have to get a Reader instance. This ensures that no mutation will happen while
you are reading.

### When providing functions to the Klerk configuration (DSL)

In this case, many functions have a reader in the argument which is ready to be used. As an example: if you have
provided the function updateBook to describe how a Book model should be modified on an event:

```kotlin
fun updateBook(args: ArgForInstanceEvent<Book, Nothing?, Context, MyCollections>): Book {
    val numberOfLivingAuthors = with(args.reader) {
        views.authors.living.count()
    }
    return args.model.props.copy(title = BookTitle("Biographies of all $numberOfLivingAuthors living authors"))
}
```

If there is no reader in the argument when you need it, you are probably not using the DSL correctly.

### When Klerk has started

In this case, you can get a reader from Klerk using a context:

```kotlin
val numberOfLivingAuthors = klerk.read(context) {
    views.authors.living.count()
}
```

You can also get a suspending version of the reader:

```kotlin
klerk.readSuspend(context) {
    // you can call suspending functions here and then keep reading
}
```

Note that readSuspend may impact performance if you call slow suspending functions.

## Reading a model

The reader answers questions about a single model:

* `get(id)` — the model, or throws if it does not exist.
* `getOrNull(id)` — null instead of throwing.
* `getIfAuthorizedOrNull(id)` — null when the actor may not read it.
* `getRelated(...)` / `getRelatedInCollection(...)` — the models that reference this one.

## Reading a view

Questions about a *set* of models are asked on the [view](views.md) itself. The view decides *what is in the list and in
which order*; the reader decides *how much of it you get*.

Every one of these needs a reader, but takes it as a context parameter — so inside a `klerk.read { }` block, where the
reader is the receiver, you never write it out. In a DSL function, where the reader is `args.reader`, wrap the calls in
`with(args.reader) { … }`.

```kotlin
val authors = klerk.read(context) { views.authors.all.asSequence().take(10).toList() }
```

### Pick the cheapest thing that answers the question

Klerk keeps every *id* in memory, but model bodies live in a bounded cache ([performance.md](performance.md)) — a model
that has been evicted is read back from storage. So the difference between asking about a view and reading it is real:

| you want                              | call                                                 | cost                              |
|---------------------------------------|------------------------------------------------------|-----------------------------------|
| how many, is it empty, is it in there | `count()`, `isEmpty()`, `isNotEmpty()`, `id in view` | ids only, no model read           |
| the ids                               | `ids()`                                              | ids only, lazy                    |
| the first match                       | `firstOrNull { … }`, `first { … }`                   | stops at the first match          |
| some of them                          | `asSequence()`                                       | lazy: reads only what you consume |
| one page                              | `query(QueryOptions(...))`                           | bounded                           |
| all of them                           | `asList()`                                           | unbounded                         |

```kotlin
klerk.read(context) {
    views.authors.all.count()
    views.authors.all.isEmpty()
    authorId in views.authors.all
    views.authors.all.firstOrNull { it.props.isAlive.value }
    views.authors.all.asSequence().take(10).toList()
}
```

`asList()` reads and holds every model in the view. Reach for it when you genuinely want all of them, and for one of the
rows above when you don't.

### Reading a page

```kotlin
val page = klerk.read(context) {
    views.authors.all.query(QueryOptions(maxItems = 20))
}
page.items          // List<Model<Author>>
page.hasNextPage    // true if there is more after this page
page.cursorNextPage // pass back in QueryOptions to get it
```

`QueryResponse` carries the page and four cursors — `cursorFirstPage`, `cursorPreviousPage`, `cursorNextPage`,
`cursorLastPage`. **Each is null when there is no such page**, so a pagination control renders a link for exactly the
cursors it was given.

```kotlin
val next = page.cursorNextPage?.let {
    klerk.read(context) { views.authors.all.query(QueryOptions(maxItems = 20, cursor = it)) }
}
```

`cursorLastPage` and `totalCount` require counting the whole view, so they are populated only on request:

```kotlin
QueryOptions(maxItems = 20, countTotal = true)
```

`cursorAt(index)` gives the cursor of one item of the page rather than of the page as a whole — what a GraphQL edge
cursor needs.

### Filtering

`query` and `asList` take a filter, applied *before* the page is cut, so a page is full whenever there are enough
matching models:

```kotlin
views.authors.all.query(options) { it.props.isAlive.value }
```

A filter you reuse belongs on the view instead (`views.authors.all.filter { … }`, see [views.md](views.md)) — a
registered view can be indexed, a lambda passed to `query` cannot.

### Ordering

A query returns models in the view's own order. The `all` view is in creation order; `sorted(selector)` gives any other.
Pagination follows whatever order the view defines, including a custom `ModelView`'s.

```kotlin
val newestFirst = views.authors.all.sorted({ it.createdAt }, ascending = false).register("newestFirst")
```

### Cursors

A `QueryListCursor` is an opaque position in a view. It is URL-safe and printable, so it can go straight into a query
string:

```kotlin
val param = page.cursorNextPage?.toString()      // e.g. "bzoyMCxhOjE4NA"
val cursor = QueryListCursor.fromString(param)   // throws IllegalArgumentException if malformed
```

Do not construct one yourself, and do not read anything into its contents — the encoding is not part of the API.
`QueryListCursor.first` is the one cursor you can name: the start of the view.

`QueryOptions.direction` says where the cursor sits relative to the page:

|                  |                                                                               |
|------------------|-------------------------------------------------------------------------------|
| `FROM` (default) | the page starts at the cursor — what the cursors in a `QueryResponse` are for |
| `AFTER`          | the page starts immediately after the cursor                                  |
| `BEFORE`         | the page ends immediately before the cursor                                   |

`AFTER` and `BEFORE` are there for APIs whose cursors point at an item rather than at a page, such as GraphQL's `after`/
`before` arguments.

A cursor is a *position*, not a snapshot. If models are created or deleted between two page reads, the cursor still
resolves to the item it was cut at whenever that item still exists, so ordinary churn does not skip or repeat a row. If
that item is gone, the position is used as-is and a row may shift by one. A view whose order changes wholesale between
two requests gives no such guarantee.

## Authorization

| Reading                                                         | Not authorized to read a match            |
|-----------------------------------------------------------------|-------------------------------------------|
| `get(id)`                                                       | throws `AuthorizationException`           |
| `getOrNull(id)`                                                 | returns `null` (also for a missing model) |
| `getIfAuthorizedOrNull(id)`                                     | returns `null`                            |
| `getRelated(...)` / `getRelatedInCollection(...)`               | throws `AuthorizationException`           |
| `view.asList()` / `view.query(options)`                         | throws `AuthorizationException`           |
| `view.asListIfAuthorized()` / `view.queryIfAuthorized(options)` | silently skips it                         |

Use the `IfAuthorized` variants when the actor is expected to see only part of the data (e.g. a supplier that may read
only its own rows) — the throwing variants would turn the whole page into a 500.
`queryIfAuthorized` applies the check before the page is cut, so its pages are full and its cursors are correct.

The `IfAuthorized` variants only work inside a `klerk.read` block. Inside DSL functions the reader does not enforce
authorization, so use `get`/`asList`/`query` there.

## Cost

A query walks the view from the start to the end of the page. Models are read only for the rows returned, unless a
filter or an authorization check forces earlier rows to be read too. Paging far into a large view therefore gets
linearly more expensive, and `countTotal` walks the whole view. The read lock is held for the whole block, so a large
unbounded read also holds up commands ([concurrency.md](concurrency.md)).

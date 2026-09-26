# Reading data

## Getting a Reader

To read data managed by Klerk, you first have to get a Reader instance. This ensures that no mutation will happen while
you are reading.

### When providing functions to the Klerk configuration (DSL)

In this case, many functions have a reader in the argument which is ready to be used. As an example: if you have
provided the function updateBook to describe how a Book model should be modified on an event:

```kotlin
fun updateBook(args: InstanceEventArgs<Book, Nothing?, Ctx, Views>): Book {
    val numberOfLivingAuthors = with(args.reader) {
        views.authors.living.count()
    }
    return args.model.props.copy(title = BookTitle("Biographies of all $numberOfLivingAuthors living authors"))
}
```

If there is no reader in the argument when you need it, you are probably not using the DSL correctly.

The reader in `args` is a `ModelReader`: models, relations, views, jobs, attached-data metadata and the event log. A
`klerk.read { }` block gets the larger `Reader`, which adds `possibleEvents(id)` and `possibleVoidEvents(clazz)`
— those need an actor to answer for, so they only exist where authorization is enforced. `isGenerallyPossible(eventRef)`
answers a narrower question with no model at all: whether a `generalCommands` rule (see
[authorization](authorization.md)) denies the event outright, independent of any instance.

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

* `get(id)` — the model. Throws `NoSuchElementException` if it does not exist, `AuthorizationException` if the actor
  may not read it.
* `getOrNull(id)` — null in both those cases.
* `referencing(...)` / `referencingInCollection(...)` — the models that reference this one.
* `attachedData.getMetadata(id)` / `metadataOrNull(id)` — what is known about an
  [attached value](attached-data.md) apart from the value itself. The value is read after the block.

## Returning several values from one read block

The read lock is held for the whole block, so everything a request needs should be read in **one** `klerk.read { }`
call — separate calls let a command commit in between and hand you an inconsistent mix
([concurrency.md](concurrency.md)). The block returns its last expression, so bundle the values.

Two values — `Pair`:

```kotlin
val (author, bookCount) = klerk.read(context) {
    Pair(get(authorId), views.books.all.count())
}
```

Three — `Triple`:

```kotlin
val (author, book, editor) = klerk.read(context) {
    val author = get(authorId)
    Triple(author, get(bookId), get(author.props.editor))
}
```

More than three — a local `data class`, so the fields stay named on the way out:

```kotlin
val page = klerk.read(context) {
    val author = get(authorId)

    data class AuthorPage(
        val author: Model<Author>,
        val books: List<Model<Book>>,
        val editor: Model<User>,
        val livingPeers: Int,
    )
    AuthorPage(
        author = author,
        books = views.books.all.asSequence().filter { it.props.author == author.id }.toList(),
        editor = get(author.props.editor),
        livingPeers = views.authors.living.count(),
    )
}
```

Filter on the sequence *inside* the block, not with `.toList().filter { … }` after it — see
[Pick the cheapest thing](#pick-the-cheapest-thing-that-answers-the-question) below.

## Reading a view

Questions about a *set* of models are asked on the [view](views.md) itself. The view decides *what is in the list and in
which order*; the reader decides *how much of it you get*.

Every one of these needs a reader, but takes it as a context parameter — so inside a `klerk.read { }` block, where the
reader is the receiver, you never write it out. In a DSL function, where the reader is `args.reader`, wrap the calls in
`with(args.reader) { … }`.

They are extension functions, so import the ones you use:

```kotlin
import dev.klerkframework.klerk.view.asSequence
import dev.klerkframework.klerk.view.count
import dev.klerkframework.klerk.view.query
```

```kotlin
val livingAuthors = klerk.read(context) {
    views.authors.all.asSequence()
        .filter { it.props.isAlive.value }
        .take(10)
        .toList()
}
```

### Pick the cheapest thing that answers the question

Klerk keeps every *id* in memory, but model bodies live in a bounded cache ([performance.md](performance.md)) — a model
that has been evicted is read back from storage. So the difference between asking about a view and reading it is real:

| you want                              | call                                                 | cost                              |
|---------------------------------------|------------------------------------------------------|-----------------------------------|
| how many, is it empty, is it in there | `count()`, `isEmpty()`, `isNotEmpty()`, `id in view` | ids only, no model read *         |
| the ids                               | `ids()`                                              | ids only, lazy *                  |
| some of them, or the first match      | `asSequence()`                                       | lazy: reads only what you consume |
| one page                              | `query(QueryOptions(...))`                           | bounded                           |
| all of them                           | `asSequence().toList()`                              | unbounded                         |

\* For the system. For any other actor these only count the models the actor may read, so each model in the view is
read to evaluate the `readModels` rules.

All of these skip models the actor may not read; see [Authorization](#authorization) below.

```kotlin
klerk.read(context) {
    views.authors.all.count()
    views.authors.all.isEmpty()
    authorId in views.authors.all
    views.authors.all.asSequence().firstOrNull { it.props.isAlive.value }
    views.authors.all.asSequence().take(10).toList()
}
```

There is deliberately no `asList()`, `first()` or `firstOrNull()` on a view. `asSequence()` is lazy, so `first`,
`firstOrNull`, `take`, `any` and `count` are the stdlib operators right there on the sequence; `asSequence().toList()`
reads and holds every model in the view, so spelling it out keeps the cost visible.

### Reading a page

```kotlin
val page = klerk.read(context) {
    views.authors.all.query(QueryOptions(maxItems = 20))
}
page.items          // List<Model<Author>>
page.hasNextPage    // true if there is more after this page
page.cursorNextPage // pass back in QueryOptions to get it
```

`maxItems` is not capped. If the page size comes from a client, cap it before passing it on; klerk-graphql allows at
most 100.

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

`query` takes a filter, applied *before* the page is cut, so a page is full whenever there are enough matching models:

```kotlin
views.authors.all.query(options) { it.props.isAlive.value }
```

The filter only sees models the actor may read, with the properties it may not read masked, so it cannot be used to
probe hidden data. `queryOrThrow` throws as soon as it comes across a model the actor may not read, whether or not the
filter would have matched it.

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
val cursor = QueryListCursor.parse(param)   // throws IllegalArgumentException if malformed
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

| Reading                                                   | Not authorized to read a match            |
|-----------------------------------------------------------|-------------------------------------------|
| `get(id)`                                                 | throws `AuthorizationException`           |
| `getOrNull(id)`                                           | returns `null` (also for a missing model) |
| `referencing(...)` / `referencingInCollection(...)`         | silently skips it                         |
| `attachedData.getMetadata(id)`                               | throws `AuthorizationException`           |
| `attachedData.getMetadataOrNull(id)`                         | returns `null` (also for missing data)    |
| `view.asSequence()` / `view.query(options)`               | silently skips it                         |
| `view.count()` / `isEmpty()` / `ids()` / `id in view`     | silently skips it                         |
| `view.asSequenceOrThrow()` / `view.queryOrThrow(options)` | throws `AuthorizationException`           |
| `modelChanges.subscribe(id, context)`                     | silently skips it; deletions are always sent |

`asSequence()` / `query()` skip unreadable matches, which is what a list rendered for an actor that only sees part of
the data needs (e.g. a supplier that may read only its own rows) — a throw would turn the whole page into a 500. The
check is applied before the page is cut, so pages stay full and cursors stay correct.

Use the `OrThrow` variants when you expect every match to be readable and a hidden row means the authorization rules are
wrong (e.g. an admin-only all-users list), so you want a loud failure rather than a silently short page.

All four work in any read context. Inside a DSL function the reader does not enforce authorization, so there they all
return everything.

## Cost

A query walks the view from the start to the end of the page. Models are read only for the rows returned, unless a
filter or an authorization check forces earlier rows to be read too. Paging far into a large view therefore gets
linearly more expensive, and `countTotal` walks the whole view. The read lock is held for the whole block, so a large
unbounded read also holds up commands ([concurrency.md](concurrency.md)).

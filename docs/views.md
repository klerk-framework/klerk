# Views

Instead of writing queries against a database, you declare **views**: named, typed lists of model instances that Klerk
keeps up to date as models are created, updated, and deleted. A view is where you put anything that would otherwise be
an index or a `WHERE` clause.

This page covers how views are *declared*. For how to actually read them, see [reading.md](reading.md).

## The views object

Every managed model type gets its own view class, a subclass of `ModelViews<T, C>`:

```kotlin
class BookViews : ModelViews<Book, Ctx>()
```

All of an application's view classes are grouped into one top-level data class. This class is the `V` type parameter you
see everywhere (`Klerk<C, V>`, `StateMachine<T, S, C, V>`, ...), and it's what you pass into
`SpecificationBuilder` and into your state machine builder functions:

```kotlin
data class Views(
    val books: BookViews,
    val authors: AuthorViews<Views>,
)

SpecificationBuilder<Ctx, Views>(views).build {
    managedModels {
        model(Book::class, bookStateMachine(collections), collections.books)
        model(Author::class, authorStateMachine(collections), collections.authors)
    }
    // ...
}
```

## `all`, and deriving views with `filter`

`ModelViews<T, C>` gives every model type a view called `all` for free — an `AllModelView<T, C>` containing every
instance of that type, ordered by creation time. Everything else is built from it:

```kotlin
class AuthorViews<V>(val allBooks: AllModelView<Book, Ctx>) : ModelViews<Author, Ctx>() {

    private val greatAuthorNames = setOf("Astrid", "Elsa")

    val greatAuthors = this.all
        .filter { greatAuthorNames.contains(it.props.firstName.value) }
        .register("greatAuthors")

    val establishedAuthors = this.all
        .filter { it.state == Established.name }
        .register("establishedAuthors")

    val establishedGreatAuthors = greatAuthors
        .filter { it.state == Established.name }
        .register("establishedGreatAuthors")
}
```

`filter` takes a predicate on `Model<T>` (so you can filter on `props`, `state`, `createdAt`, etc.) and returns a new
`ModelView` wrapping the previous one — views compose, as `establishedGreatAuthors` above shows by filtering an
already-filtered view. `ModelView` also has `sorted { selector }` and `filterStates(included, excluded)` for the same
purpose.

The predicate must be a pure function of the model it is given. Klerk evaluates it when a model changes and remembers
the answer (see [How views are kept](#how-views-are-kept)); a predicate that consults anything else — a mutable
variable, the clock, another model — will produce a view that silently stops matching what you asked for. Capturing an
immutable value, as `greatAuthorNames` does above, is fine. If membership genuinely depends on something else, write a
custom `ModelView` instead: those are evaluated on every query.

Every view you want to expose must be registered with `.register("someId")`. Registering does two things: it gives the
view a stable string id, combined with the owning model's class name into a `CollectionId(modelName, shortId)`
(rendered as `c.Author.establishedAuthors`); and it adds the view to `Specification.getCollections()`, which is how
Klerk knows the view exists at all. That `CollectionId` is what `Specification.getCollection(id)` uses to look a view up
by id, and it's what shows up in the error message when a `validReferences` check rejects a command (`"Did not find 42 in
c.Author.all for parameter favouriteColleague"`). An unregistered `filter`/`sorted` result still works if you hold a
reference to it, but it won't show up in `Specification.getCollections()` and can't be looked up by id. Ids may not
contain `.` or spaces.

## Views that need more than a property initializer

Some views can't be expressed as a one-line `filter` — e.g. a view that joins across two managed models. For these,
implement `ModelView<T, C>` directly. The one method you must write is `memberIds`: the ids in the view, in order.
Return all of them — a view defines order and membership only, and `query` does the paging.

```kotlin
class AuthorsWithAtLeastTwoBooks<V>(
    private val authors: ModelView<Author, Ctx>,
    private val books: AllModelView<Book, Ctx>,
) : ModelView<Author, Ctx>(authors) {

    override fun <V> memberIds(reader: ModelReader<Ctx, V>): Sequence<ModelID<Author>> {
        val withTwoBooks = books.withReader(reader)
            .groupingBy { it.props.author }
            .eachCount()
            .filterValues { it >= 2 }
            .keys
        return authors.memberIds(reader).filter { withTwoBooks.contains(it) }
    }
}
```

**Answer in ids, not models.** Klerk turns ids into models only for the ones a caller actually asks for, so a view that
returns ids never pays for reading models it excludes — and `count`, `contains` and `isEmpty` are then answered without
reading any model at all. `withReader` is derived from `memberIds` and is not overridable.

The pattern that makes a custom view genuinely cheap is to maintain your own lookup structure from the
`didCreate`/`didUpdate`/`didDelete` hooks on the `ModelViews` of whatever the view depends on, and answer from it:

```kotlin
class BookViews : ModelViews<Book, Ctx>() {
    val booksPerAuthor = mutableMapOf<ModelID<Author>, Int>()

    override fun didCreate(created: Model<Book>) {
        booksPerAuthor.merge(created.props.author, 1, Int::plus)
    }

    override fun didDelete(deleted: Model<Book>) {
        booksPerAuthor.merge(deleted.props.author, -1, Int::plus)
    }
}
```

`memberIds` then becomes `authors.memberIds(reader).filter { (booksPerAuthor[it] ?: 0) >= 2 }`, which reads no
`Book` at all. Override `contains` too when you can answer it directly — `validReferences` asks it once per command, so
it is on the write path rather than the read path.

If building such a view requires a reference to *another* model's `ModelViews` instance (as `AuthorsWithAtLeastTwoBooks`
needs `books`, the `Book` view), you generally can't wire it up in a property initializer, because the views for
different models may be constructed in an order you don't control. Use the `initialize()` hook instead — it's called
once all managed models (and therefore all `ModelViews` instances) exist:

```kotlin
class AuthorViews<V>(val allBooks: AllModelView<Book, Ctx>) : ModelViews<Author, Ctx>() {

    lateinit var establishedGreatWithAtLeastTwoBooks: AuthorsWithAtLeastTwoBooks<V>

    override fun initialize() {
        establishedGreatWithAtLeastTwoBooks = AuthorsWithAtLeastTwoBooks(all, allBooks)
        establishedGreatWithAtLeastTwoBooks.register("medMinst2Böcker")
    }
}
```

Note that in this particular example `allBooks` (the `Book` view's `all`) is passed in through the constructor instead —
either approach works; use `initialize()` when the dependency isn't available yet at construction time.

## Views stay live

Views aren't snapshots — they reflect the current model set. When a model is deleted, it disappears from every view
built on top of `all` immediately:

```kotlin
val astrid = createAuthorAstrid(klerk)
klerk.read(Ctx.system()) {
    assertTrue { astrid in collections.authors.all }
}

klerk.handle(Command(DeleteAuthor, astrid, null), Ctx.system())

klerk.read(Ctx.system()) {
    assertFalse { astrid in collections.authors.all }
}
```

## How views are kept

A view built from `filter`, `filterStates` and `sorted` keeps an index of the ids it contains. It is built the first
time the view is queried, and after that each command updates it for just the models that command touched. Querying such
a view therefore reads only the models the view actually contains — a narrow view over a large `all` costs the same
whether `all` holds a hundred models or a million.

Consequences worth knowing:

- **`count`, `contains` and `isEmpty` read no models at all.** They are answered from the index.
- **A view is indexed only if every view it is derived from is.** A custom `ModelView` may depend on anything, so it is
  evaluated on every query, and so is anything derived from it. A custom view can still be cheap — see
  [above](#views-that-need-more-than-a-property-initializer) — but you maintain that yourself.
- **`sorted` keeps no index of its own** — sorting doesn't change what a view contains — but it must read every model in
  it to order them, so it is the one built-in that always reads its whole contents.
- **Views declared after Klerk has started are not indexed.** They behave as they always did, evaluated on each query.
  This is what keeps a `filter` written inside a read block from being maintained, and retained, forever. Declare views
  as `val`s on your `ModelViews` class and this never comes up.
- **Registering is unrelated to indexing.** `register` gives a view a stable id; an unregistered view held in a `val`
  is indexed just the same.

## Querying a view

Views are read through a `Reader`, e.g. inside `klerk.read`:

```kotlin
val greatEstablishedAuthors = klerk.read(context) {
    views.authors.establishedGreatAuthors.asSequence().toList()
}
```

See [reading.md](reading.md) for how to read a view: `count`, `asSequence`, `query`, filtering and pagination.

## Using a view as a reference constraint

Any parameter of type `ModelID<Author>` in an event's parameters class must declare which view the referenced id is
allowed to come from, using `validReferences` in the state machine DSL:

```kotlin
event(CreateBook) {
    validReferences(CreateBookParams::author, collections.authors.all)
}
```

This ensures a `CreateBook` command can't reference an `Author` id that doesn't exist (or, if you point it at a narrower
view than `all`, one that doesn't satisfy that view's criteria). See [validation.md](validation.md) for the full
validation pipeline.

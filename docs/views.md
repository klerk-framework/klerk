# Views

Instead of writing queries against a database, you declare **views**: named, typed lists of model instances that
Klerk keeps up to date as models are created, updated, and deleted. A view is where you put anything that would
otherwise be an index or a `WHERE` clause.

This page covers how views are *declared*. For how to actually query them, see [reading.md](reading.md).

## The views object

Every managed model type gets its own view class, a subclass of `ModelViews<T, C>`:

```kotlin
class BookViews : ModelViews<Book, Context>()
```

All of an application's view classes are grouped into one top-level data class. This class is the `V` type
parameter you see everywhere (`Klerk<C, V>`, `StateMachine<T, S, C, V>`, ...), and it's what you pass into
`SpecificationBuilder` and into your state machine builder functions:

```kotlin
data class MyCollections(
    val books: BookViews,
    val authors: AuthorViews<MyCollections>,
)

SpecificationBuilder<Context, MyCollections>(collections).build {
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
class AuthorViews<V>(val allBooks: AllModelView<Book, Context>) : ModelViews<Author, Context>() {

    private val greatAuthorNames = setOf("Linus", "Bertil")

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
already-filtered view. `ModelView` also has `sorted { selector }` and `filterStates(included, excluded)` for the
same purpose.

Every view you want to expose must be registered with `.register("someId")`. Registering does two things: it gives
the view a stable string id, combined with the owning model's class name into a `CollectionId(modelName, shortId)`
(rendered as `c.Author.establishedAuthors`); and it adds the view to `Specification.getCollections()`, which is how Klerk
knows the view exists at all. That `CollectionId` is what `Specification.getCollection(id)` uses to look a view up by id,
and it's what shows up in the error message when a `validReferences` check rejects a command (`"Did not find 42 in
c.Author.all for parameter favouriteColleague"`). An unregistered `filter`/`sorted` result still works if
you hold a reference to it, but it won't show up in `Specification.getCollections()` and can't be looked up by id. Ids may
not contain `.` or spaces.

## Views that need more than a property initializer

Some views can't be expressed as a one-line `filter` — e.g. a view that joins across two managed models. For these,
implement `ModelView<T, C>` directly:

```kotlin
class AuthorsWithAtLeastTwoBooks<V>(
    private val authors: ModelView<Author, Context>,
    private val books: AllModelView<Book, Context>,
) : ModelView<Author, Context>(authors) {

    override fun <V> withReader(reader: Reader<Context, V>, cursor: QueryListCursor?): Sequence<Model<Author>> {
        return authors.withReader(reader, cursor).filter { author ->
            books.withReader(reader, null).filter { it.props.author == author.id }.take(2).count() == 2
        }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<Context, V>): Boolean =
        withReader(reader, null).any { it.id == value }
}
```

If building such a view requires a reference to *another* model's `ModelViews` instance (as `AuthorsWithAtLeastTwoBooks`
needs `books`, the `Book` view), you generally can't wire it up in a property initializer, because the views for
different models may be constructed in an order you don't control. Use the `initialize()` hook instead — it's called
once all managed models (and therefore all `ModelViews` instances) exist:

```kotlin
class AuthorViews<V>(val allBooks: AllModelView<Book, Context>) : ModelViews<Author, Context>() {

    lateinit var establishedGreatWithAtLeastTwoBooks: AuthorsWithAtLeastTwoBooks<V>

    override fun initialize() {
        establishedGreatWithAtLeastTwoBooks = AuthorsWithAtLeastTwoBooks(all, allBooks)
        establishedGreatWithAtLeastTwoBooks.register("medMinst2Böcker")
    }
}
```

Note that in this particular example `allBooks` (the `Book` view's `all`) is passed in through the constructor
instead — either approach works; use `initialize()` when the dependency isn't available yet at construction time.

## Views stay live

Views aren't snapshots — they reflect the current model set. When a model is deleted, it disappears from every view
built on top of `all` immediately:

```kotlin
val astrid = createAuthorAstrid(klerk)
klerk.read(Context.system()) {
    assertTrue { collections.authors.all.contains(astrid, this) }
}

klerk.handle(Command(DeleteAuthor, astrid, null), Context.system(), ProcessingOptions(CommandToken.simple()))

klerk.read(Context.system()) {
    assertFalse { collections.authors.all.contains(astrid, this) }
}
```

## Querying a view

Views are read through a `Reader`, e.g. inside `klerk.read`:

```kotlin
val greatEstablishedAuthors = klerk.read(context) {
    list(views.authors.establishedGreatAuthors)
}
```

See [reading.md](reading.md) for the full set of `Reader` operations (`list`, `get`, `firstOrNull`, pagination via
`QueryOptions`/`QueryListCursor`, etc.).

## Using a view as a reference constraint

Any parameter of type `ModelID<Author>` in an event's parameters class must declare which view the referenced id is
allowed to come from, using `validReferences` in the state machine DSL:

```kotlin
event(CreateBook) {
    validReferences(CreateBookParams::author, collections.authors.all)
}
```

This ensures a `CreateBook` command can't reference an `Author` id that doesn't exist (or, if you point it at a
narrower view than `all`, one that doesn't satisfy that view's criteria). See [validation.md](validation.md) for the
full validation pipeline.

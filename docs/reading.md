# Reading data

## Getting a Reader

To read data managed by Klerk, you first have to get a Reader instance.

### When providing functions to the Klerk configuration (DSL)

In this case, many functions has a reader in the argument which is ready to be used. As an example: if you have provided
the function updateBook to describe how a Book model should be modified on an event:

```kotlin
fun updateBook(args: ArgForInstanceEvent<Book, Nothing?, Context, MyCollections>): Book {
    val numberOfLivingAuthors = with(args.reader) {
        list(views.authors.all)
            .filter { it.props.isAlive.value }
            .size
    }
    return args.model.props.copy(title = BookTitle("Biographies of all $numberOfLivingAuthors living authors"))
}
```

If there is no reader in the argument, you are probably not using the DSL correctly.

### When Klerk has started

In this case, you can get a reader from Klerk using a context:

```kotlin
val numberOfLivingAuthors = klerk.read(context) {
    list(views.authors.all)
        .filter { it.props.isAlive.value }
        .size
}
```

You can also get a suspending version of the reader:

```kotlin
klerk.readSuspend(context) {
    // you can call suspending functions here and then keep reading
}
```

Note that readSuspend may impact performance if you call slow suspending functions.

## Reader methods

| Method | Not authorized to read a match |
|---|---|
| `get(id)` / `list(view)` / `query(view, options)` | throws `AuthorizationException` |
| `getOrNull(id)` | returns `null` (also for a missing model) |
| `getIfAuthorizedOrNull(id)` / `listIfAuthorized(view)` / `queryIfAuthorized(view, options)` | silently skips it |

Use the `IfAuthorized` variants when the actor is expected to see only part of a collection (e.g. a supplier that
may read only its own rows) — `list`/`query` would turn the whole page into a 500. They only work inside a
`klerk.read` block; inside DSL functions the reader does not enforce authorization, so use `get`/`list` there.

See [querying.md](querying.md) for reading a page at a time, filtering and cursors.


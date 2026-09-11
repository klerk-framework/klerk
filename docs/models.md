# Models

A model is a plain, immutable Kotlin `data class` describing the shape of one kind of thing in your system, e.g.:

```kotlin
data class Book(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>>,
    val tags: Set<BookTag>,
    val averageScore: AverageScore,
    val writtenAt: BookWrittenAt,
    val publishedAt: BookWrittenAt?,
)
```

You register it as a *managed model* in the specification, together with its [state machine](state-machines.md) and its
[views](views.md):

```kotlin
managedModels {
    model(Book::class, bookStateMachine(collections), collections.books)
}
```

Klerk wraps every instance in a `Model<T>` that adds bookkeeping Klerk itself is responsible for — you never construct
this yourself:

```kotlin
data class Model<T : Any>(
    val id: ModelID<T>,
    val createdAt: Instant,
    val lastPropsUpdateAt: Instant,
    val lastStateTransitionAt: Instant,
    val state: String,
    val timeTrigger: Instant?,
    val props: T,
)
```

`props` is your data class. `state` is the current state name from its [state machine](state-machines.md). `id` is a
`ModelID<T>` — a typed, `Int`-backed reference to the model, safe to hold onto and pass around (e.g. `Book::author:
ModelID<Author>` above is how one model refers to another).

## Rules for model classes

`SpecificationBuilder` validates these at startup and throws `IllegalArgumentException`/`IllegalConfigurationException`
if violated:

* The class must be a `data class`.
* All properties must be `val`, never `var`.
* Every property must be a `DataContainer` (see below), or a collection (`List`, `Set`, ...) of one, or a
  `ModelID<...>`, or another plain data class composed the same way (e.g. `Address(val street: Street)`).
* Every property must be a public `val`, and the primary constructor must be public. This also applies to nested
  classes and to event parameters classes, since Klerk reads, stores and copies them. A class that breaks a rule is
  rejected when Klerk starts.

You cannot put a raw `String`, `Int`, etc. directly on a model — everything goes through a `DataContainer`.

## DataContainer

Every property that isn't a reference to another model is wrapped in a `DataContainer<T>` subclass. This exists for
several reasons at once:

1. It's where per-property validation rules live.
2. Authorization rules for reading individual properties are enforced when the value is accessed (see below).
3. Distinct types prevent mixing up e.g. a `Username` and a `Password` even though both wrap a `String`.
4. You can attach behavior — units, formatting, parsing — to a specific kind of value.

You subclass one of the built-in containers and fill in its constraints:

```kotlin
class BookTitle(value: String) : StringContainer(value) {
    override val minLength = 2
    override val maxLength = 100
    override val maxLines = 1
}

class AverageScore(value: Float) : FloatContainer(value) {
    override val min = 0f
    override val max = Float.MAX_VALUE
}

enum class BookGenre { Fiction, Mystery, Fantasy }
class BookGenreContainer(value: BookGenre) : EnumContainer<BookGenre>(value)
```

Built-in containers:

| Container              | Wraps                   | Built-in constraints                                                                                                 |
|------------------------|-------------------------|----------------------------------------------------------------------------------------------------------------------|
| `StringContainer`      | `String`                | `minLength`, `maxLength`, `maxLines`, optional `regexPattern`                                                        |
| `IntContainer`         | `Int`                   | `min`, `max`                                                                                                         |
| `LongContainer`        | `Long`                  | `min`, `max`                                                                                                         |
| `ShortContainer`       | `Short`                 | `min`, `max`                                                                                                         |
| `ByteContainer`        | `Byte`                  | `min`, `max`                                                                                                         |
| `UIntContainer`        | `UInt`                  | `min`, `max`                                                                                                         |
| `ULongContainer`       | `ULong`                 | `min`, `max`                                                                                                         |
| `UShortContainer`      | `UShort`                | `min`, `max`                                                                                                         |
| `UByteContainer`       | `UByte`                 | `min`, `max`                                                                                                         |
| `FloatContainer`       | `Float`                 | `min`, `max`                                                                                                         |
| `DoubleContainer`      | `Double`                | `min`, `max`                                                                                                         |
| `BooleanContainer`     | `Boolean`               | none                                                                                                                 |
| `EnumContainer<E>`     | an `Enum`               | none (use `validEnums` in the state machine to restrict which values are accepted — see [validation](validation.md)) |
| `InstantContainer`     | `kotlin.time.Instant`   | none (microsecond resolution)                                                                                        |
| `DateContainer`        | `java.time.LocalDate`   | none — a calendar date with no time of day or time zone                                                              |
| `DurationContainer`    | `kotlin.time.Duration`  | none (microsecond resolution)                                                                                        |
| `GeoPositionContainer` | `GeoPosition` (lat/lon) | validated by `GeoPosition` itself; serializes as ISO 6709                                                            |

On top of the built-in constraints, add custom rules via `validators`:

```kotlin
class PositiveEvenIntContainer(value: Int) : IntContainer(value) {
    override val min = 2
    override val max = Int.MAX_VALUE
    override val validators = setOf(::mustBeEven)

    fun mustBeEven(t: Translation): PropertyValidation {
        return if (valueWithoutAuthorization % 2 == 0) PropertyValidation.Valid else PropertyValidation.Invalid()
    }
}
```

See [validation](validation.md) for how all of this fits into the full validation pipeline, and how to add model-wide
validation that spans several properties (`Validatable`).

### Reading a container's value

Different ways to read the wrapped value:

* `toString()` on a container renders the value with a sensible formatting and with the masked placeholder `[••••••]`
  instead of the value if the actor isn't authorized to read it. This is the recommended way to present the value in a
  UI. If you want to render the value in a different format, you can override `toString()` on the container.
* `value` — throws `AuthorizationException` if the current actor is not authorized to read this property (per your
  [authorization](authorization.md) rules).
* `valueWithoutAuthorization` — always available, bypassing authorization. Business logic inside the framework (e.g. a
  container's own validators, which run before authorization is even relevant) uses this. Application code normally
  should not.
* `valueOrNullIfNotAuthorized` — like `value`, but returns `null` instead of throwing when unauthorized.

Containers that never went through an authorizing read — the ones you construct yourself, and the ones the framework
uses internally — always allow reading, which is why validators and view filters can use `.value` without thinking about
actors.

## ModelID

```kotlin
@JvmInline
value class ModelID<T : Any>(val value: Int)
```

A lightweight, type-safe pointer to a model. Use it as a property type whenever one model refers to another (e.g.
`Book.author: ModelID<Author>`), and as the identifier you pass to `Reader.get(id)` (see [reading](reading.md)) or into
a `Command` (see [events and commands](events-and-commands.md)).

## Validatable

Rules that need to see more than one property at once — of either a model or an event's parameters class — go on
`Validatable`:

```kotlin
data class Author(val firstName: FirstName, val lastName: LastName) : Validatable {
    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf(::noAuthorCanBeNamedJamesClavell)

    private fun noAuthorCanBeNamedJamesClavell(): PropertyCollectionValidity {
        return if (firstName.value == "James" && lastName.value == "Clavell") Invalid() else Valid
    }
}
```

Full details, including how this interacts with per-container validators and event-level rules, are in
[validation](validation.md).

## Appearance

Klerk typically doesn't deal with what the model looks like in the UI, but you still may want to override toString on
the model data class. This can be used by e.g. klerk-web to display a nice human-readable representation of the model in
e.g. lists. It is recommended to pick one or two of the properties that best represent the model. If the model has a
property "name", "title" or "label" is a good choice. E.g.

```kotlin
data class Book(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>>
) {
    override fun toString() = title.toString()
}
```

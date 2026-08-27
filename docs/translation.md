# Translation

Every user-facing string Klerk produces — validation messages, property names, event names — is generated through a
`Translation`, so your application can support multiple languages without your business logic ever building strings
itself.

```kotlin
interface KlerkContext {
    val translation: Translation
    // ... actor, time, eventLogExtra
}

interface Translation {
    val klerk: KlerkTranslation
}
```

`Translation` is carried on the [`Ctx`](context.md), so which language is used is decided per-request, the same way the
actor is.

## KlerkTranslation

`KlerkTranslation` is where the actual strings live:

```kotlin
interface KlerkTranslation {
    fun property(property: KProperty1<*, *>): String
    fun propertyDescription(property: String): String?
    fun event(event: EventReference): String
    fun function(f: Function<Any>): String
    fun mustBeAtLeast(value: Number): String
    fun mustBeAtMost(value: Number): String
    fun invalidProperty(propertyName: String, functionName: String, translationInfo: String?): String
    val mustBeProvided: String
    fun tooShort(minLength: Int): String
    fun tooLong(maxLength: Int): String
    fun tooManyLines(maxLines: Int): String
    val invalid: String
    val internalError: String
    val unauthorized: String
    val noAllowingRule: String
}
```

These are exactly the messages produced by the [validation](validation.md) and [authorization](authorization.md)
pipelines — e.g. `tooShort`/`tooLong` come from a `StringContainer`'s length check, `mustBeAtLeast`/`mustBeAtMost`
from a numeric container's range check, and `unauthorized`/`noAllowingRule` from failed authorization rules.
`function(f)` supplies the fallback message for a failed `Validatable`/`PropertyCollectionValidity` rule when it didn't
provide its own `endUserTranslatedMessage` — the default implementation prettifies the validator function's name (e.g.
`mustBeEven` → "Must be even").

## DefaultTranslation

```kotlin
object DefaultTranslation : Translation {
    override val klerk: KlerkTranslation = DefaultKlerkTranslation
}
```

`DefaultKlerkTranslation` implements every message in English by prettifying identifiers (`camelCaseToPretty`) — e.g. a
property `firstName` becomes "First name".

## Adding a language

Implement `KlerkTranslation`, delegating to a default for anything you don't override — you only need to supply the
strings that differ:

```kotlin
class SwedishKlerkTranslation(val default: KlerkTranslation) : KlerkTranslation by default {

    override fun property(property: KProperty1<*, *>): String = when (property) {
        CreateAuthorParams::firstName -> "Förnamn på den nya författaren"
        else -> default.property(property)
    }

    override fun event(event: EventReference): String = when (event) {
        CreateAuthor.id -> "Ny författare"
        else -> default.event(event)
    }

    override fun mustBeAtLeast(value: Number) = "Måste vara minst $value"
}

object SwedishTranslation : Translation {
    override val klerk: KlerkTranslation = SwedishKlerkTranslation(EnglishKlerkTranslation(DefaultKlerkTranslation))
}
```

Note the `when` matches on specific `KProperty1`/`EventReference`/function references, falling through to
`default` for everything else — this is how you translate only the handful of properties and events that need a special
label, without maintaining a translation for every single field in the system.

## Wiring it up

Since `translation` lives on your `Ctx`, switching language is just constructing the context differently — typically
based on the request's locale, or a user's saved preference:

```kotlin
fun contextForRequest(actor: ActorIdentity, locale: String): Context =
    Context(actor, translation = if (locale == "sv") SwedishTranslation else DefaultTranslation)
```

See [testing](testing.md) for asserting on translated output (`Problem.endUserTranslatedMessage`) rather than on the
underlying rule that produced it.

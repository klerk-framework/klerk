# Translation

Every user-facing string Klerk produces — validation messages, property names, event names — is generated through a
`HasKlerkTranslation`, so your application can support multiple languages without your business logic ever building
strings
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

`HasKlerkTranslation` is carried on the [`Ctx`](context.md), so which language is used is decided per-request, the same
way the
actor is.

## KlerkTranslation

`KlerkTranslation` is where the actual strings live:

```kotlin
interface KlerkTranslation {
    fun property(property: KProperty1<*, *>): String
    fun propertyDescription(property: KProperty1<*, *>): String?
    fun event(event: EventReference): String
    fun modelName(kClass: KClass<*>): String
    fun enumValue(value: Enum<*>): String
    fun function(f: Function<Any>): String
    fun mustBeAtLeast(value: Number): String
    fun mustBeAtMost(value: Number): String
    fun invalidProperty(propertyName: String, functionName: String, translationInfo: String?): String
    fun invalidPropertyCollection(functionName: String, translationInfo: String?): String
    fun preventedByRule(functionName: String, translationInfo: String?): String
    fun tooShort(minLength: Int): String
    fun tooLong(maxLength: Int): String
    fun tooManyLines(maxLines: Int): String
    fun text(key: KlerkText): String
}
```

`languageTag` is the BCP 47 tag of the language, e.g. `"sv"`. klerk-web uses it as the `lang` of its pages. It is `"en"` unless you override it.

`modelName` is the name of a model (given its props class), e.g. in a page title, and `enumValue` the name of an enum
value, e.g. in a drop-down list.

`text` returns one of the fixed texts in the enum `KlerkText`, e.g. `KlerkText.Unauthorized` or
`KlerkText.MustBeProvided`. `KlerkText.Id`, `State`, `Created`, `Updated` and `Properties` are the names of a model's
ID, state, creation time, last modification time and property summary; klerk-web uses them as column headers. `text`
returns the English text unless you override it:

```kotlin
override fun text(key: KlerkText): String = when (key) {
    KlerkText.Unauthorized -> "Obehörig"
    else -> DefaultKlerkTranslation.text(key)
}
```

`untranslatedTexts()` lists the `KlerkText` that a translation returns in English (also those that are the same in both
languages, e.g. "ID"), e.g. for a test:

```kotlin
assertEquals(emptyList(), SwedishTranslation.klerk.untranslatedTexts())
```

The other strings are exactly the messages produced by the [validation](validation.md) and
[authorization](authorization.md) pipelines — e.g. `tooShort`/`tooLong` come from a `StringContainer`'s length check,
`mustBeAtLeast`/`mustBeAtMost`
from a numeric container's range check, and `KlerkText.Unauthorized`/`KlerkText.NoAllowingRule` from failed
authorization rules.
A rule never spells out its own message. `invalidPropertyCollection` builds the one for a failed
`Validatable`/`PropertyCollectionValidity` rule, and `preventedByRule` the one for a failed `validateWithContext`
rule — both from the rule's name plus whatever `translationInfo` it passed. The default implementations prettify the
name (e.g. `mustBeEven` → "Must be even"), so translating a rule means switching on its name:

```kotlin
override fun invalidPropertyCollection(functionName: String, translationInfo: String?): String =
    when (functionName) {
        "obligatoriskaFaltMasteVaraIfyllda" -> "Följande fält saknas: ${translationInfo.orEmpty()}"
        else -> DefaultKlerkTranslation.invalidPropertyCollection(functionName, translationInfo)
    }
```

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
class MyKlerkTranslation(val default: KlerkTranslation) : KlerkTranslation by default {

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
    override val klerk: KlerkTranslation = MyKlerkTranslation(DefaultKlerkTranslation)
}
```

Note the `when` matches on specific `KProperty1`/`EventReference`/function references, falling through to
`default` for everything else — this is how you translate only the handful of properties and events that need a special
label, without maintaining a translation for every single field in the system.

## Swedish

`SwedishKlerkTranslation` has Swedish versions of the texts in `KlerkText` and of the validation messages. It does not
translate the names of models, properties, events and enum values; delegate to it and override those:

```kotlin
object AppKlerkTranslation : KlerkTranslation by SwedishKlerkTranslation {
    override fun modelName(kClass: KClass<*>): String = when (kClass) {
        Author::class -> "Författare"
        else -> SwedishKlerkTranslation.modelName(kClass)
    }
}
```

## Wiring it up

Since `translation` lives on your `Ctx`, switching language is just constructing the context differently — typically
based on the request's locale, or a user's saved preference:

```kotlin
fun contextForRequest(actor: ActorIdentity, locale: String): Ctx =
    Ctx(actor, translation = if (locale == "sv") SwedishTranslation else DefaultTranslation)
```

See [testing](testing.md) for asserting on translated output (`Problem.endUserTranslatedMessage`) rather than on the
underlying rule that produced it.

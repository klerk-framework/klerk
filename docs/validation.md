# Validation

Before a command is accepted, Klerk runs several layers of validation, cheapest/most-general first. Any failure
short-circuits the rest and is returned as a `Problem` in a `CommandResult.Failure` (see
[events and commands](events-and-commands.md)):

1. Per-property rules on each `DataContainer` in the event's parameters.
2. Cross-property rules on the parameters class as a whole (`Validatable`).
3. Rules that use the `Ctx` (`validateWithContext`).
4. `ModelID`s are checked against a declared [view](views.md) (`validReferences`).
5. `EnumContainer`s are checked against a declared set of allowed values (`validEnums`).

Steps 1, 4 and 5 cover every value in the parameters, also those in a `List`, a `Set` or a nested class. A problem
names the value by its path, e.g. `address.street` or `tags[1]`.
6. Rules that see the full picture — parameters, context, and (for instance events) the current model (`validate` /
   `validateWithParameters`).
7. [Authorization](authorization.md) rules.

Every rule and validator must be a named function reference, such as `::mustBeEven`, since its name identifies the rule
in messages, translations and documentation. A lambda is rejected when Klerk starts.

## 1. Per-property rules (DataContainer)

Every `DataContainer` subclass validates its own value: the built-in constraints (`minLength`/`maxLength` for
`StringContainer`, `min`/`max` for the numeric containers, ...) plus anything you add to `validators`:

```kotlin
class BookTitle(value: String) : StringContainer(value) {
    override val minLength = 2
    override val maxLength = 100
    override val maxLines = 1
    override val validators = setOf(::`title must be catchy`)

    private fun `title must be catchy`(title: String, translation: Translation): PropertyValidity {
        return PropertyValidity.Valid // or PropertyValidity.Invalid("optional translation info")
    }
}
```

A validator function takes the value and the current `Translation`, and returns `PropertyValidity.Valid` or
`PropertyValidity.Invalid(translationInfo)`. This check happens purely on the container's own value — it never sees
sibling properties, the context, or the model. Since the value is passed in, a rule can be a top-level function shared
by several containers.
See [models](models.md) for the full list of built-in containers.

## 2. Cross-property rules (Validatable)

When a rule needs to look at more than one property together — of a model, or of an event's parameters class — implement
`Validatable`:

```kotlin
data class CreateAuthorParams(
    val firstName: FirstName,
    val lastName: LastName,
    val phone: PhoneNumber,
) : Validatable {

    override fun validators(): Set<() -> PropertyCollectionValidity> =
        setOf(::augustStrindbergCannotHaveCertainPhoneNumber)

    private fun augustStrindbergCannotHaveCertainPhoneNumber(): PropertyCollectionValidity {
        return if (firstName.value == "August" && lastName.value == "Strindberg" && phone.value == "123456")
            Invalid() else Valid
    }
}
```

Each validator returns `PropertyCollectionValidity.Valid` or `PropertyCollectionValidity.Invalid(...)`.
`Invalid` optionally carries:

* `translationInfo` — a detail handed to the `Translation` when it builds the message, e.g. which field is missing.
  The message itself always comes from the `Translation`, keyed on the rule's name; a rule never spells it out.
* `fieldMustBeNull` / `fieldMustNotBeNull` — a `KProperty0<DataContainer<*>?>` pointing at the offending field (s), so
  that the error can be attributed to a specific field instead of just the object as a whole.

`Validatable` works exactly the same way whether you implement it on a model class (`Author`) or on an event's
parameters class (`CreateAuthorParams`) — see [models](models.md) for the model-level example.

## 3–6. Rules declared in the state machine

The remaining layers are declared per-event, inside the `event(...) { }` block in your
[state machine](state-machines.md):

```kotlin
event(CreateAuthor) {
    validateWithContext(::preventUnauthenticated)
    validateWithParameters(::cannotHaveAnAwfulName)
    validReferences(CreateAuthorParams::favouriteColleague, views.authors.all)
}

event(CreateBook) {
    validReferences(CreateBookParams::author, views.authors.all)
    validEnums(CreateBookParams::genre, BookGenre.entries.toSet())
}
```

* **`validateWithContext(function: (C) -> ContextValidity)`** — runs against the `Ctx` alone, before
  parameters are even looked at. Use it for rules like "this event requires an authenticated actor". It returns
  `ContextValidity` rather than `PropertyCollectionValidity`, since no property is examined; a failure is a
  `PreventedByRuleProblem` (`KlerkErrorCode.PreventedByRule`):

  ```kotlin
  fun preventUnauthenticated(context: Ctx): ContextValidity =
      if (context.actor == Unauthenticated) ContextValidity.Invalid() else ContextValidity.Valid
  ```

* **`validReferences(property, view)`** — every `ModelID` in the parameters must be declared here, pointing at the
  [view](views.md) it must be found in. This includes a `List` or `Set` of ids, where every id must be in the view,
  and ids in a nested class, which you declare with the nested class's property:

  ```kotlin
  data class CreateBookParams(val author: ModelID<Author>, val coAuthors: Set<ModelID<Author>>, val shelf: Placement)
  data class Placement(val nextTo: ModelID<Book>)

  event(CreateBook) {
      validReferences(CreateBookParams::author, views.authors.all)
      validReferences(CreateBookParams::coAuthors, views.authors.all)
      validReferences(Placement::nextTo, views.books.all)
  }
  ```

  Klerk starts only if every `ModelID` is declared; otherwise it throws `IllegalConfigurationException`
  (`KlerkErrorCode.MissingValidReferences`) with a message telling you exactly what to add. There is no way to opt
  out: every id a command carries must be in a view. To accept any model of a type, declare its `all` view.

* **`validEnums(property, validValues)`** — restricts an `EnumContainer` (or a `List`/`Set` of them) to a subset of
  the enum's values, e.g. to phase out a value without removing it from the enum itself.

Declaring `validReferences` or `validEnums` for a property that is not in the event's parameters throws
`IllegalConfigurationException` (`KlerkErrorCode.ValidationRuleForUnknownProperty`) at startup.

* **`validate(function)`** and **`validateWithParameters(function)`** — the general escape hatch, run last, with access
  to the full `Arg...` object for the event (context, reader, command — and for instance events, the current model).
  `validate` is for rules that don't need the parameters (available even when the command carries none);
  `validateWithParameters` additionally receives the typed parameters:

  ```kotlin
  fun cannotHaveAnAwfulName(
      args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>
  ): PropertyCollectionValidity {
      return if (args.command.params.firstName.value == "Mike" && args.command.params.lastName.value == "Litoris")
          Invalid() else Valid
  }
  ```

  Because these rules get a `reader`, they can look up other models — e.g. "no author can be named Astrid while there's
  already an author named Rowling":

  ```kotlin
  fun onlyAllowAuthorNameAstridIfThereIsNoRowling(
      args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>
  ): PropertyCollectionValidity {
      args.reader.apply {
          if (args.command.params.firstName.value != "Astrid") return Valid
          val rowling = views.authors.all.asSequence().firstOrNull { it.props.firstName.value == "Rowling" }
          return if (rowling == null) Valid else Invalid()
      }
  }
  ```

  For instance events, use `InstanceEventArgs` instead, which additionally carries the current `model`.

## 7. Authorization

The final gate is whether the actor is allowed to perform the event at all — see [authorization](authorization.md).
Authorization runs after every validation rule above has passed, so a rejected command always tells you the most
specific reason first (a bad property beats a generic "unauthorized").

## Translated messages

Every message shown to an end user goes through the `Translation` on the current `Ctx` (see
[context](context.md)), so validation failures are automatically localized without your validators needing to know about
languages.

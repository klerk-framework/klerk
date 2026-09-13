# Authorization

Klerk enforces authorization. You declare rules once, in the specification, and Klerk applies them everywhere: on every model
read, every individual property read, every command, and the event log.

```kotlin
SpecificationBuilder<Ctx, Views>(views).build {
    authorization {
        readModels {
            positive { rule(::everybodyCanRead) }
            negative { rule(::unauthenticatedCannotReadAstrid) }
        }
        readProperties {
            positive { rule(::everybodyCanReadAllProperties) }
            negative { rule(::cannotReadAstridsFirstName) }
        }
        commands {
            positive { rule(::everybodyCanDoEverything) }
            negative { }
        }
        eventLog {
            positive { rule(::everybodyCanReadEventLog) }
            negative { }
        }
        readAttachedData {
            positive { rule(::onlyProjectMembersCanReadAttachments) }
            negative { }
        }
        writeAttachedData {
            positive { rule(::anyLoggedInUserCanUpload) }
            negative { }
        }
    }
    // other specification
}
```

There are six independent rule categories — `readModels`, `readProperties`, `commands` (i.e. events/commands),
`eventLog`, `readAttachedData` and `writeAttachedData` — each with its own `positive`/`negative` rule sets. A category
with no rules at all denies everything in that category, since there is no rule to explicitly allow it. Both the
categories and the `positive`/`negative` blocks inside them are optional, so declare only the ones you need.

Every rule must be a named function reference, such as `::everybodyCanRead`, since its name identifies the rule in
problems and documentation. A lambda is rejected when Klerk starts.

For prototyping, `authorization { allowEverythingInsecurely() }` fills in all categories with "allow everybody" and
logs a warning — never use it in production.

## What the rules guarantee

The rules run before a model is handed to you — by `get`, `list` and friends. You can therefore safely pass the
retrieved model to the user.

Note that the rules are not run internally, so you must be careful so that an attacker cannot infer a value. Say, for
example, that you have a property `secretKey` that is on a Customer model. You have a rule that allows the user to read
`createdAt` on the model and another rule that denys access to `secretKey`. If you build a UI that allows the user to
search among the Customer models and it is possible to filter on `secretKey`, the attacker could pass a guess in the
filter and repeat until a model is found. The attacker will not be able to see `secretKey` but can infer that the guess
was correct. To prevent this, you may want to have a rate limit or moving `secretKey` to a different model type that is
not exposed to the user.

## How positive and negative rules combine

Every rule returns one of two small enums:

```kotlin
enum class PositiveAuthorization { NoOpinion, Allow }
enum class NegativeAuthorization { Pass, Deny }
```

For a given operation, Klerk evaluates both sets the same way, in this order:

1. If **any** negative rule returns `Deny`, the operation is rejected — regardless of what the positive rules say.
2. Otherwise, if **no** positive rule returns `Allow` (i.e. they all say `NoOpinion`, including the case where there are
   zero rules), the operation is rejected — "no policy explicitly allowed the request".
3. Otherwise, it's allowed.

In other words: deny always wins, and you need at least one rule to actively opt in — there is no implicit allow.

## Rule categories

### readModels

Gates whether an actor can read a `Model<T>` at all — via `Reader.get`, `view.asSequence()`, `view.query(...)`, etc
(see [reading](reading.md)). Rules
receive an `ArgModelContextReader<C, V>` (`model`, `context`, `reader`):

```kotlin
fun unauthenticatedCannotReadAstrid(args: ArgModelContextReader<Ctx, Views>): NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is Unauthenticated)
        Deny else Pass
}
```

If a model fails this check, `view.asSequence()`/`view.query(...)` silently omit it, while `Reader.get` and the
`...OrThrow` view variants throw `AuthorizationException` — see [reading](reading.md).

### readProperties

Gates whether an actor can read one specific property *value* of a model that it's already allowed to read as a whole.
Rules receive an `ArgsForPropertyAuth<C, V>` (`property`, `model`, `context`, `reader`):

```kotlin
fun cannotReadAstridsFirstName(args: ArgsForPropertyAuth<Ctx, Views>): NegativeAuthorization =
    if (args.property is FirstName && args.property.value == "Astrid") Deny else Pass
```

This is evaluated at most once per [`DataContainer`](models.md) property on the model (recursively, including containers
nested in plain data classes, `Set`s and `List`s). If unauthorized, `DataContainer.value` throws
`AuthorizationException` when accessed; `valueOrNullIfNotAuthorized` returns `null` instead, and `toString()` prints the
masked placeholder. See [models](models.md) for details on `DataContainer`'s authorization-aware accessors.

### commands

Gates whether an actor can submit a given `Command` (see [events and commands](events-and-commands.md)). Rules receive
an `ArgCommandContextReader<*, C, V>` (`command`, `context`, `reader`):

```kotlin
fun everybodyCanDoEverything(args: ArgCommandContextReader<*, Ctx, Views>): PositiveAuthorization =
    PositiveAuthorization.Allow
```

This runs as the last step of command validation — after all rules described in [validation](validation.md) have already
passed — so a command that's both invalid and unauthorized is reported as invalid, not unauthorized.

### eventLog

Gates whether an actor can read entries from the event log (`eventLog(...)` inside a read block, see
[events and commands](events-and-commands.md)). Rules receive an `ArgContextReader<C, V>` (`context`, `reader`) —
there's no per-entry model here, so this is an all-or-nothing gate rather than something you can narrow per entry.

### readAttachedData

Gates whether an actor can read [attached data](attached-data.md), i.e. `klerk.attachedData.get(id, context)` and
`klerk.attachedData.getMetadata(id, context)`. Rules receive an `ArgsForAttachedDataRead<C, V>` (`owner`, `context`,
`reader`):

```kotlin
fun onlyProjectMembersCanReadAttachments(args: ArgsForAttachedDataRead<Ctx, Views>): PositiveAuthorization {
    val owner = args.owner.props
    if (owner !is File) return PositiveAuthorization.NoOpinion
    val project = args.reader.get(owner.project)
    return if (project.props.members.contains(args.context.actor.id)) PositiveAuthorization.Allow
    else PositiveAuthorization.NoOpinion
}
```

`owner` is the model the data is attached to, which is what makes model-relative policies expressible and keeps them
correct as the model changes.

**These rules are only consulted for private data.** Data prepared as `AttachedDataVisibility.Public` is readable by
anyone, and a negative rule here will not stop it — see [visibility](attached-data.md#visibility) for why.

### writeAttachedData

Gates whether an actor can call `klerk.attachedData.prepare(...)`. Rules receive an `ArgsForAttachedDataWrite<C, V>`
(`kind`, `lease`, `context`, `reader`).

This category is weak by construction: at `prepare` time the data has not been attached to anything, so there is no
model and no command. The real gate on *attaching* data to a model is the ordinary `commands` authorization of the
command that claims it, and what the value may be — including its `visibility` — is declared on the container the
property holds, blob or string alike — see
[what an attached property declares](attached-data.md#what-an-attached-property-declares).

What is left for these rules is the `kind` ("anyone may upload JSON, only editors may upload a blob") and the `lease`
(who may keep unclaimed data around for hours rather than a minute).

## ActorIdentity

All of the above key off `context.actor`. See [context](context.md) for the full list of `ActorIdentity`
implementations (`Unauthenticated`, `SystemIdentity`, `ModelIdentity`, ...) and how to check the concrete type of the
actor inside a rule.

`SystemIdentity` bypasses authorization entirely.

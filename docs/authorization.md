# Authorization

Klerk enforces authorization itself — you don't sprinkle `if (!user.canRead(...))` checks through your code. Instead you
declare rules once, in the config, and Klerk applies them everywhere: on every model read, every individual property
read, every command, and the event log.

```kotlin
ConfigBuilder<Context, MyCollections>(collections).build {
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
    }
}
```

There are four independent rule categories — `readModels`, `readProperties`, `commands` (i.e. events/commands), and
`eventLog` — each with its own `positive`/`negative` rule sets. A category with no rules at all denies everything in
that category, since there is no rule to explicitly allow it.

For prototyping, `insecureAllowEverything()` fills in all four categories with "allow everybody" and logs a warning —
never use it in production.

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

`SystemIdentity` (see [context](context.md)) bypasses authorization entirely for model/property reads — code running as
the system always sees everything.

## Rule categories

### readModels

Gates whether an actor can read a `Model<T>` at all — via `Reader.get`, `list`, etc (see [reading](reading.md)). Rules
receive an `ArgModelContextReader<C, V>` (`model`, `context`, `reader`):

```kotlin
fun unauthenticatedCannotReadAstrid(args: ArgModelContextReader<Context, MyCollections>): NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is Unauthenticated)
        Deny else Pass
}
```

If a model fails this check, `Reader.get`/`list`/etc. throw `AuthorizationException` (or, for the `...IfAuthorized`
variants, the model is silently omitted — see [reading](reading.md)).

### readProperties

Gates whether an actor can read one specific property *value* of a model that it's already allowed to read as a whole.
Rules receive an `ArgsForPropertyAuth<C, V>` (`property`, `model`, `context`, `reader`):

```kotlin
fun cannotReadAstridsFirstName(args: ArgsForPropertyAuth<Context, MyCollections>): NegativeAuthorization {
    return if (args.property is FirstName && args.property.valueWithoutAuthorization == "Astrid") Deny else Pass
}
```

This is evaluated once per [`DataContainer`](models.md) property on the model (recursively, including containers nested
in plain data classes, `Set`s and `List`s) the moment the model is read — not lazily when you call
`.value`. If unauthorized, `DataContainer.value` throws `AuthorizationException` when later accessed;
`valueOrNullIfNotAuthorized` returns `null` instead, and `toString()` prints the masked placeholder. See
[models](models.md) for details on `DataContainer`'s authorization-aware accessors.

### commands

Gates whether an actor can submit a given `Command` (see [events and commands](events-and-commands.md)). Rules receive
an `ArgCommandContextReader<*, C, V>` (`command`, `context`, `reader`):

```kotlin
fun everybodyCanDoEverything(args: ArgCommandContextReader<*, Context, MyCollections>): PositiveAuthorization =
    PositiveAuthorization.Allow
```

This runs as the last step of command validation — after all rules described in [validation](validation.md) have already
passed — so a command that's both invalid and unauthorized is reported as invalid, not unauthorized.

### eventLog

Gates whether an actor can read entries from the audit log (`klerk.events.getEventsInAuditLog(...)`, see
[events and commands](events-and-commands.md)). Rules receive an `ArgContextReader<C, V>` (`context`, `reader`) —
there's no per-entry model here, so this is an all-or-nothing gate rather than something you can narrow per entry.

## ActorIdentity

All of the above key off `context.actor`. See [context](context.md) for the full list of `ActorIdentity`
implementations (`Unauthenticated`, `SystemIdentity`, `ModelIdentity`, ...) and how to check the concrete type of the
actor inside a rule.

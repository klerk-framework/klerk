# Authorization

Klerk enforces authorization. You declare rules once, in the specification, and Klerk applies them everywhere: on every model
read, every individual property read, every command, and the event log.

```kotlin
SpecificationBuilder<Ctx, Views>(views).build {
    authorization {
        readModels {
            positive(::everybodyCanRead)
            negative(::unauthenticatedCannotReadAstrid)
        }
        readProperties {
            positive(::everybodyCanReadAllProperties, ::authorsCanReadTheirOwnDrafts)
            negative(::cannotReadAstridsFirstName)
        }
        commands {
            positive(::everybodyCanDoEverything)
        }
        eventLog {
            positive(::everybodyCanReadEventLog)
        }
        readAttachedData {
            positive(::onlyProjectMembersCanReadAttachments)
        }
        writeAttachedData {
            positive(::anyLoggedInUserCanUpload)
        }
        readJobs {
            positive(::usersCanSeeTheirOwnJobs)
        }
        controlJobs {
            positive(::usersCanCancelTheirOwnJobs)
        }
    }
    // other specification
}
```

There are nine independent rule categories — `readModels`, `readProperties`, `commands` (i.e. events/commands),
`eventLog`, `activityLog`, `readAttachedData`, `writeAttachedData`, `readJobs` and `controlJobs` — each with its own
`positive`/`negative` rule sets. A category with no rules at all denies everything in that category, since there is no
rule to explicitly allow it. Both the categories and the `positive`/`negative` calls inside them are optional, so
declare only the ones you need.

`positive` and `negative` take one or more rules, so related rules can be listed together or given a call each —
whichever reads better.

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

Every rule receives arguments that implement `RuleArgs<C, V>` (`context`, `reader`), so a helper written against
`RuleArgs` works in rules of any category.

### readModels

Gates whether an actor can read a `Model<T>` at all — via `Reader.get`, `view.asSequence()`, `view.query(...)`, etc
(see [reading](reading.md)). Rules
receive an `ModelReadRuleArgs<C, V>` (`model`, `context`, `reader`):

```kotlin
fun unauthenticatedCannotReadAstrid(args: ModelReadRuleArgs<Ctx, Views>): NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is Unauthenticated)
        Deny else Pass
}
```

If a model fails this check, `view.asSequence()`/`view.query(...)` silently omit it, while `Reader.get` and the
`...OrThrow` view variants throw `AuthorizationException` — see [reading](reading.md).

### readProperties

Gates whether an actor can read one specific property *value* of a model that it's already allowed to read as a whole.
Rules receive an `PropertyReadRuleArgs<C, V>` (`property`, `model`, `context`, `reader`):

```kotlin
fun cannotReadAstridsFirstName(args: PropertyReadRuleArgs<Ctx, Views>): NegativeAuthorization =
    if (args.property is FirstName && args.property.value == "Astrid") Deny else Pass
```

This is evaluated at most once per [`DataContainer`](models.md) property on the model (recursively, including containers
nested in plain data classes, `Set`s and `List`s). If unauthorized, `DataContainer.value` throws
`AuthorizationException` when accessed; `valueOrNullIfNotAuthorized` returns `null` instead, and `toString()` prints the
masked placeholder. See [models](models.md) for details on `DataContainer`'s authorization-aware accessors.

### commands

Gates whether an actor can submit a given `Command` (see [events and commands](events-and-commands.md)). Rules receive
an `CommandRuleArgs<*, C, V>` (`command`, `context`, `reader`):

```kotlin
fun everybodyCanDoEverything(args: CommandRuleArgs<*, Ctx, Views>): PositiveAuthorization =
    PositiveAuthorization.Allow
```

This runs as the last step of command validation — after all rules described in [validation](validation.md) have already
passed — so a command that's both invalid and unauthorized is reported as invalid, not unauthorized.

A command on a model the actor may not read (see [readModels](#readmodels)) is processed like any other if it passes. If
it fails for any reason, the only problem returned is a `NotFoundProblem`, exactly as if the model did not exist.

The rules also decide what `possibleEvents` and `possibleVoidEvents` return (see [reading](reading.md)). There the
parameters are not known yet, so `args.command.params` is null even for an event that takes parameters — a rule that
needs them must handle that:

```kotlin
val params = args.command.params as? PublishParams ?: return Pass
```

#### generalCommands

A restricted variant of `commands`, for rules that never need a model or parameters — only the event and the actor.
Rules receive an `EventRuleArgs<C, V>` (`event`, `context`, `reader`), which has no `model` at all:

```kotlin
fun onlyAdminsMayManageUsers(args: EventRuleArgs<Ctx, Views>): NegativeAuthorization =
    if (args.event == DeactivateUser && !args.context.isAdmin()) Deny else Pass
```

Every rule declared here is automatically included when authorizing real commands too, so write a check like this
once instead of duplicating it in `commands`. Positive rules work too, e.g. a whitelist of the events a role may
run. The payoff is `Reader.isGenerallyPossible(eventRef)`: since these rules can't depend on which instance is
targeted, they can answer "could the actor ever submit this event" with no model at all — false if a negative rule
denies it, or if no `generalCommands` positive rule allows it and there are no `commands` positive rules. That lets
tooling built on Klerk (e.g. klerk-mcp) decide which actions to expose to an actor without a concrete instance.

Use `commands` instead as soon as a rule needs to look at the model or the event's parameters, e.g.
`apiTokenCanOnlyBeRevokedByOwner` checking `args.command.model` against the token's owner — that can't be expressed
as a `generalCommands` rule, and rules that need it stay ordinary `commands` rules.

### eventLog

Gates whether an actor can read entries from the event log (`eventLog(...)` inside a read block, see
[events and commands](events-and-commands.md)). Rules receive an `EventLogRuleArgs<C, V>` (`context`, `reader`) —
there's no per-entry model here, so this is an all-or-nothing gate rather than something you can narrow per entry.

### activityLog

Gates whether an actor can read the activity log, `klerk.activityLog`: an in-memory record of recent starts, stops,
successful commands and model reads, including who read which model. Rules receive an `ActivityLogRuleArgs<C, V>`
(`context`, `reader`) and decide for the whole log. `entries(context)` throws `AuthorizationException` when denied, and
so does collecting `subscribe(context)` or `subscribeToReads(context)`.

### readAttachedData

Gates whether an actor can read [attached data](attached-data.md), i.e. `klerk.attachedData.get(id, context)` and
`klerk.attachedData.getMetadata(id, context)`. Rules receive an `AttachedDataReadRuleArgs<C, V>` (`owner`, `context`,
`reader`):

```kotlin
fun onlyProjectMembersCanReadAttachments(args: AttachedDataReadRuleArgs<Ctx, Views>): PositiveAuthorization {
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

Gates whether an actor can call `klerk.attachedData.prepare(...)`. Rules receive an `AttachedDataWriteRuleArgs<C, V>`
(`kind`, `lease`, `context`, `reader`).

This category is weak by construction: at `prepare` time the data has not been attached to anything, so there is no
model and no command. The real gate on *attaching* data to a model is the ordinary `commands` authorization of the
command that claims it, and what the value may be — including its `visibility` — is declared on the container the
property holds, blob or string alike — see
[what an attached property declares](attached-data.md#what-an-attached-property-declares).

What is left for these rules is the `kind` ("anyone may upload JSON, only editors may upload a blob") and the `lease`
(who may keep unclaimed data around for hours rather than a minute).

### readJobs

Who may see a job's metadata — its status, progress and log — through `JobManager.get`, `JobManager.all` and
`JobManager.subscribe`. The rules receive a `JobReadRuleArgs`, whose `isOwnedByActor()` answers "did this actor
schedule it?" (`isOwnedBy(actor)` asks about someone else). It uses `ActorIdentity.isSameAs`, so an actor without `id`
or `externalId`, e.g. `Unauthenticated`, owns no jobs.

```kotlin
readJobs {
    positive(::usersCanSeeTheirOwnJobs)
}

fun usersCanSeeTheirOwnJobs(args: JobReadRuleArgs<Ctx, Views>): PositiveAuthorization =
    if (args.isOwnedByActor()) Allow else NoOpinion
```

Declaring no rule here denies every job read, which is what makes a progress bar silently empty. Seeing a job does not
allow controlling it.

### controlJobs

Who may cancel, resume or delete a job through `JobManager.cancel`, `JobManager.resume` and `JobManager.delete`. The
rules receive a `JobControlRuleArgs`, which adds the `operation` (`JobOperation.Cancel`, `Resume` or `Delete`) to what
`JobReadRuleArgs` has:

```kotlin
controlJobs {
    positive(::usersCanCancelTheirOwnJobs, ::adminsCanDoAnything)
}

fun usersCanCancelTheirOwnJobs(args: JobControlRuleArgs<Ctx, Views>): PositiveAuthorization =
    if (args.operation == JobOperation.Cancel && args.isOwnedByActor()) Allow else NoOpinion
```

Resuming a job whose `job.agent` is `JobAgent.System` runs its remaining steps with full authority, so allow that only
for trusted actors. Use `JobManager.isAllowed(id, operation, context)` to decide whether to offer an operation in a UI.

## ActorIdentity

All of the above key off `context.actor`. See [context](context.md) for the full list of `ActorIdentity`
implementations (`Unauthenticated`, `SystemIdentity`, `ModelIdentity`, ...) and how to check the concrete type of the
actor inside a rule.

`SystemIdentity` bypasses all authorization rules. Commands submitted by the system are still validated.

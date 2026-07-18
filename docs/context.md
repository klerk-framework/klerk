# Context

Every operation in Klerk — reading, issuing a command, or evaluating an authorization rule — happens on behalf of
someone, at some point in time, in some language. This is carried around in a `Context`, which you define yourself
by implementing `KlerkContext`:

```kotlin
interface KlerkContext {
    val actor: ActorIdentity
    val auditExtra: String?
    val translation: Translation
    val time: Instant
}
```

A typical application-specific context adds whatever else its rules need, e.g. a cached reference to the current
user:

```kotlin
data class Context(
    override val actor: ActorIdentity,
    override val auditExtra: String? = null,
    override val time: Instant = Clock.System.now(),
    override val translation: Translation = DefaultTranslation,
    val user: Model<User>? = null,
) : KlerkContext {

    companion object {
        fun fromUser(user: Model<User>): Context = Context(ModelIdentity(user), user = user)
        fun unauthenticated(): Context = Context(Unauthenticated)
        fun system(): Context = Context(SystemIdentity)
    }
}
```

The type parameter `C` used throughout Klerk's API (`Klerk<C, V>`, `StateMachine<T, S, C, V>`, ...) is your
`KlerkContext` implementation.

## actor

`actor` is an `ActorIdentity` — who is performing the operation. This is what authorization rules and business rules
key off of. The built-in identities are:

| Identity | Meaning |
|---|---|
| `Unauthenticated` | The request has no logged-in user. |
| `AuthenticationIdentity` | A trusted identity used by code that performs authentication itself (e.g. checking a password before a session exists). |
| `ModelIdentity(model)` | The actor is a specific model instance, typically a user model. |
| `ModelReferenceIdentity(id)` | Like `ModelIdentity`, but only holds the id (no need to have read the model first). |
| `SystemIdentity` | Klerk itself, used when the framework executes something in the background (see `systemContextProvider` below). |
| `CustomIdentity` | An escape hatch for identities that don't fit the other cases. |
| `PluginIdentity(plugin)` | Used by plugins acting on their own behalf. |

Business and authorization rules narrow on the concrete type, e.g.:

```kotlin
fun unauthenticatedCannotReadAstrid(args: ArgModelContextReader<Context, MyCollections>): NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is Unauthenticated) Deny else Pass
}
```

## time

`time` is the instant the operation is considered to happen at. Business logic should always read the time from the
context instead of calling `Clock.System.now()` directly — this is what makes state-machine code
([state-machines.md](state-machines.md)) deterministic and testable, since tests can supply a fixed or fake time.

## translation

`translation` carries a `Translation`, which supplies human-readable text for validation messages, property names,
event names, and so on (see [translation](translation.md)). Passing a different `Translation` per context is how you
support multiple languages for the same actor pool — e.g. `Context.swedishUnauthenticated()` in the test suite uses a
`SwedishTranslation` while the default uses `DefaultTranslation`.

## auditExtra

An optional free-text string that is stored alongside the audit log entry for whatever command is processed with
this context. Use it to attach information that doesn't belong in the event parameters but is still useful when
reviewing history (e.g. "imported from legacy system", a support ticket id, ...).

## systemContextProvider

Klerk sometimes needs to act without an actor supplying a context — most notably when a state machine's time
trigger (`after(...)`, `atTime(...)`) fires in the background, or when a job runs. For these cases you must register
a function in the config that builds a `Context` from a `SystemIdentity`:

```kotlin
ConfigBuilder<Context, MyCollections>(collections).build {
    systemContextProvider { systemIdentity -> Context(systemIdentity) }
    // ...
}
```

This is mandatory — `ConfigBuilder.build()` throws `IllegalConfigurationException` if it's missing.

## Getting a context for reading

See [reading.md](reading.md) for how a `Context` is used together with `klerk.read`/`klerk.readSuspend`. Inside the
DSL functions of a state machine, you never construct a `Context` yourself — one is handed to you as part of the
`Arg...` parameter (e.g. `ArgForInstanceEvent.context`), carrying over the context of whichever command triggered the
call.

# Specification & settings

A Klerk application is described by two objects:

```kotlin
val klerk = Klerk.create(specification, settings)
```

**`Specification`** is what the application *is*: its models, state machines, events, authorization rules, views, job
types, cron schedules and migrations. It is the executable specification of the business — if you change it, you have
changed the product.

**`KlerkSettings`** is how *this instance* runs: where it stores its data, what it reads the time from, where it
publishes metrics, and how hard the job dispatcher works.

## Building them

```kotlin
val specification = SpecificationBuilder<Ctx, Views>(views).build {
    managedModels { ... }
    authorization { ... }
    systemContextProvider { systemIdentity -> Ctx(systemIdentity) }
}

val settings = KlerkSettings(
    persistence = SqlPersistence(dataSource),
    attachedBlobStore = AttachedBlobStore.Database,
)
```

`persistence` is the only `KlerkSettings` parameter without a default, so it cannot be forgotten. Everything else has a
production-sane default.

## In tests

The point of the split is that a test reuses the application's real specification and changes only the settings:

```kotlin
val klerk = Klerk.create(
    createSpecification(views),                       // the same one production uses
    KlerkSettings(
        persistence = RamStorage(),
        clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
        jobs = JobSettings(execution = JobExecution.Manual),
    ),
)
```

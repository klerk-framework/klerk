# Specification & settings

A Klerk application is described by two objects, and `Klerk.create` takes both:

```kotlin
val klerk = Klerk.create(specification, settings)
```

**`Specification`** is what the application *is*: its models, state machines, events, authorization rules, views, job
types, cron schedules and migrations. It is the executable specification of the business — if you change it, you have
changed the product.

**`KlerkSettings`** is how *this instance* runs: where it stores its data, what it reads the time from, where it
publishes metrics, and how hard the job dispatcher works.

The rule of thumb: *could two deployments of the same product legitimately differ on this?* If yes, it is a setting.

## What goes where

| Specification                                         | KlerkSettings                                           |
|-------------------------------------------------------|---------------------------------------------------------|
| `managedModels` (models + state machines + views)     | `persistence`                                           |
| `authorization`                                       | `attachedBlobStore`                                     |
| `jobs { register(...) / cron(...) / admission(...) }` | `clock`                                                 |
| `migrations`                                          | `meterRegistry`                                         |
| `systemContextProvider`, `jobContextProvider`         | `jobs` (a [`JobSettings`](jobs.md))                     |
| plugins (`withPlugin`)                                | `allowUnsafeOperations`                                 |
| `eraseAuditLogAfterModelDeletion`                     | `unclaimedAttachedDataLifetime`, `maxAttachedDataLease` |
|                                                       | `contentTypeDetector`                                   |
|                                                       | `modelCache` (a [`ModelCacheSettings`](eviction.md))    |

Two placements are worth explaining:

- **Jobs are split.** *Which* jobs exist and who may queue them is the application (`JobsSpecification`); parallelism,
  polling, retention, retry backoff and whether jobs run at all are operational (`JobSettings`). A plugin can add job
  types and crons, and by construction cannot touch the operational half.
- **`eraseAuditLogAfterModelDeletion` is in the specification**, not the settings. "We erase the audit trail when a user
  is deleted" is a privacy promise about the application, and it must not vary per deployment.

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

## Related

- [Persistence & migrations](persistence.md) — the storage backends
- [Eviction](eviction.md) — `modelCache`, and what it costs to keep less in memory
- [Jobs](jobs.md) — `JobsSpecification` and `JobSettings` in detail
- [Time](time.md) — the settings clock
- [Testing](testing.md) — the full testing story

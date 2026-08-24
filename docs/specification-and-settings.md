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

## From environment variables

To make it easier to change the settings, Klerk supports reading them from environment variables.

`KlerkSettings.fromEnvVars` reads many of the settings from environment variables, falling back to the regular default
when a variable is unset.

```kotlin
val settings = KlerkSettings.fromEnvVars(persistence = SqlPersistence(dataSource))
```

| Environment variable                     | Setting                                                |
|------------------------------------------|--------------------------------------------------------|
| `KLERK_ALLOW_UNSAFE_OPERATIONS`          | `allowUnsafeOperations`                                |
| `KLERK_UNCLAIMED_ATTACHED_DATA_LIFETIME` | `unclaimedAttachedDataLifetime`                        |
| `KLERK_MAX_ATTACHED_DATA_LEASE`          | `maxAttachedDataLease`                                 |
| `KLERK_JOBS_ON_UNLOADABLE_JOB`           | `jobs.onUnloadableJob` (`FailToStart` or `DeadLetter`) |
| `KLERK_JOBS_EXECUTION`                   | `jobs.execution` (`Automatic` or `Manual`)             |
| `KLERK_JOBS_SUCCEEDED_RETENTION`         | `jobs.succeededRetention`                              |
| `KLERK_JOBS_CANCELLED_RETENTION`         | `jobs.cancelledRetention`                              |
| `KLERK_JOBS_DEAD_LETTER_RETENTION`       | `jobs.deadLetterRetention`                             |
| `KLERK_JOBS_HARD_QUEUE_LIMIT`            | `jobs.hardQueueLimit`                                  |
| `KLERK_JOBS_MAX_PARALLEL_STEPS`          | `jobs.maxParallelSteps`                                |
| `KLERK_JOBS_POLL_INTERVAL`               | `jobs.pollInterval`                                    |
| `KLERK_JOBS_BACKOFF_BASE`                | `jobs.backoffBase`                                     |
| `KLERK_MODEL_CACHE_MAX_RESIDENT_MODELS`  | `modelCache.maxResidentModels`                         |

Durations accept both `kotlin.time.Duration` syntax (`"24h"`, `"1d 2h"`) and ISO-8601 (`"PT24H"`).

## In tests

A benefit of separating specification and settings is that tests can use the application's real specification and change
only the settings:

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

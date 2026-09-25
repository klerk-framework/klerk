# Metrics

Klerk and its plugins publish metrics with [Micrometer](https://micrometer.io) to one registry:
`KlerkSettings.meterRegistry`. By default it is a private `SimpleMeterRegistry` that is not exported anywhere. To export
the metrics, pass your application's registry:

```kotlin
val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

val settings = KlerkSettings(
    persistence = SqlPersistence(dataSource),
    meterRegistry = prometheus,
)
```

Klerk has `micrometer-core` as an `api` dependency. The registry implementation, e.g.
`io.micrometer:micrometer-registry-prometheus`, is added by the application. If you use `klerk-bom`, it also aligns
the Micrometer versions, so leave out the version:

```kotlin
dependencies {
    implementation(platform("dev.klerkframework:klerk-bom:<version>"))
    implementation("dev.klerkframework:klerk")
    implementation("io.micrometer:micrometer-registry-prometheus")
}
```

## What Klerk publishes

| Name | Type | Tags | |
|---|---|---|---|
| `klerk.commands` | counter | `model`, `event`, `outcome` | Commands handled, from `klerk.handle` and job steps. `outcome` is `success` or `failure`. Dry runs are not counted. |
| `klerk.models.count` | gauge | | The number of models. |
| `klerk.models.resident` | gauge | | The number of model bodies held in memory. See [performance](performance.md). |
| `klerk.models.load` | timer | | How long reading all persisted models took at startup. |

`model` and `event` are the names in the event's `EventReference`, e.g. `Author` and `CreateAuthor`.

## Plugins

A plugin publishes to the same registry, `klerk.settings.meterRegistry`, and names its meters `klerk.<plugin>.<name>`,
e.g. `klerk.web.image.render`. Each plugin documents what it publishes.

# Klerk Framework

Klerk is a Kotlin framework for developing information systems. It replaces the database and
business-logic code in a traditional system. You are free to use other backend components to build whatever you want on
top of Klerk, such as

* an API (JSON, REST, GraphQL) serving your frontend
* a web app using server generated HTML
* a microservice (communicating via RPC or message queues)

# Getting Started

Add this in settings.gradle.kts:

```
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven ("https://jitpack.io")
    }
}
```

Now add the dependency in build.gradle.kts:

```
dependencies {
    implementation("com.github.klerk-framework:<version>")
}
```

Make sure you have configured the project to use Java 17 or later.

## Now what?

When developing a system using Klerk, we must:

__1. Build a configuration__: This is where you declare all rules.

```
val config = ConfigBuilder<Ctx, Data>(collections).build {
    // lots of stuff here
}
```

__2. Start Klerk__

```
val klerk = Klerk.create(config)
klerk.meta.start()
```

__3. Use Klerk__: Read data and issue commands to modify data.

```
val myBook = klerk.read(context) { get(myBookId) }
```

## Documentation

* [Models](docs/models.md) — defining model classes and `DataContainer` properties
* [State machines](docs/state-machines.md) — states, transitions, time triggers
* [Events and commands](docs/events-and-commands.md) — declaring events and calling `klerk.handle`
* [Validation](docs/validation.md) — property, cross-property, and event-level rules
* [Authorization](docs/authorization.md) — who can read and write what
* [Views](docs/views.md) — declaring queryable, live-updating lists of models
* [Reading data](docs/reading.md) — querying with a `Reader`
* [Context](docs/context.md) — actor, time, and translation for every operation
* [Jobs](docs/jobs.md) — background work, managed and unmanaged
* [Persistence & migrations](docs/persistence.md) — storage backends and evolving model shapes
* [Key-value store](docs/key-value-store.md) — simple storage outside the model system
* [Translation](docs/translation.md) — localizing validation and UI text
* [Testing](docs/testing.md) — testing a Klerk configuration
* [Concurrency](docs/concurrency.md) — how commands and reads are serialized, and what that means for your code
* [Security](docs/security.md) — how authorization, concurrency, and the audit log combine into Klerk's security model
* [Performance](docs/performance.md) — what's fast by default, and what to do if it isn't enough

Read more on [klerkframework.dev](https://klerkframework.dev)

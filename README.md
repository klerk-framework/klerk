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

Read more on [klerkframework.dev](https://klerkframework.dev)

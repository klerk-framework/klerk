# Klerk

Klerk is a Kotlin framework for developing information systems on the JVM. It replaces the database and
business-logic code in a traditional system. You are free to use other backend components to build whatever you want on
top of Klerk, such as

* an API (JSON, REST, GraphQL) serving your frontend
* a web app using server generated HTML
* a microservice (communicating via RPC or message queues)

## Documentation

The documentation can be found [here](documentation/README.md).
There is also a [troubleshooting guide](documentation/Troubleshooting.md).

## Installation

Ask for a token, store it on your local file system as described here:
https://docs.gitlab.com/ee/user/packages/gradle_repository/#authenticate-to-the-package-registry-with-gradle

In your consuming project add this to your build.gradle.kts

```
repositories {
    ...
    maven {
        url = uri("https://gitlab.com/api/v4/projects/33843632/packages/maven")
        name = "GitLab"
        credentials(HttpHeaderCredentials::class) {
            name = "Private-Token"
            value = findProperty("gitLabPrivateToken") as String?
        }
        authentication {
            create("header", HttpHeaderAuthentication::class)
        }
    }
}
```

and

```
dependencies {
    ...
    implementation("dev.klerkframework:klerk:<version>")
}
```

Make sure you have configured the project to use Java 17 or later.

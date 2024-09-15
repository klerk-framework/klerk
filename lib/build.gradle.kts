import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.0.0"
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka") version "1.9.20"
}

val coroutinesVersion = "1.4.2"
val datetimeVersion = "0.6.0"
val exposedVersion = "0.47.0"
val ktor_version = "2.3.10"
val micrometer_version = "1.11.1"
val graphql_version = "7.1.1"
val kotlinLoggingVersion = "2.1.21"
val slf4jVersion = "2.0.3"
val uuidCreatorVersion = "5.3.3"
val googleApiClientVersion = "2.2.0"
val sqliteJdbcVersion = "3.44.1.0"
val gsonVersion = "2.9.0"
val webauthnVersion = "2.5.2"

group = "dev.klerkframework"
version = "1.0.0-beta.2"

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    implementation("io.ktor:ktor-server-html-builder:$ktor_version")
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth:$ktor_version")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")

    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    implementation("io.micrometer:micrometer-core:$micrometer_version")

    implementation("com.google.code.gson:gson:$gsonVersion")

    // authentication plugin
    implementation("com.yubico:webauthn-server-core:$webauthnVersion")
    implementation("com.google.api-client:google-api-client:$googleApiClientVersion")
    // --->

    // graphql
    implementation("com.expediagroup:graphql-kotlin-ktor-server:$graphql_version")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
}

publishing {
    publications {
        create<MavenPublication>("Maven") {
            artifactId = "klerk"
            from(components["java"])
        }
    }

    repositories {
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
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}

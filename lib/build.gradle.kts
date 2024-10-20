import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka") version "1.9.20"
}

val coroutinesVersion = "1.4.2"
val datetimeVersion = "0.6.0"
val exposedVersion = "0.47.0"
val micrometerVersion = "1.11.1"
val kotlinLoggingVersion = "2.1.21"
val slf4jVersion = "2.0.3"
val sqliteJdbcVersion = "3.44.1.0"
val gsonVersion = "2.9.0"

group = "dev.klerkframework"
version = "1.0.0-beta.3"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    implementation("io.micrometer:micrometer-core:$micrometerVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")
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
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}

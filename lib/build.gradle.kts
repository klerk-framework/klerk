import org.jetbrains.dokka.gradle.formats.DokkaFormatPlugin
import org.jetbrains.dokka.gradle.internal.InternalDokkaGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.3.10"
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka") version "2.2.0"
}

val coroutinesVersion = "1.10.2"
val serializationVersion = "1.9.0"
val datetimeVersion = "0.7.1"
val exposedVersion = "0.47.0"
val micrometerVersion = "1.11.1"
val kotlinLoggingVersion = "2.1.21"
val slf4jVersion = "2.0.9"
val sqliteJdbcVersion = "3.44.1.0"
val caffeineVersion = "3.2.4"

group = "dev.klerkframework"
version = "1.0.0-beta.7-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
    // api: Flow and SharedFlow appear in Klerk's public API (KlerkModels.subscribe, JobManager.subscribe, KlerkLog).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    // Used only internally (Logger.atLevel); no slf4j type appears in Klerk's public API.
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    // api: MeterRegistry is a KlerkSettings property.
    api("io.micrometer:micrometer-core:$micrometerVersion")
    implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
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

// Klerk's own tests exercise the experimental API.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("dev.klerkframework.klerk.ExperimentalKlerkApi")
}

// Declares Markdown Gradle plugin
@OptIn(InternalDokkaGradlePluginApi::class)
abstract class DokkaMarkdownPlugin : DokkaFormatPlugin(formatName = "markdown") {
    override fun DokkaFormatPlugin.DokkaFormatPluginContext.configure() {
        project.dependencies {
            // Sets up current project generation
            dokkaPlugin(dokka("gfm-plugin"))

            // Sets up multi-project generation
            formatDependencies.dokkaPublicationPluginClasspathApiOnly.dependencies.addLater(
                dokka("gfm-template-processing-plugin")
            )
        }
    }
}
// Applies the plugin
apply<DokkaMarkdownPlugin>()

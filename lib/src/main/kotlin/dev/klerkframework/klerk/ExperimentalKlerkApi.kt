package dev.klerkframework.klerk

/**
 * Marks a part of Klerk that is experimental: it may change or be removed in any release, without a migration path.
 *
 * Opt in per declaration with `@OptIn(ExperimentalKlerkApi::class)`, or for a whole module with the Kotlin compiler
 * argument `-opt-in=dev.klerkframework.klerk.ExperimentalKlerkApi`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an experimental part of Klerk and may change or be removed in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class ExperimentalKlerkApi

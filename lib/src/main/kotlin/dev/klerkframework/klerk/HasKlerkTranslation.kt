package dev.klerkframework.klerk

import dev.klerkframework.klerk.misc.functionName
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Converts a camelCase identifier to a space-separated, capitalized phrase, e.g. `"firstName"` -> `"First name"`. */
public fun camelCaseToPretty(s: String): String = buildString {
    for ((index, c) in s.withIndex()) {
        when {
            index == 0 -> append(c.titlecase(Locale.getDefault()))
            c.isUpperCase() -> append(" ${c.lowercase()}")
            else -> append(c.lowercase())
        }
    }
}

/**
 * Supplies human-readable text for validation, property, and event names. Implement to support additional languages.
 */
public interface HasKlerkTranslation {
    /** The strings Klerk itself produces. */
    public val klerk: KlerkTranslation
}

/** Built-in framework-level strings (used by [DefaultKlerkTranslation] unless overridden). */
public interface KlerkTranslation {
    /** The name of [property], as shown to an end user. */
    public fun property(property: KProperty1<*, *>): String

    /** A description of [property], e.g. shown as a tooltip, or null if it has none. */
    public fun propertyDescription(property: KProperty1<*, *>): String?

    /** The name of [event], as shown to an end user. */
    public fun event(event: EventReference): String

    /**
     * The name of the rule [f], a named function reference, as shown in generated documentation. Not a failure
     * message — see [invalidPropertyCollection] and [preventedByRule] for those.
     */
    public fun function(f: Function<Any>): String

    /** The message when a number is smaller than [value]. */
    public fun mustBeAtLeast(value: Number): String

    /** The message when a number is larger than [value]. */
    public fun mustBeAtMost(value: Number): String

    /** The message when a duration is shorter than [value]. */
    public fun mustBeAtLeast(value: Duration): String

    /** The message when a duration is longer than [value]. */
    public fun mustBeAtMost(value: Duration): String

    /** The message when a duration is not a whole number of [unit]. */
    public fun mustBeWholeUnits(unit: DurationUnit): String

    /** The message when a decimal number has more than [scale] digits after the decimal point. */
    public fun tooManyDecimals(scale: Int): String

    /** The message when the validator [functionName] rejects the property [propertyName]. */
    public fun invalidProperty(propertyName: String, functionName: String, translationInfo: String?): String

    /** The message when a [Validatable] or event validation rule rejects a class as a whole. */
    public fun invalidPropertyCollection(functionName: String, translationInfo: String?): String

    /** The message when a `validateWithContext` rule refuses a command based on the context alone. */
    public fun preventedByRule(functionName: String, translationInfo: String?): String

    /** The message when a string is shorter than [minLength]. */
    public fun tooShort(minLength: Int): String

    /** The message when a string is longer than [maxLength]. */
    public fun tooLong(maxLength: Int): String

    /** The message when a string has more than [maxLines] lines. */
    public fun tooManyLines(maxLines: Int): String

    /** The name of the model whose props class is [kClass], as shown to an end user. */
    public fun modelName(kClass: KClass<*>): String

    /** The name of the enum [value], as shown to an end user. */
    public fun enumValue(value: Enum<*>): String

    /** The text for [key]; English unless overridden. */
    public fun text(key: KlerkText): String = key.english
}

/**
 * The DefaultTranslator can be used when you don't want to translate your application
 */
public object DefaultTranslation : HasKlerkTranslation {
    override val klerk: KlerkTranslation = DefaultKlerkTranslation
}

/** The English strings Klerk uses by default. */
public object DefaultKlerkTranslation : KlerkTranslation {

    override fun modelName(kClass: KClass<*>): String = camelCaseToPretty(requireNotNull(kClass.simpleName))

    override fun enumValue(value: Enum<*>): String = camelCaseToPretty(value.name)

    override fun property(property: KProperty1<*, *>): String = camelCaseToPretty(property.name)

    override fun propertyDescription(property: KProperty1<*, *>): String? = null

    override fun event(event: EventReference): String = camelCaseToPretty(event.eventName)

    override fun function(f: Function<Any>): String =
        functionName(f)?.let { camelCaseToPretty(it) } ?: text(KlerkText.Invalid)

    override fun invalidProperty(propertyName: String, functionName: String, translationInfo: String?): String =
        camelCaseToPretty(functionName)

    override fun invalidPropertyCollection(functionName: String, translationInfo: String?): String =
        camelCaseToPretty(functionName)

    override fun preventedByRule(functionName: String, translationInfo: String?): String =
        camelCaseToPretty(functionName)

    override fun tooShort(minLength: Int): String = "Must be at least $minLength characters"
    override fun tooLong(maxLength: Int): String = "Must be at most $maxLength characters"
    override fun tooManyLines(maxLines: Int): String = "Must be at most $maxLines lines"
    override fun mustBeAtLeast(value: Number): String = "Must be at least $value"
    override fun mustBeAtMost(value: Number): String = "Must be at most $value"
    override fun mustBeAtLeast(value: Duration): String = "Must be at least $value"
    override fun mustBeAtMost(value: Duration): String = "Must be at most $value"
    override fun mustBeWholeUnits(unit: DurationUnit): String = "Must be a whole number of ${unit.name.lowercase()}"
    override fun tooManyDecimals(scale: Int): String = "Must have at most $scale decimals"
}

/** The fixed texts of Klerk. Get the text in a [HasKlerkTranslation] with [KlerkTranslation.text]. */
public enum class KlerkText(
    /** The English text. */
    public val english: String,
) {
    /** The message when a required value is missing. */
    MustBeProvided("Must be provided"),

    /** A generic message for an invalid value. */
    Invalid("Invalid"),

    /** The message when something went wrong inside Klerk. */
    InternalError("Internal error"),

    /** The message when the actor is not allowed to do something. */
    Unauthorized("Unauthorized"),

    /** The message when no authorization rule allowed the operation. */
    NoAllowingRule("No policy explicitly allowed the request"),

    /** The name of a model's ID, e.g. as a column header. */
    Id("ID"),

    /** The name of a model's state, e.g. as a column header. */
    State("State"),

    /** The name of when a model was created, e.g. as a column header. */
    Created("Created"),

    /** The name of when a model was last modified, e.g. as a column header. */
    Updated("Updated"),

    /** The name of a summary of a model's properties, e.g. as a column header. */
    Properties("Properties"),
}

/** The texts in [KlerkText] that [translation] has not translated, i.e. that are still the English default. */
public fun KlerkTranslation.untranslatedTexts(): List<KlerkText> = KlerkText.entries.filter { text(it) == it.english }

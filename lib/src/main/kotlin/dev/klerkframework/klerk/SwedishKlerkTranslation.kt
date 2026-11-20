package dev.klerkframework.klerk

import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The texts and messages of Klerk in Swedish. The names of models, properties, events and enum values are not
 * translated; use delegation to supply them:
 *
 * ```kotlin
 * object MyKlerkTranslation : KlerkTranslation by SwedishKlerkTranslation {
 *     override fun modelName(kClass: KClass<*>): String = ...
 * }
 * ```
 */
public object SwedishKlerkTranslation : KlerkTranslation by DefaultKlerkTranslation {
    private val texts = mapOf(
        KlerkText.MustBeProvided to "Måste anges",
        KlerkText.Invalid to "Ogiltigt",
        KlerkText.InternalError to "Internt fel",
        KlerkText.Unauthorized to "Obehörig",
        KlerkText.NoAllowingRule to "Ingen tillåtande regel",
        KlerkText.State to "Status",
        KlerkText.Created to "Skapad",
        KlerkText.Updated to "Ändrad",
        KlerkText.Properties to "Egenskaper",
    )

    override fun text(key: KlerkText): String = texts[key] ?: DefaultKlerkTranslation.text(key)

    override fun mustBeAtLeast(value: Number): String = "Måste vara minst $value"

    override fun mustBeAtMost(value: Number): String = "Måste vara högst $value"

    override fun mustBeAtLeast(value: Duration): String = "Måste vara minst $value"

    override fun mustBeAtMost(value: Duration): String = "Måste vara högst $value"

    override fun mustBeWholeUnits(unit: DurationUnit): String = "Måste vara ett helt antal ${unit.name.lowercase()}"

    override fun tooManyDecimals(scale: Int): String = "För många decimaler (högst $scale)"

    override fun invalidProperty(propertyName: String, functionName: String, translationInfo: String?): String =
        "Ogiltig egenskap $propertyName"

    override fun invalidPropertyCollection(functionName: String, translationInfo: String?): String =
        "Ogiltiga egenskaper"

    override fun preventedByRule(functionName: String, translationInfo: String?): String =
        "Förhindrad av regeln $functionName"

    override fun tooShort(minLength: Int): String = "För kort (minst $minLength tecken)"

    override fun tooLong(maxLength: Int): String = "För lång (högst $maxLength tecken)"

    override fun tooManyLines(maxLines: Int): String = "För många rader (högst $maxLines)"
}

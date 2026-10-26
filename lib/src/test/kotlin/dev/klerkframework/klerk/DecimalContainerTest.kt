package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataCategory
import dev.klerkframework.klerk.datatypes.DecimalContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.KlerkJson
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.statemachine.executables.validateModelProps
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.validation.PropertyCollectionValidity.Invalid
import dev.klerkframework.klerk.validation.Valid
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Amount(value: BigDecimal) : DecimalContainer(value) {
    override val category: DataCategory = DataCategory.NonSensitive
    override val min: BigDecimal = BigDecimal.ZERO
    override val max: BigDecimal = BigDecimal("1000000")
    override val scale: Int = 3
}

class CurrencyCode(value: String) : StringContainer(value) {
    override val category: DataCategory = DataCategory.NonSensitive
    override val minLength: Int = 3
    override val maxLength: Int = 3
    override val maxLines: Int = 1
}

data class Price(val amount: Amount, val currency: CurrencyCode) : Validatable {
    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf(::amountFitsCurrency)

    private fun amountFitsCurrency(): PropertyCollectionValidity {
        val digits = java.util.Currency.getInstance(currency.value).defaultFractionDigits
        return if (amount.value.stripTrailingZeros().scale() <= digits) Valid else Invalid()
    }
}

data class OrderLine(val price: Price)

data class Order(val total: Price, val lines: List<OrderLine>)

private fun price(amount: String, currency: String) = Price(Amount(BigDecimal(amount)), CurrencyCode(currency))

class DecimalContainerTest {

    @Test
    fun `Validates range and decimals`() {
        assertNull(Amount(BigDecimal("12.345")).validate("amount", DefaultTranslation))
        assertNull(Amount(BigDecimal("12.3000")).validate("amount", DefaultTranslation))
        assertEquals(
            "Must have at most 3 decimals",
            Amount(BigDecimal("12.3456")).validate("amount", DefaultTranslation)?.endUserTranslatedMessage,
        )
        assertNotNull(Amount(BigDecimal("-0.01")).validate("amount", DefaultTranslation))
        assertEquals(
            "Must be at most 1000000",
            Amount(BigDecimal("1E+7")).validate("amount", DefaultTranslation)?.endUserTranslatedMessage,
        )
    }

    @Test
    fun `Equal regardless of trailing zeros`() {
        assertEquals(Amount(BigDecimal("1.0")), Amount(BigDecimal("1.00")))
        assertEquals(Amount(BigDecimal("1.0")).hashCode(), Amount(BigDecimal("1.00")).hashCode())
        assertNotEquals(Amount(BigDecimal("1.0")), Amount(BigDecimal("1.01")))
    }

    @Test
    fun `toString has no exponent`() {
        assertEquals("1000", Amount(BigDecimal("1E+3")).toString())
    }

    @Test
    fun `Stored exactly as a string`() {
        val original = price("0.100", "SEK")
        val json = KlerkJson.encode(original)
        assertTrue(json.contains("\"0.100\""), json)
        val decoded = KlerkJson.decode(Price::class, json)
        assertEquals("0.100", decoded.amount.value.toPlainString())

        val huge = price("123456789012345678901234567890.123456789", "SEK")
        assertEquals(huge.amount.value, KlerkJson.decode(Price::class, KlerkJson.encode(huge)).amount.value)
    }

    @Test
    fun `A stored value that is not a decimal fails`() {
        assertFailsWith<IllegalArgumentException> {
            ObjectSchema.of(Price::class).fromJson("""{"amount":"twelve","currency":"SEK"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectSchema.of(Price::class).fromJson("""{"amount":12.5,"currency":"SEK"}""")
        }
    }

    @Test
    fun `Has its own property type`() {
        assertEquals(PropertyType.Decimal, ObjectSchema.of(Price::class).field("amount")?.type)
    }

    @Test
    fun `Nested objects are validated`() {
        val valid = Order(price("100", "JPY"), listOf(OrderLine(price("1.234", "KWD"))))
        assertEquals(emptyList(), validateModelProps(valid, DefaultTranslation))

        val invalidTotal = valid.copy(total = price("100.5", "JPY"))
        assertEquals(1, validateModelProps(invalidTotal, DefaultTranslation).size)

        val invalidLine = valid.copy(lines = listOf(OrderLine(price("1.5", "JPY")), OrderLine(price("1.234", "KWD"))))
        assertEquals(1, validateModelProps(invalidLine, DefaultTranslation).size)
    }

    @Test
    fun `Validatables are found in declaration order`() {
        val order = Order(price("1", "SEK"), listOf(OrderLine(price("2", "SEK")), OrderLine(price("3", "SEK"))))
        val found = ObjectSchema.of(Order::class).validatables(order).map { (it as Price).amount.value.toInt() }
        assertEquals(listOf(1, 2, 3), found.toList())
    }
}

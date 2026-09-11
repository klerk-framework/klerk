package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.BookGenre
import dev.klerkframework.klerk.BookGenreContainer
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.datatypes.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class Name(value: String) : StringContainer(value) {
    override val minLength = 0
    override val maxLength = 100
    override val maxLines = 1
}

private class Score(value: Int) : IntContainer(value) {
    override val min = 0
    override val max = 100
}

private class Count(value: Long) : LongContainer(value) {
    override val min = 0L
    override val max = Long.MAX_VALUE
}

private class Ratio(value: Float) : FloatContainer(value) {
    override val min = 0f
    override val max = 1f
}

private class Flag(value: Boolean) : BooleanContainer(value)
private class Moment(value: Instant) : InstantContainer(value)
private class Day(value: LocalDate) : DateContainer(value)
private class Span(value: kotlin.time.Duration) : DurationContainer(value)
private class Place(value: GeoPosition) : GeoPositionContainer(value)

private data class Inner(val name: Name)

private data class Everything(
    val name: Name,
    val score: Score,
    val count: Count,
    val ratio: Ratio,
    val flag: Flag,
    val genre: BookGenreContainer,
    val moment: Moment,
    val day: Day,
    val span: Span,
    val place: Place,
    val ref: ModelID<Book>,
    val refs: Set<ModelID<Book>>,
    val names: List<Name>,
    val inner: Inner,
    val optional: Name?,
    val defaulted: Score = Score(1),
)

private class Big(value: ULong) : ULongContainer(value) {
    override val min = 0uL
    override val max = ULong.MAX_VALUE
}

private data class HasBig(val big: Big)
private data class HasBareString(val name: String)
private data class OnlyNullable(val moment: Moment?, val name: Name?)
private data class HasMap(val names: Map<String, Name>)
private data class HasMutableList(val names: MutableList<Name>)
private class Empty
private data class HasEmpty(val empty: Empty)

class KlerkJsonTest {

    private val everything = Everything(
        name = Name("Astrid"),
        score = Score(42),
        count = Count(9_000_000_000),
        ratio = Ratio(0.25f),
        flag = Flag(true),
        genre = BookGenreContainer(BookGenre.Mystery),
        moment = Moment(Instant.fromEpochMilliseconds(1_700_000_000_123)),
        day = Day(LocalDate.of(2026, 9, 11)),
        span = Span(90.minutes),
        place = Place(GeoPosition(59.3293, 18.0686)),
        ref = ModelID(7),
        refs = setOf(ModelID(7), ModelID(8)),
        names = listOf(Name("a"), Name("b")),
        inner = Inner(Name("inner")),
        optional = null,
    )

    @Test
    fun `Round trip`() {
        assertEquals(everything, KlerkJson.decode(Everything::class, KlerkJson.encode(everything)))
    }

    @Test
    fun `Containers used only as nullable properties round trip`() {
        val original = OnlyNullable(Moment(Instant.fromEpochMilliseconds(5)), Name("x"))
        assertEquals(original, KlerkJson.decode(OnlyNullable::class, KlerkJson.encode(original)))
    }

    @Test
    fun `Containers and ids are written as bare values`() {
        val json = KlerkJson.encodeToElement(everything)
        assertEquals(JsonPrimitive("Astrid"), json["name"])
        assertEquals(JsonPrimitive(7), json["ref"])
        assertEquals(JsonArray(listOf(JsonPrimitive(7), JsonPrimitive(8))), json["refs"])
        assertEquals(JsonPrimitive("Mystery"), json["genre"])
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("inner"))), json["inner"])
        assertEquals(JsonNull, json["optional"])
        assertEquals(JsonPrimitive(1), json["defaulted"])
    }

    @Test
    fun `An unknown key fails`() {
        assertEquals("'extra' is not a property of Everything", reasonFor { put("extra", JsonPrimitive(1)) })
    }

    @Test
    fun `A missing key fails, also when nullable or with a default value`() {
        assertEquals("'name' is missing", reasonFor { remove("name") })
        assertEquals("'optional' is missing", reasonFor { remove("optional") })
        assertEquals("'defaulted' is missing", reasonFor { remove("defaulted") })
    }

    @Test
    fun `A renamed key reports both sides`() {
        val reason = reasonFor { put("title", getValue("name")); remove("name") }
        assertEquals("'title' is not a property of Everything, 'name' is missing", reason)
    }

    @Test
    fun `Null for a non-nullable property fails`() {
        assertEquals("'name' is null, but the property is not nullable", reasonFor { put("name", JsonNull) })
    }

    @Test
    fun `A value of the wrong type fails`() {
        assertEquals("'name' is an integer, expected a string", reasonFor { put("name", JsonPrimitive(5)) })
        assertEquals("'score' is a decimal number, expected an integer", reasonFor { put("score", JsonPrimitive(1.5)) })
        assertEquals("'score' is a string, expected an integer", reasonFor { put("score", JsonPrimitive("1")) })
        assertEquals("'score' is too large for an Int", reasonFor { put("score", JsonPrimitive(3_000_000_000)) })
        assertEquals("'flag' is a string, expected a boolean", reasonFor { put("flag", JsonPrimitive("true")) })
        assertEquals("'refs' is an integer, expected an array", reasonFor { put("refs", JsonPrimitive(7)) })
        assertEquals("'inner' is a string, expected an object", reasonFor { put("inner", JsonPrimitive("x")) })
        assertEquals(
            "'genre' is 'Horror', which is not a constant of BookGenre",
            reasonFor { put("genre", JsonPrimitive("Horror")) }
        )
    }

    @Test
    fun `The path points at nested and collection values`() {
        assertEquals("'inner.name' is missing", reasonFor { put("inner", JsonObject(emptyMap())) })
        assertEquals(
            "'names[1]' is an integer, expected a string",
            reasonFor { put("names", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(2)))) }
        )
    }

    @Test
    fun `A value that a constructor rejects fails`() {
        val latitude100Degrees = 1_000_000_000L shl 32
        val reason = reasonFor { put("place", JsonPrimitive(latitude100Degrees)) }
        assertTrue(reason.startsWith("'place' could not be created"), reason)
    }

    @Test
    fun `Invalid JSON fails`() {
        val reason = assertFailsWith<JsonMismatchException> { KlerkJson.decode(Everything::class, "{") }.reason
        assertEquals("the stored value is not valid JSON", reason)
    }

    @Test
    fun `Types that cannot be stored are rejected up front`() {
        assertFailsWith<IllegalConfigurationException> { KlerkJson.requireStorable(HasBareString::class) }
        assertFailsWith<IllegalConfigurationException> { KlerkJson.requireStorable(HasMap::class) }
        assertFailsWith<IllegalConfigurationException> { KlerkJson.requireStorable(HasMutableList::class) }
        assertFailsWith<IllegalConfigurationException> { KlerkJson.requireStorable(HasEmpty::class) }
    }

    @Test
    fun `ULongContainer round-trips through JSON`() {
        val original = HasBig(Big(ULong.MAX_VALUE))
        val deserialized = KlerkJson.decode(HasBig::class, KlerkJson.encode(original))
        assertEquals(original, deserialized)
    }

    @Test
    fun `Event parameters from JSON`() {
        val parameters = EventParameters(Inner::class)
        assertEquals(Inner(Name("x")), parameters.fromJson("""{"name":"x"}"""))
        val e = assertFailsWith<IllegalArgumentException> { parameters.fromJson("""{"name":"x","other":1}""") }
        assertEquals("Invalid parameters for Inner: 'other' is not a property of Inner", e.message)
    }

    private fun reasonFor(change: MutableMap<String, JsonElement>.() -> Unit): String {
        val json = JsonObject(KlerkJson.encodeToElement(everything).toMutableMap().apply(change))
        return assertFailsWith<JsonMismatchException> { KlerkJson.decode(Everything::class, json.toString()) }.reason
    }
}

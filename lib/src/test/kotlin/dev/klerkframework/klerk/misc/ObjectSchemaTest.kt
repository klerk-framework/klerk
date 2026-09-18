package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DefaultTranslation
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.RuleDescription
import dev.klerkframework.klerk.RuleType
import dev.klerkframework.klerk.Street
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.Validatable
import dev.klerkframework.klerk.attacheddata.collectAttachedData
import dev.klerkframework.klerk.datatypes.ByteContainer
import dev.klerkframework.klerk.datatypes.DoubleContainer
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.datatypes.ShortContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.datatypes.UByteContainer
import dev.klerkframework.klerk.datatypes.UIntContainer
import dev.klerkframework.klerk.datatypes.ULongContainer
import dev.klerkframework.klerk.datatypes.UShortContainer
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.validation.PropertyValidity
import dev.klerkframework.klerk.validation.Valid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

data class SchemaAddress(val street: Street, val owner: ModelID<Author>?)

data class SchemaPerson(
    val name: Street,
    val address: SchemaAddress,
    val nicknames: List<Street>,
    val friends: Set<ModelID<Author>>,
)

data class SchemaPrivateAddress(private val street: Street)
data class SchemaHasPrivateNested(val address: SchemaPrivateAddress)
data class SchemaPrivateConstructor private constructor(val street: Street)
data class SchemaHasVar(var street: Street)
data class SchemaNestedVar(val inner: SchemaHasVar)
data class SchemaBlobHolder(val cover: AttachedBlobID)
data class SchemaNestedBlobs(val holders: List<SchemaBlobHolder>)
data class SchemaBodyVar(val street: Street) {
    var counter: Int = 0
}
data class SchemaWithDefault(val street: Street, val other: Street = Street("default"))

class LambdaValidated(value: Int) : IntContainer(value) {
    override val min = 0
    override val max = 10
    override val validators = setOf<(Int, Translation) -> PropertyValidity>({ _, _ -> PropertyValidity.Invalid() })
}

data class SchemaLambdaValidated(val number: LambdaValidated)

data class SchemaLambdaValidatable(val street: Street) : Validatable {
    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf({ Valid })
}

internal data class SchemaInternalClass(val street: Street)

class SchemaOuter {
    internal data class Nested(val street: Street)
}

internal class InternalStreet(value: String) : StringContainer(value) {
    override val minLength = 0
    override val maxLength = 10
    override val maxLines = 1
}

class ObjectSchemaTest {

    @Test
    fun `Fields describe the constructor parameters`() {
        val schema = ObjectSchema.of(SchemaPerson::class)
        assertEquals(listOf("name", "address", "nicknames", "friends"), schema.fields.map { it.name })
        assertEquals(PropertyType.String, schema.field("name")?.type)
        assertNull(schema.field("address")?.type)
        assertNull(schema.field("missing"))

        val friends = assertNotNull(schema.field("friends"))
        assertTrue(friends.isCollection)
        assertNull(friends.type)
        assertEquals(ModelID::class, friends.valueClass)
        assertEquals(Author::class, friends.referencedModel)

        val owner = assertNotNull(ObjectSchema.of(SchemaAddress::class).field("owner"))
        assertEquals(PropertyType.Ref, owner.type)
        assertTrue(owner.isNullable)
        assertEquals(Author::class, owner.referencedModel)
        assertEquals(ModelID<Author>(3), owner.get(person.address))
    }

    @Test
    fun `An instance is created from values by name`() {
        val schema = ObjectSchema.of(SchemaWithDefault::class)
        assertEquals(SchemaWithDefault(Street("a"), Street("default")), schema.create(mapOf("street" to Street("a"))))
        assertFailsWith<IllegalArgumentException> { schema.create(mapOf("other" to Street("b"))) }
        assertFailsWith<IllegalArgumentException> { schema.create(mapOf("street" to Street("a"), "nope" to 1)) }
        assertEquals("default", schema.field("other")?.defaultContainer?.valueWithoutAuthorization)
        assertNull(schema.field("street")?.defaultContainer)
    }

    @Test
    fun `A value of the wrong type is rejected when creating`() {
        val schema = ObjectSchema.of(SchemaPerson::class)
        val valid = mapOf(
            "name" to person.name,
            "address" to person.address,
            "nicknames" to person.nicknames,
            "friends" to person.friends,
        )
        assertEquals(person, schema.create(valid))

        fun rejected(name: String, value: Any?): String =
            assertFailsWith<IllegalArgumentException> { schema.create(valid + (name to value)) }.message!!

        assertTrue(rejected("name", "Main").contains("'name'"))
        assertTrue(rejected("name", null).contains("not nullable"))
        assertTrue(rejected("nicknames", listOf("a")).contains("'nicknames[0]'"))
        assertTrue(rejected("friends", person.friends.toList()).contains("Set"))
    }

    @Test
    fun `A validator must be a named function reference`() {
        val e = assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaLambdaValidated::class) }
        assertEquals(KlerkErrorCode.RuleMustBeNamed, e.code)
        assertFailsWith<IllegalConfigurationException> { LambdaValidated(1).validate("number", DefaultTranslation) }
        val validatable =
            assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaLambdaValidatable::class) }
        assertEquals(KlerkErrorCode.RuleMustBeNamed, validatable.code)
    }

    @Test
    fun `Only public classes are accepted`() {
        assertTrue(
            assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaInternalClass::class) }
                .message!!.contains("SchemaInternalClass is not public"),
        )
        assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaOuter.Nested::class) }
    }

    private val person = SchemaPerson(
        name = Street("Main"),
        address = SchemaAddress(Street("Side"), ModelID(3)),
        nicknames = listOf(Street("a"), Street("b")),
        friends = setOf(ModelID(4)),
    )

    @Test
    fun `Leaves are found in nested objects and collections`() {
        val paths = ObjectSchema.of(SchemaPerson::class).leaves(person).map { it.path }.toList()
        assertEquals(
            listOf("name", "address.street", "address.owner", "nicknames[0]", "nicknames[1]", "friends[0]"),
            paths,
        )
    }

    @Test
    fun `Leaf fields are keyed by the class that declares them`() {
        val fields = ObjectSchema.of(SchemaPerson::class).leafFields().associate { it.path to it.field.key }
        assertEquals(PropertyKey(SchemaAddress::class, "owner"), fields["address.owner"])
        assertEquals(PropertyKey(SchemaPerson::class, "nicknames"), fields["nicknames"])
        assertEquals(PropertyKey.of(SchemaAddress::owner), fields["address.owner"])
    }

    @Test
    fun `Transforming leaves rebuilds only what changed`() {
        val schema = ObjectSchema.of(SchemaPerson::class)
        assertSame(person, schema.transformLeaves(person) { it })

        val upper = schema.transformLeaves(person) {
            if (it is Street) Street(it.valueWithoutAuthorization.uppercase()) else it
        } as SchemaPerson
        assertEquals("SIDE", upper.address.street.valueWithoutAuthorization)
        assertEquals(listOf("A", "B"), upper.nicknames.map { it.valueWithoutAuthorization })
        assertSame(person.friends, upper.friends)
    }

    @Test
    fun `Classes that Klerk cannot read or create are rejected`() {
        assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaHasPrivateNested::class) }
        assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaPrivateConstructor::class) }
        assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaNestedVar::class) }
        assertFailsWith<IllegalConfigurationException> { ObjectSchema.of(SchemaBodyVar::class) }
    }

    @Test
    fun `Attached data in nested objects is found`() {
        val props = SchemaNestedBlobs(listOf(SchemaBlobHolder(AttachedBlobID(5)), SchemaBlobHolder(AttachedBlobID(6))))
        assertEquals(setOf(5, 6), collectAttachedData(props).keys)
    }

    @Test
    fun `A container is created from a field`() {
        val field = ObjectSchema.of(SchemaWithDefault::class).field("street")!!
        assertEquals("x", field.createContainer("x").valueWithoutAuthorization)
        assertFailsWith<IllegalArgumentException> { field.createContainer(5) }
    }

    @Test
    fun `A rule is described by its function name`() {
        val description = RuleDescription(::sampleRule, RuleType.ContextValidation)
        assertEquals("ContextValidation: sampleRule", description.toString())
    }
}

private fun sampleRule(context: Ctx): PropertyCollectionValidity = Valid

class SchemaShort(value: Short) : ShortContainer(value) {
    override val min = 0.toShort()
    override val max = 9.toShort()
}
class SchemaByte(value: Byte) : ByteContainer(value) {
    override val min = 0.toByte()
    override val max = 9.toByte()
}
class SchemaUInt(value: UInt) : UIntContainer(value) {
    override val min = 0u
    override val max = 9u
}
class SchemaULong(value: ULong) : ULongContainer(value) {
    override val min = 0uL
    override val max = 9uL
}
class SchemaUShort(value: UShort) : UShortContainer(value) {
    override val min = 0.toUShort()
    override val max = 9.toUShort()
}
class SchemaUByte(value: UByte) : UByteContainer(value) {
    override val min = 0.toUByte()
    override val max = 9.toUByte()
}
class SchemaDouble(value: Double) : DoubleContainer(value) {
    override val min = 0.0
    override val max = 9.0
}

data class SchemaNumbers(
    val short: SchemaShort,
    val byte: SchemaByte,
    val uInt: SchemaUInt,
    val uLong: SchemaULong,
    val uShort: SchemaUShort,
    val uByte: SchemaUByte,
    val double: SchemaDouble,
)

class NumberContainerTest {

    private val schema = ObjectSchema.of(SchemaNumbers::class)

    @Test
    fun `Every numeric container kind has a property type`() {
        assertEquals(PropertyType.Short, schema.field("short")!!.type)
        assertEquals(PropertyType.Byte, schema.field("byte")!!.type)
        assertEquals(PropertyType.UInt, schema.field("uInt")!!.type)
        assertEquals(PropertyType.ULong, schema.field("uLong")!!.type)
        assertEquals(PropertyType.UShort, schema.field("uShort")!!.type)
        assertEquals(PropertyType.UByte, schema.field("uByte")!!.type)
        assertEquals(PropertyType.Double, schema.field("double")!!.type)
    }

    @Test
    fun `Bounds are readable without knowing the kind`() {
        val uLong = SchemaULong(5uL)
        assertEquals("0", uLong.min.toString())
        assertEquals("9", uLong.max.toString())
        assertFalse(uLong.hasDecimals)
        assertTrue(SchemaDouble(1.5).hasDecimals)
    }

    @Test
    fun `Bounds are enforced`() {
        assertNull(SchemaUByte(9.toUByte()).validate("uByte", DefaultTranslation))
        assertNotNull(SchemaDouble(9.5).validate("double", DefaultTranslation))
    }
}

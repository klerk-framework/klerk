package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.attacheddata.collectAttachedData
import dev.klerkframework.klerk.datatypes.DataContainer
import kotlin.test.*

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
        assertEquals("default", schema.field("other")?.kotlinDefaultInstance?.valueWithoutAuthorization)
        assertNull(schema.field("street")?.kotlinDefaultInstance)
    }

    private val person = SchemaPerson(
        name = Street("Main"),
        address = SchemaAddress(Street("Side"), ModelID(3)),
        nicknames = listOf(Street("a"), Street("b")),
        friends = setOf(ModelID(4)),
    )

    @Test
    fun `Leaves are found in nested objects and collections`() {
        val paths = mutableListOf<String>()
        ObjectSchema.of(SchemaPerson::class).forEachLeaf(person) { paths.add(it.path) }
        assertEquals(
            listOf("name", "address.street", "address.owner", "nicknames[0]", "nicknames[1]", "friends[0]"),
            paths
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
    fun `A container is created from its class`() {
        assertEquals("x", DataContainer.create(Street::class, "x").valueWithoutAuthorization)
        assertFailsWith<IllegalArgumentException> { DataContainer.create(Street::class, 5) }
    }

    @Test
    fun `A rule is described by its function name`() {
        assertEquals("ContextValidation: sampleRule", RuleDescription(::sampleRule, RuleType.ContextValidation).toString())
    }
}

private fun sampleRule(context: Ctx): PropertyCollectionValidity = PropertyCollectionValidity.Valid

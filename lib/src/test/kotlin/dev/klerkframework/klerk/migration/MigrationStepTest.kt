package dev.klerkframework.klerk.migration

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.SqlPersistence
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationStepTest {

    private val bc = BookViews()
    private val collections = Views(bc, AuthorViews(bc.all))

    @Test
    fun `A migration step rewrites the stored models`() = runBlocking {
        val persistence = storedSampleData()
        val rename = Step(2, authors { it.withProps { put("firstName", JsonPrimitive("Changed")) } })
        val klerk = klerkWith(persistence, rename)
        klerk.meta.start()
        assertEquals(2, klerk.settings.persistence.currentModelSchemaVersion)
        val firstNames = mutableSetOf<String>()
        persistence.readAllModels { model -> (model.props as? Author)?.let { firstNames.add(it.firstName.value) } }
        assertEquals(setOf("Changed"), firstNames)
        klerk.meta.stop()
    }

    @Test
    fun `Startup fails when a stored property is not in the model class`() = runBlocking {
        val persistence = storedSampleData()
        val reason = startupProblem(persistence, Step(2, authors { renameKey(it, "firstName", "givenName") }))
        assertTrue(reason.contains("'givenName' is not a property of Author"), reason)
        assertTrue(reason.contains("'firstName' is missing"), reason)
    }

    @Test
    fun `A later migration step fixes a mismatch`() = runBlocking {
        val persistence = storedSampleData()
        val broken = Step(2, authors { renameKey(it, "firstName", "givenName") })
        startupProblem(persistence, broken)

        val fixed = Step(3, authors { renameKey(it, "givenName", "firstName") })
        val klerk = klerkWith(persistence, broken, fixed)
        klerk.meta.start()
        assertEquals(3, klerk.settings.persistence.currentModelSchemaVersion)
        klerk.meta.stop()
    }

    @Test
    fun `Startup fails when a non-nullable property is missing`() = runBlocking {
        val reason = startupProblem(storedSampleData(), Step(2, authors { it.withProps { remove("lastName") } }))
        assertTrue(reason.contains("'lastName' is missing"), reason)
    }

    @Test
    fun `Startup fails when a nullable property is missing`() = runBlocking {
        val reason = startupProblem(storedSampleData(), Step(2, authors { it.withProps { remove("picture") } }))
        assertTrue(reason.contains("'picture' is missing"), reason)
    }

    @Test
    fun `Startup fails when a property with a default value is missing`() = runBlocking {
        val reason = startupProblem(storedSampleData(), Step(2, books { it.withProps { remove("genre") } }))
        assertTrue(reason.contains("'genre' is missing"), reason)
    }

    @Test
    fun `Startup fails when a property has changed type`() = runBlocking {
        val reason = startupProblem(storedSampleData(), Step(2, authors { it.withProps { put("firstName", JsonPrimitive(5)) } }))
        assertTrue(reason.contains("'firstName' is an integer, expected a string"), reason)
    }

    @Test
    fun `Startup fails when a nested property is missing`() = runBlocking {
        val step = Step(2, authors {
            it.withProps { put("address", JsonObject(getValue("address").jsonObject - "street")) }
        })
        val reason = startupProblem(storedSampleData(), step)
        assertTrue(reason.contains("'address.street' is missing"), reason)
    }

    @Test
    fun `Startup fails when there is no model class for a stored type`() = runBlocking {
        val reason = startupProblem(storedSampleData(), Step(2, authors { it.copy(type = "Writer") }))
        assertTrue(reason.contains("there is no model class named Writer"), reason)
    }

    @Test
    fun `renameKey throws if the key does not exist`() {
        val step = Step(2) { it }
        val model = MigrationModelV1("Author", 1, kotlin.time.Clock.System.now(), kotlin.time.Clock.System.now(),
            kotlin.time.Clock.System.now(), "Created", JsonObject(emptyMap()))
        assertFailsWith<IllegalStateException> { step.renameKey(model, "nope", "other") }
    }

    private suspend fun storedSampleData(): SqlPersistence {
        val persistence = SQLiteInMemory.create()
        val klerk = klerkWith(persistence)
        klerk.meta.start()
        generateSampleData(3, 2, klerk)
        assertEquals(1, klerk.settings.persistence.currentModelSchemaVersion)
        klerk.meta.stop()
        return persistence
    }

    private suspend fun startupProblem(persistence: Persistence, vararg steps: MigrationStep): String {
        val klerk = klerkWith(persistence, *steps)
        return assertFailsWith<PersistedModelMismatchException> { klerk.meta.start() }.reason
    }

    private fun klerkWith(persistence: Persistence, vararg steps: MigrationStep): Klerk<Ctx, Views> {
        val specification = SpecificationBuilder<Ctx, Views>(collections).build {
            migrations(steps.toSet())
            managedModels {
                model(Book::class, bookStateMachine(collections), collections.books)
                model(Author::class, authorStateMachine(collections), collections.authors)
            }
            apply(addStandardTestConfiguration())
        }
        return Klerk.create(specification, testSettings(persistence))
    }
}

private class Step(
    override val migratesToVersion: Int,
    private val transform: MigrationStepV1toV1.(MigrationModelV1) -> MigrationModelV1?,
) : MigrationStepV1toV1 {
    override val description = "Test step to version $migratesToVersion"
    override fun migrateModel(original: MigrationModelV1): MigrationModelV1? = transform(original)
}

private fun authors(change: MigrationStepV1toV1.(MigrationModelV1) -> MigrationModelV1?): MigrationStepV1toV1.(MigrationModelV1) -> MigrationModelV1? =
    { if (it.type == "Author") change(it) else it }

private fun books(change: MigrationStepV1toV1.(MigrationModelV1) -> MigrationModelV1?): MigrationStepV1toV1.(MigrationModelV1) -> MigrationModelV1? =
    { if (it.type == "Book") change(it) else it }

private fun MigrationModelV1.withProps(change: MutableMap<String, JsonElement>.() -> Unit): MigrationModelV1 =
    copy(props = JsonObject(props.toMutableMap().apply(change)))

package dev.klerkframework.klerk.migration

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.SqlPersistence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationStepTest {

    @Test
    fun migrate() {
        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val persistence = SQLiteInMemory.create()

        runBlocking {
            var klerk = Klerk.create(createConfigWithMigrations(collections, emptySet()), testSettings(persistence))
            klerk.meta.start()
            generateSampleData(10, 3, klerk)
            assertEquals(1, klerk.settings.persistence.currentModelSchemaVersion)
            klerk.meta.stop()

            klerk = Klerk.create(createConfigWithMigrations(collections, setOf(MyMigrationStep)), testSettings(persistence))
            klerk.meta.start()
            assertEquals(2, klerk.settings.persistence.currentModelSchemaVersion)
        }
    }

    private fun createConfigWithMigrations(
        collections: Views,
        steps: Set<MigrationStep>,
    ): Specification<Ctx, Views> {
        return SpecificationBuilder<Ctx, Views>(collections).build {
            migrations(steps)
            managedModels {
                model(Book::class, bookStateMachine(collections), collections.books)
                model(Author::class, authorStateMachine(collections), collections.authors)
            }
            apply(addStandardTestConfiguration())
        }
    }
}

object MyMigrationStep : MigrationStepV1toV1 {
    override val description = "Min första migrering"
    override val migratesToVersion = 2
    override fun migrateModel(original: MigrationModelV1): MigrationModelV1? {
        return if (original.type == "Book") renameKey(original, "coAuthors", "changed") else original
    }
}

object MyMigrationStep2 : MigrationStepV1toV1 {
    override val description = "Min första migrering"
    override val migratesToVersion = 2
    override fun migrateModel(original: MigrationModelV1): MigrationModelV1? {
        return if (original.type == "Book") renameKey(original, "coAuthors", "changed") else original
    }
}

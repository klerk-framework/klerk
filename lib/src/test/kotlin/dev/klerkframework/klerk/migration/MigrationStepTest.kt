package dev.klerkframework.klerk.migration

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.SqlPersistence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationStepTest {

    @Test
    fun migrate() {
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val persistence = SQLiteInMemory.create()

        runBlocking {
            var klerk = Klerk.create(createConfigWithMigrations(collections, persistence, emptySet()))
            klerk.meta.start()
            generateSampleData(10, 3, klerk)
            assertEquals(1, klerk.config.persistence.currentModelSchemaVersion)
            klerk.meta.stop()

            klerk = Klerk.create(createConfigWithMigrations(collections, persistence, setOf(MyMigrationStep)))
            klerk.meta.start()
            assertEquals(2, klerk.config.persistence.currentModelSchemaVersion)
        }
    }

    private fun createConfigWithMigrations(
        collections: MyCollections,
        persistence: SqlPersistence,
        steps: Set<MigrationStep>
    ): Config<Context, MyCollections> {
        return ConfigBuilder<Context, MyCollections>(collections).build {
            persistence(persistence)
            migrations(steps)
            managedModels {
                model(Book::class, bookStateMachine(collections.authors.all, collections), collections.books)
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

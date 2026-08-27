# Persistence & migrations

Klerk owns persistence itself — you don't write SQL or design a schema. You choose (or implement) a `Persistence`
backend, put it in `KlerkSettings`, and Klerk uses it to durably store models, the event log, jobs, and
[attached data](attached-data.md).

There are two implementations in the framework today.

## SqlPersistence

```kotlin
KlerkSettings(persistence = SqlPersistence(myDataSource))
```

Backed by a SQL database via a `javax.sql.DataSource`, using [Exposed](https://github.com/JetBrains/Exposed) as the SQL
layer. On construction it connects and creates its tables if missing (event log, models, schema-migration tracking,
attached data, jobs, cron state), then reads the current model schema version from the
`klerk_model_schema_migrations` table. Model `props` and command `params` are stored as JSON.

For production, supply a
`DataSource` for your actual database (e.g. a connection pool pointed at Postgres/MySQL/etc., anything Exposed can talk
to through JDBC).

To quickly get started with a local SQLite database, import `org.xerial:sqlite-jdbc` and use:

```kotlin
private fun createPersistence(): Persistence {
    val dbFilePath =
        requireNotNull(System.getenv("DATABASE_PATH")) { "The environment variable 'DATABASE_PATH' must be set" }
    val ds = SQLiteDataSource()
    ds.url = "jdbc:sqlite:$dbFilePath"
    return SqlPersistence(ds)
}
```

## RamStorage

```kotlin
KlerkSettings(persistence = RamStorage())
```

Keeps everything in a set of in-memory maps. It is explicitly documented as "should only be used for testing" — nothing
survives a restart, and `migrate()` is a no-op because a fresh `RamStorage` is always empty on startup. This is the
default in the test suite (`testSettings(storage: Persistence = RamStorage())`).

## Wiring persistence into settings

```kotlin
Klerk.create(
    specification,
    KlerkSettings(persistence = myPersistence),
)
```

Which backend an instance uses is not part of the [specification](../README.md) — the same specification runs on
`RamStorage` in a test and on `SqlPersistence` in production. `persistence` is the one `KlerkSettings` parameter without
a default, so it cannot be forgotten.

Attached blobs are configured separately with `KlerkSettings.attachedBlobStore`, and required as soon as the
specification declares a blob property. **With `FileBlobStore`, a database backup no longer contains the blobs** — back
up its directory as well. See [attached data](attached-data.md).

## Migrations

Model classes evolve over time, but already-persisted data was written against an older shape. `MigrationStep`
describes one step of turning old persisted data into what the current model classes expect:

```kotlin
interface MigrationStep {
    val description: String
    val migratesToVersion: Int
}

interface MigrationStepV1toV1 : MigrationStep {
    fun migrateModel(original: MigrationModelV1): MigrationModelV1? = original
    fun renameKey(original: MigrationModelV1, from: String, to: String): MigrationModelV1
}
```

`MigrationStepV1toV1` is the concrete kind of step available today (there is room in the naming for future
`MigrationStepV1toV2`-style steps once a more structural schema version bump is introduced). Its `migrateModel`
receives every persisted model as a generic `MigrationModelV1` — `type` (the model's simple class name), `id`,
timestamps, `state`, and `props` as a raw `Map<String, Any>` (i.e. before it is deserialized back into your actual data
class). Returning the same instance unchanged leaves the stored model untouched, returning a modified copy rewrites its
stored JSON, and returning `null` deletes the model. `renameKey` is a small helper for the common case of a renamed
property; it throws if the `from` key isn't found.

```kotlin
object RenameCoAuthorsToCoWriters : MigrationStepV1toV1 {
    override val description = "Rename Book.coAuthors to coWriters"
    override val migratesToVersion = 2
    override fun migrateModel(original: MigrationModelV1): MigrationModelV1? {
        return if (original.type == "Book") renameKey(original, "coAuthors", "coWriters") else original
    }
}
```

Migration steps describe how the application evolves, so they belong in the specification; the backend they run against
is a setting:

```kotlin
val specification = SpecificationBuilder<Context, MyCollections>(collections).build {
    migrations(setOf(RenameCoAuthorsToCoWriters))
    managedModels {
        model(Book::class, bookStateMachine(collections), collections.books)
        model(Author::class, authorStateMachine(collections), collections.authors)
    }
    // authorization, systemContextProvider, ...
}

val klerk = Klerk.create(specification, KlerkSettings(persistence = SqlPersistence(myDataSource)))
```

### Versioning rules

`migratesToVersion` values must form a contiguous sequence starting at 2 (version 1 is the implicit starting schema —
there is no explicit step to reach it). `Specification.validateMigrations()` enforces this when
`Klerk.create(specification, settings)` initializes the specification (before `klerk.meta.start()` is even called):

- every step's `migratesToVersion` must be `> 1`
- every step's `description` must be shorter than 200 characters
- folding over the sorted steps, each one's `migratesToVersion` must be exactly one more than the previous
  (`1, 2, 3, ...`) — a gap throws `"Missing migration to version N"`

`Persistence.currentModelSchemaVersion` reports which version the store is currently at (for `SqlPersistence`, read from
the migrations table; `RamStorage` is hardcoded to `1` since it starts empty every time). On startup, Klerk calls
`persistence.migrate(migrationSteps)`; `SqlPersistence` applies any steps beyond the currently stored version, in order,
rewriting each affected model's stored JSON and recording the new version in
`klerk_model_schema_migrations`. Adding a new migration step is therefore how you ship a change to a model's shape
without losing previously persisted instances.

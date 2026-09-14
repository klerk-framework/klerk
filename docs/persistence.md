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

## Implementing a backend

`Persistence` is an SPI: implement it to store models, the event log, jobs and attached data somewhere else. Writes
arrive as a `CommitBatch` — created, updated and deleted models, the event-log entry, the attached-data delta and the
job rows — and must be applied in one transaction. A store that cannot do that must not be used for jobs.

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

## Stored models must match the model classes

A model is stored as a JSON object with one key per constructor parameter of its class. A `DataContainer` is stored as
its `valueWithoutAuthorization` (e.g. an `InstantContainer` as microseconds since 1970), a `ModelID` as a number, a
`List` or `Set` as an array and a nested class as an object. A `ULongContainer` is stored as a JSON string, since its
range exceeds what a JSON number can represent exactly.

`klerk.meta.start()` runs any pending [migration steps](#migrations) and then reads every stored model into its model
class. A stored model must match its class exactly:

- every stored key must be a property of the class
- every property must be stored, also a nullable property and one with a default value
- every value must have the type of its property, e.g. an integer for an `IntContainer` and one of the enum's
  constants for an `EnumContainer`
- the stored type must be the simple name of a model class

The same rules apply to nested classes. If a stored model does not match, `start()` throws
`PersistedModelMismatchException` and Klerk does not start:

```
The stored Author with id 42 does not match the model classes: 'givenName' is not a property of Author,
'firstName' is missing. Register a MigrationStep that makes the stored data match.
```

Renaming, removing, adding or retyping a model property therefore requires a migration step.

## Migrations

Model classes evolve over time, but already-persisted data was written against an older shape. `MigrationStep`
describes one step of turning old persisted data into what the current model classes expect:

```kotlin
sealed interface MigrationStep {
    val description: String
    val migratesToVersion: Int
}

interface ModelMigrationStep : MigrationStep {
    fun migrateModel(original: MigrationModelV1): MigrationModelV1?
    fun renameKey(original: MigrationModelV1, from: String, to: String): MigrationModelV1
}
```

`ModelMigrationStep` is the concrete kind of step available today (there is room in the naming for future
`MigrationStepV1toV2`-style steps once a more structural schema version bump is introduced). Its `migrateModel`
receives every persisted model as a generic `MigrationModelV1` — `type` (the model's simple class name), `id`,
timestamps, `state`, and `props` as the stored `JsonObject` (from `kotlinx.serialization`). Returning the same instance
unchanged leaves the stored model untouched, returning a modified copy rewrites its stored JSON, and returning `null`
deletes the model. `renameKey` is a small helper for the common case of a renamed property; it throws if the `from` key
isn't found.

```kotlin
object RenameCoAuthorsToCoWriters : ModelMigrationStep {
    override val description = "Rename Book.coAuthors to coWriters"
    override val migratesToVersion = 2
    override fun migrateModel(original: MigrationModelV1): MigrationModelV1? {
        return if (original.type == "Book") renameKey(original, "coAuthors", "coWriters") else original
    }
}
```

Adding a property means adding its key, also when the property is nullable or has a default value. A step can rewrite
values as well, e.g. when a property changes type:

```kotlin
object AddNickname : ModelMigrationStep {
    override val description = "Add Author.nickname"
    override val migratesToVersion = 3
    override fun migrateModel(original: MigrationModelV1): MigrationModelV1? {
        if (original.type != "Author") return original
        return original.copy(props = JsonObject(original.props + ("nickname" to JsonNull)))
    }
}
```

Migration steps describe how the application evolves, so they belong in the specification; the backend they run against
is a setting:

```kotlin
val specification = SpecificationBuilder<Ctx, Views>(views).build {
    migrations(RenameCoAuthorsToCoWriters)
    managedModels {
        model(Book::class, bookStateMachine(views), views.books)
        model(Author::class, authorStateMachine(views), views.authors)
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
  (`1, 2, 3, ...`) — a gap throws `IllegalConfigurationException` (`KlerkErrorCode.InvalidMigration`)

`Persistence.currentModelSchemaVersion` reports which version the store is currently at (for `SqlPersistence`, read from
the migrations table; `RamStorage` is hardcoded to `1` since it starts empty every time). On startup, Klerk calls
`persistence.migrate(migrationSteps)`; `SqlPersistence` applies any steps beyond the currently stored version, in order,
rewriting each affected model's stored JSON and recording the new version in
`klerk_model_schema_migrations`. Adding a new migration step is therefore how you ship a change to a model's shape
without losing previously persisted instances.

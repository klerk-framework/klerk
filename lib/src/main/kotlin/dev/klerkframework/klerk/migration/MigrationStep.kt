package dev.klerkframework.klerk.migration

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * A persisted model as raw, pre-deserialization data, given to [MigrationStepV1toV1.migrateModel]. [props] is the
 * model's stored JSON, i.e. what is decoded into the props class once all migration steps have run.
 *
 * @property type the model's simple class name (e.g. `"Book"`)
 */
public data class MigrationModelV1(
    val type: String,
    val id: Int,
    val createdAt: Instant,
    val lastPropsUpdatedAt: Instant,
    val lastTransitionAt: Instant,
    val state: String,
    val props: JsonObject,
)

/**
 * One step of migrating already-persisted data to match the current model classes. Register implementations via
 * `SpecificationBuilder.migrations(...)`. [migratesToVersion] values across all registered steps must form a contiguous
 * sequence starting at 2 (enforced by `Specification.validateMigrations()` when [dev.klerkframework.klerk.Klerk.create] is
 * called) — a gap throws `IllegalConfigurationException`.
 */
public interface MigrationStep {
    /** Must be shorter than 200 characters (enforced at specification validation). */
    public val description: String

    /** The schema version this step migrates data *to*. Must be exactly one more than the previous step's, starting at 2. */
    public val migratesToVersion: Int
}

/**
 * The concrete [MigrationStep] kind available today (there is room in the naming for a future
 * `MigrationStepV1toV2`-style kind once a more structural schema-version bump is introduced).
 */
public interface MigrationStepV1toV1 : MigrationStep {

    /**
     * Called for every persisted model when this step runs. Return [original] unchanged to leave the stored model
     * untouched, a modified copy to rewrite its stored JSON, or `null` to delete the model.
     */
    public fun migrateModel(original: MigrationModelV1): MigrationModelV1? = original

    /**
     * Helper for the common case of a renamed property: copies [original] with the `props` key [from] renamed to [to].
     * @throws IllegalStateException if [from] is not a key of `original.props`
     */
    public fun renameKey(original: MigrationModelV1, from: String, to: String): MigrationModelV1 {
        check(from in original.props) { "Could not find any key '$from'" }
        return original.copy(props = JsonObject(original.props.mapKeys { (key, _) -> if (key == from) to else key }))
    }

}

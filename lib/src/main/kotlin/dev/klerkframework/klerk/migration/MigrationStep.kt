package dev.klerkframework.klerk.migration

import kotlinx.datetime.Instant

public data class MigrationModelV1(
    val type: String,
    val id: Int,
    val createdAt: Instant,
    val lastPropsUpdatedAt: Instant,
    val lastTransitionAt: Instant,
    val state: String,
    val props: Map<String, Any>,
)

public interface MigrationStep {
    public val description: String
    public val migratesToVersion: Int
}

public interface MigrationStepV1toV1 : MigrationStep {
    public fun migrateModel(original: MigrationModelV1): MigrationModelV1? = original

    public fun renameKey(original: MigrationModelV1, from: String, to: String): MigrationModelV1 {
        val newProps = original.props.toMutableMap().mapKeys { (key, _) -> if (key == from) to else key }
        check(newProps != original.props) { "Could not find any key '$from'" }
        return original.copy(props = newProps)
    }

}

package dev.klerkframework.klerk

/**
 * A packaged extension that contributes specification (managed models, events, rules, ...) to a host application.
 * [mergeSpecification] should return previous augmented with the plugin's own configuration; [start] is called once
 * after [KlerkMeta.start], and [stop] once during [KlerkMeta.stop].
 */
public interface KlerkPlugin<C : KlerkContext, V> {
    /** A unique name of the plugin. Must not contain spaces. */
    public val name: String
    /** A human-readable description of what the plugin does. */
    public val description: String
    /** Returns [previous] augmented with the plugin's own models, events, rules and jobs. */
    public fun mergeSpecification(previous: Specification<C, V>): Specification<C, V>

    /** Called once after Klerk has started. Use it to kick off whatever background work the plugin needs. */
    public suspend fun start(klerk: Klerk<C, V>)

    /**
     * Called once when Klerk stops, before Klerk shuts down its own machinery and in reverse plugin order.
     * Override it if the plugin has background work to wind down. Must not block for long.
     */
    public fun stop() {}
}

package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.PropertyReadRuleArgs
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.ObjectSchema

/**
 * Applies the `readProperties` authorization rules for one read (i.e. one `klerk.read { }` call).
 *
 * Klerk stores models in a cache and hands out shallow copies of them, which means the [DataContainer] instances in
 * `model.props` are shared between the cache and every reader. Authorization must therefore never be written to those
 * instances. Instead [secure] rebuilds `props` with cloned containers that carry the decision this read made, which is
 * what makes the models it hands out stable snapshots.
 *
 * The rules are evaluated as the model is handed out rather than on first access to a property. A rule may query other
 * models through its `reader`, which is only sound while the read lock is held, so a decision that was postponed would
 * have to be made before the read finishes anyway — a model that outlives the read must not run rules afterwards.
 * Evaluating at once means every rule is called from the `get`/`list` that caused it.
 *
 * Not thread safe — one instance belongs to one read, which runs under the read lock.
 */
internal class PropertyAuthScope<C : KlerkContext, V>(
    private val context: C,
    private val specification: Specification<C, V>,
    private val reader: ReaderWithoutAuth<C, V>,
    private val allowBypass: Boolean,
) {

    private val decisions = HashMap<DecisionKey, Boolean>()
    private var finished = false

    /**
     * Returns a copy of [model] where every [DataContainer], also those in collections and nested objects, carries the
     * decision the rules made for it. The model is returned as-is if it has no containers at all, or if the actor is
     * the system (which is not subject to authorization).
     */
    fun <T : Any> secure(model: Model<T>): Model<T> {
        if (context.actor == SystemIdentity) {
            return model
        }
        check(!finished) { "The reader cannot be used after its read has finished" }
        val securedProps = ObjectSchema.of(model.props::class).transformLeaves(model.props) { leaf ->
            if (leaf is DataContainer<*>) leaf.copyWithAuthorization(isAuthorized(model, leaf), allowBypass) else leaf
        }
        if (securedProps === model.props) {
            return model
        }
        @Suppress("UNCHECKED_CAST")
        return model.copy(props = securedProps as T)
    }

    /**
     * Marks this scope as belonging to a read that has finished, so that a [Reader] that is kept and used after its
     * read block fails loudly instead of reading a cache that has moved on.
     */
    fun finish() {
        finished = true
        decisions.clear()
    }

    /**
     * The decision is remembered per model and property, so a rule is evaluated at most once per property even if the
     * same model is handed out several times during the read.
     *
     * Note that containers are compared with [DataContainer.equals], i.e. by class and value rather than by identity.
     * Two different properties of the same model that have the same container class and the same value therefore share
     * one decision. That is consistent with what a rule can see — [PropertyReadRuleArgs] gives it the container, not
     * the name of the property holding it, so such properties are indistinguishable to it anyway — but a rule that
     * finds the property name by looking for the container in `model.props` by identity would break this assumption.
     */
    private fun isAuthorized(model: Model<out Any>, property: DataContainer<*>): Boolean =
        decisions.getOrPut(DecisionKey(model.id.value, property)) {
            isReadPropertyAuthorized(PropertyReadRuleArgs(property, model, context, reader), specification)
        }

    private data class DecisionKey(val modelId: Int, val property: DataContainer<*>)
}

/**
 * Params may hold containers taken from a read result. Their restrictions belong to that read and must not end up in
 * the model cache.
 */
internal fun <T : Any, P> withoutReadRestrictions(command: Command<T, P>): Command<T, P> {
    val params: Any = command.params ?: return command
    val cleaned = ObjectSchema.of(params::class).transformLeaves(params) { leaf ->
        if (leaf is DataContainer<*>) leaf.withoutReadRestrictions() else leaf
    }
    @Suppress("UNCHECKED_CAST")
    return if (cleaned === params) command else command.copy(params = cleaned as P)
}

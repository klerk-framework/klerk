package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.ArgsForPropertyAuth
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

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
) {

    private val decisions = HashMap<DecisionKey, Boolean>()
    private var finished = false

    /**
     * Returns a copy of [model] where every [DataContainer] carries the decision the rules made for it. The model is
     * returned as-is if it has no containers at all, or if the actor is the system (which is not subject to
     * authorization).
     */
    fun <T : Any> secure(model: Model<T>): Model<T> {
        if (context.actor == SystemIdentity) {
            return model
        }
        check(!finished) { "The reader cannot be used after its read has finished" }
        val securedProps = secureValue(model.props, model)
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
     * one decision. That is consistent with what a rule can see — [ArgsForPropertyAuth] gives it the container, not the
     * name of the property holding it, so such properties are indistinguishable to it anyway — but a rule that finds
     * the property name by looking for the container in `model.props` by identity would break this assumption.
     */
    private fun isAuthorized(model: Model<out Any>, property: DataContainer<*>): Boolean =
        decisions.getOrPut(DecisionKey(model.id.value, property)) {
            isReadPropertyAuthorized(ArgsForPropertyAuth(property, model, context, reader), specification)
        }

    private fun secureValue(value: Any?, model: Model<out Any>): Any? = when (value) {
        null -> null
        is DataContainer<*> -> value.copyWithAuthorization(isAuthorized(model, value))
        is ModelID<*> -> value
        is Set<*> -> secureElements(value, model)?.toSet() ?: value
        is List<*> -> secureElements(value, model) ?: value
        else -> secureNestedObject(value, model)
    }

    /** Returns null if no element needed securing, so that the original collection can be kept. */
    private fun secureElements(values: Collection<*>, model: Model<out Any>): List<Any?>? {
        var changed = false
        val secured = values.map { element ->
            val securedElement = secureValue(element, model)
            if (securedElement !== element) {
                changed = true
            }
            securedElement
        }
        return if (changed) secured else null
    }

    /**
     * Containers may be nested inside plain objects (typically data classes) that are properties of `props`. Such an
     * object is rebuilt through its primary constructor if — and only if — one of its properties changed. Objects that
     * cannot be rebuilt that way are left untouched, which means containers nested inside them are not authorized;
     * that was the behaviour before this scope existed as well.
     */
    private fun secureNestedObject(value: Any, model: Model<out Any>): Any {
        @Suppress("UNCHECKED_CAST")
        val plan = rebuildPlanFor(value::class as KClass<Any>) ?: return value
        var changed = false
        val arguments = HashMap<KParameter, Any?>(plan.parameters.size)
        plan.parameters.forEach { binding ->
            val current = binding.property.get(value)
            val secured = secureValue(current, model)
            if (secured !== current) {
                changed = true
            }
            arguments[binding.parameter] = secured
        }
        return if (changed) plan.constructor.callBy(arguments) else value
    }

    private data class DecisionKey(val modelId: Int, val property: DataContainer<*>)

    private class ParameterBinding(val parameter: KParameter, val property: KProperty1<Any, *>)

    private class RebuildPlan(val constructor: KFunction<Any>, val parameters: List<ParameterBinding>)

    /** A null [plan] means that instances of the class must be left as they are. */
    private class CachedPlan(val plan: RebuildPlan?)

    private companion object {

        /**
         * Reflecting over a class is expensive, so the result is remembered. A plan only depends on the class, not on
         * the values, and is therefore safe to share between reads.
         */
        private val rebuildPlans = ConcurrentHashMap<KClass<*>, CachedPlan>()

        private fun rebuildPlanFor(kClass: KClass<Any>): RebuildPlan? {
            if (isLeaf(kClass)) {
                return null
            }
            return rebuildPlans.computeIfAbsent(kClass) { CachedPlan(createRebuildPlan(kClass)) }.plan
        }

        /**
         * A cheap way to avoid reflecting over the types that can never contain a [DataContainer]. Without this, every
         * String and every Int in a props class would be sent through kotlin-reflect.
         */
        private fun isLeaf(kClass: KClass<*>): Boolean {
            if (kClass.java.isEnum || kClass.java.isPrimitive || kClass.java.isArray) {
                return true
            }
            val name = kClass.java.name
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("kotlin.") ||
                    name.startsWith("kotlinx.")
        }

        private fun createRebuildPlan(kClass: KClass<Any>): RebuildPlan? {
            val plan = try {
                val constructor = kClass.primaryConstructor ?: return notRebuildable(kClass)
                val properties = kClass.memberProperties.associateBy { it.name }
                val bindings = constructor.parameters.map { parameter ->
                    val property = properties[parameter.name] ?: return notRebuildable(kClass)
                    if (property.visibility != KVisibility.PUBLIC) {
                        return notRebuildable(kClass)
                    }
                    ParameterBinding(parameter, property)
                }
                RebuildPlan(constructor, bindings)
            } catch (e: Throwable) {
                // kotlin-reflect cannot describe every class (e.g. synthetic classes or classes compiled from Java)
                return notRebuildable(kClass)
            }
            return plan
        }

        /**
         * Klerk can only apply the property authorization to containers it can reach, and reaching them means being
         * able to build a copy of the object they sit in. This should not happen for a props class, so it is worth
         * telling the developer about. Plans are cached, so this is logged at most once per class.
         */
        private fun notRebuildable(kClass: KClass<Any>): RebuildPlan? {
            logger.warn {
                "${kClass.qualifiedName} cannot be copied by Klerk (it has no primary constructor, or a constructor " +
                        "parameter that is not a public property). Any DataContainer inside it will not have the " +
                        "readProperties authorization rules applied to it."
            }
            return null
        }
    }
}

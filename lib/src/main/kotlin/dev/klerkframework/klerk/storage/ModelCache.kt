package dev.klerkframework.klerk.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.read.ReadResult
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.storage.ModelCache.persistence
import dev.klerkframework.klerk.misc.envInt
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyKey
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * How much model data Klerk keeps in memory. See docs/performance.md.
 *
 * @property maxResidentModels the largest number of model bodies held in memory at once. Beyond this, the least
 * valuable are evicted and re-read from [Persistence] when they are next needed. The default is 10 million.
 */
public data class ModelCacheSettings(
    val maxResidentModels: Int = 10_000_000,
) {
    init {
        if (maxResidentModels < 1000) {
            logger.warn { "maxResidentModels should be at least 1000, was $maxResidentModels" }
        }
    }

    public companion object {
        /**
         * Builds a [ModelCacheSettings] from environment variables, falling back to the regular default for any
         * variable that is unset. Variable names are [prefix] plus the property name in `SCREAMING_SNAKE_CASE`,
         * e.g. [maxResidentModels] from `KLERK_MODEL_CACHE_MAX_RESIDENT_MODELS`.
         */
        public fun fromEnvVars(prefix: String = "KLERK_MODEL_CACHE_"): ModelCacheSettings {
            val defaults = ModelCacheSettings()
            return ModelCacheSettings(
                maxResidentModels = envInt("${prefix}MAX_RESIDENT_MODELS") ?: defaults.maxResidentModels,
            )
        }
    }
}

internal object ModelCache {

    /** The number of models that exist, whether or not their bodies are in memory. */
    internal val count: Int
        get() = ids.size

    /** The number of model bodies currently in memory. Never larger than [count]. */
    internal val residentCount: Long
        get() = bodies.estimatedSize()

    /** Whether this model's body is in memory right now. For tests that need to act on an actual cache miss. */
    internal fun isResident(id: Int): Boolean = bodies.asMap().containsKey(id)

    private val log = KotlinLogging.logger {}

    /**
     * Every id that exists. Always complete — eviction removes bodies, never ids — so this is what tells "no such
     * model" apart from "not in memory right now".
     *
     * Mutated only under the write lock, but concurrent because it is read by concurrent readers.
     */
    private val ids: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * Which models refer to a given model. Always resident: it holds nothing but ids, and correctness elsewhere
     * depends on it being complete. Only accessed while holding the ReadWriteLock, and only mutated under its write
     * side, so a plain HashMap is safe.
     */
    private val relationsTo: MutableMap<Int, MutableSet<Int>> = HashMap()

    /**
     * The evictable part. A miss is repaired from [persistence] by the reader that hit it; Caffeine makes that
     * single-flight per key, so concurrent readers of the same missing id share one fetch.
     */
    private var bodies: Cache<Int, Model<out Any>> = buildCache(ModelCacheSettings())

    private lateinit var persistence: Persistence

    /**
     * The pre-commit body of every model the commit in flight touches, held strongly so it cannot be evicted; see
     * [beginCommit].
     *
     * While a commit is in flight, storage may already hold the new state while [bodies] still holds the old one, so
     * a miss on one of these ids must not be repaired from storage — it would return the new value while every other
     * model in the same read still shows the old one. This map is what such a miss is answered from instead, which
     * keeps the whole window serving one consistent (old) version.
     *
     * Commits are serialized by [dev.klerkframework.klerk.EventsManagerImpl], so this only ever holds one commit's
     * worth of models.
     */
    private val preCommitBodies: MutableMap<Int, Model<out Any>> = ConcurrentHashMap()

    /**
     * Points the cache at the storage it reloads evicted models from, and sizes it. Must be called before any read.
     */
    internal fun initialize(persistence: Persistence, settings: ModelCacheSettings) {
        this.persistence = persistence
        this.bodies = buildCache(settings)
    }

    private fun buildCache(settings: ModelCacheSettings): Cache<Int, Model<out Any>> =
        Caffeine.newBuilder()
            .maximumSize(settings.maxResidentModels.toLong())
            .build()

    internal fun initMetrics(registry: MeterRegistry) {
        Gauge.builder("klerk.models.count") { count }
            .description("The current number of models")
            .baseUnit("models")
            .register(registry)
        Gauge.builder("klerk.models.resident") { residentCount }
            .description("The number of model bodies currently held in memory")
            .baseUnit("models")
            .register(registry)
    }

    /**
     * The model body, reading it from persistence if it is not resident. Returns null only if the model does not
     * exist — the id set is checked first, so a miss never turns into a pointless storage lookup.
     */
    private fun getBody(id: Int): Model<out Any>? {
        if (!ids.contains(id)) {
            return null
        }
        // Answered before consulting storage: a model the commit in flight touches may already be written there, so
        // repairing a miss from storage would mix its new state into a read that sees every other model as it was
        // before the commit. See [preCommitBodies].
        preCommitBodies[id]?.let { return it }
        // computeIfAbsent rather than a get/put pair: it is atomic per key, so concurrent readers that miss the same id
        // share one fetch instead of all going to storage. A null from the loader records no mapping, which is what
        // makes "deleted while a reader was looking for it" an ordinary miss rather than an error.
        return bodies.asMap().computeIfAbsent(id) { persistence.readModel(it) }
    }

    internal fun <T : Any> read(id: ModelID<T>): ReadResult<T> {
        val model = getBody(id.value)
            ?: return ReadResult.Fail(NotFoundProblem("Could not find item with id=${id.value}"))
        @Suppress("UNCHECKED_CAST")
        return ReadResult.Ok(model.copy() as Model<T>)
    }

    internal fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        @Suppress("UNCHECKED_CAST")
        return getBody(id.value)?.copy() as? Model<T>
    }

    internal fun <T : Any> store(model: Model<T>): Unit {
        updateRelations(model, relationsTo, true)
        ids.add(model.id.value)
        bodies.put(model.id.value, model.copy())
    }

    /**
     * This is used at startup when reading all models.
     */
    internal fun storeFromPersistence(model: Model<out Any>) {
        ids.add(model.id.value)
        bodies.put(model.id.value, model)
        // Relations are built as models arrive rather than in a pass afterwards, which would have to read every body
        // again -- and with eviction most of them would no longer be resident by then.
        updateRelations(model, relationsTo, klerkHasStarted = false)
    }

    /**
     * Pins the current (pre-commit) body of every model in [modelIds]. Persistence is written without the write lock
     * held, so until [endCommit] a read that misses on one of these ids is answered from the pinned body rather than
     * from storage, which may already hold the new state.
     *
     * Call before writing to persistence, and pair with [endCommit] — including on failure, or the pinned bodies
     * shadow the real ones indefinitely.
     */
    internal fun beginCommit(modelIds: Collection<ModelID<out Any>>) {
        modelIds.forEach { id ->
            // getBody, not bodies[..], so an already-evicted model is fetched from storage -- which is still the
            // pre-commit state, since this runs before persistence is written.
            getBody(id.value)?.let { preCommitBodies[id.value] = it }
        }
    }

    /**
     * Unpins the bodies [beginCommit] pinned, which is what makes the commit visible to readers.
     *
     * Must be called while holding the write lock, in the same critical section that applied the commit to the cache:
     * until this runs, reads are answered with the pinned pre-commit bodies, so releasing the lock first would expose
     * a window where views and relations already reflect the commit but the bodies do not.
     *
     * Idempotent, so it is safe to call again on a failure path.
     */
    internal fun endCommit() {
        preCommitBodies.clear()
    }

    internal fun <T : Any> delete(modelId: ModelID<T>): Unit {
        relationsTo.forEach { (_, relationSet) -> relationSet.remove(modelId.value) }
        relationsTo.remove(modelId.value)
        ids.remove(modelId.value)
        bodies.invalidate(modelId.value)
    }

    /**
     * Finds all models that have a relation to the specified model.
     */
    internal fun getAllRelated(id: ModelID<*>): Set<ModelID<*>> {
        val relations = relationsTo[id.value] ?: emptySet()
        return relations.map { ModelID<Any>(it) }.toSet()
    }

    internal fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> {
        return getAllRelated(id).map {
            val model = getBody(it.value) ?: return@map null
            if (model.props::class == clazz) {
                @Suppress("UNCHECKED_CAST")
                return@map model.copy() as Model<T>
            }
            return@map null
        }.filterNotNull().toSet()
    }

    internal fun <T : Any, U : Any> getRelated(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>
    ): Set<Model<T>> {
        if (!ids.contains(id.value)) throw NoSuchElementException("Could not find model with id $id")
        return relatedThrough(PropertyKey.of(property), id)
    }

    internal fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>
    ): Set<Model<T>> = relatedThrough(PropertyKey.of(property), id)

    /** The models that refer to [id] through the property [key], wherever in their props it is. */
    private fun <T : Any> relatedThrough(key: PropertyKey, id: ModelID<*>): Set<Model<T>> =
        getAllRelated(id).mapNotNull { relatedId ->
            val related = getBody(relatedId.value)?.copy() ?: return@mapNotNull null
            var refers = false
            ObjectSchema.of(related.props::class).forEachLeaf(related.props) { leaf ->
                if (leaf.field.key == key && leaf.value == id) {
                    refers = true
                }
            }
            @Suppress("UNCHECKED_CAST")
            if (refers) related as Model<T> else null
        }.toSet()

    /**
     * Calculates relations for the model and updates the provided relationsMap
     */
    private fun <T : Any> updateRelations(
        model: Model<T>,
        relationsMap: MutableMap<Int, MutableSet<Int>>,
        klerkHasStarted: Boolean
    ) {
        // optimization: do this before write lock
        val fromId = model.id.value

        // we don't have to do this for new models. Note that this asks the id set rather than the body cache, so an
        // evicted model still counts as existing.
        if (klerkHasStarted && ids.contains(fromId)) {
            // Simple (and inefficient?) algorithm: first remove all relations for this model, then create new relations for this model
            relationsMap.forEach { (_, relationSet) -> relationSet.remove(fromId) }
        }

        ObjectSchema.of(model.props::class).forEachLeaf(model.props) { leaf ->
            (leaf.value as? ModelID<*>)?.let { createReference(fromId, it.value, relationsMap) }
        }
    }

    private fun createReference(fromId: Int, toId: Int, relationsMap: MutableMap<Int, MutableSet<Int>>) {
        var relationSet = relationsMap[toId]
        if (relationSet == null) {
            relationSet = mutableSetOf()
        }
        relationSet.add(fromId)
        relationsMap[toId] = relationSet
    }

    fun isEmpty(): Boolean = ids.isEmpty()

    fun isIdAvailable(uInt: Int): Boolean {
        return !ids.contains(uInt)
    }

    fun clear() {
        ids.clear()
        bodies.invalidateAll()
        relationsTo.clear()
    }

    fun <T : Any, C : KlerkContext, V> handleDelta(delta: ProcessingData<T, C, V>) {
        delta.deletedModels.forEach { modelId -> delete(modelId) }
        delta.createdModels
            .union(delta.updatedModels)
            .union(delta.transitions)
            .forEach { modelId ->
                store(requireNotNull(delta.aggregatedModelState[modelId]))
            }
    }

    /**
     * The id of every model that exists.
     * @param reader is not used but must be provided to prove that there will be no concurrent modification
     */
    internal fun allIds(reader: ModelReader<*, *>): Set<Int> = ids.toSet()

}

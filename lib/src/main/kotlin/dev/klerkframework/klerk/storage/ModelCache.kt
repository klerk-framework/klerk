package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.read.ReadResult
import dev.klerkframework.klerk.read.Reader
import io.ktor.util.collections.*
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties

internal object ModelCache {

    internal val count: Int
        get() = models.count()

    private val log = KotlinLogging.logger {}

    // These (models and relationsTo) are only accessed using a ReadWriteLock which prevents concurrent modification
    // and gives us a happens-before guarantee
    private val models: MutableMap<Int, Model<out Any>> = HashMap()
    private val relationsTo: MutableMap<Int, MutableSet<Int>> = HashMap()

    internal fun initMetrics(registry: MeterRegistry) {
        Gauge.builder("klerk.models.count", models::size)
            .description("The current number of models")
            .baseUnit("models")
            .register(registry)
    }

    internal fun <T : Any> read(id: ModelID<T>): ReadResult<T> {
        val model = models[id.toInt()]
        return if (model == null) {
            ReadResult.Fail(NotFoundProblem("Could not find item with id=${id.toInt()}"))
        } else {
            @Suppress("UNCHECKED_CAST")
            ReadResult.Ok(model.copy() as Model<T>)
        }
    }

    internal fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        @Suppress("UNCHECKED_CAST")
        return models[id.toInt()]?.copy() as? Model<T>
    }

    internal fun <T : Any> store(model: Model<T>): Unit {
        updateRelations(model, relationsTo, true)
        models[model.id.toInt()] = model.copy()
    }

    /**
     * This is used at startup when reading all models. Note that relations are not calculated here.
     */
    internal fun storeFromPersistence(model: Model<out Any>) {
        models[model.id.toInt()] = model
    }

    internal fun <T : Any> delete(modelId: ModelID<T>): Unit {
        relationsTo.forEach { (_, relationSet) -> relationSet.remove(modelId.toInt()) }
        relationsTo.remove(modelId.toInt())
        models.remove(modelId.toInt())
    }

    /**
     * Finds all models that have a relation to the specified model.
     */
    internal fun getAllRelated(id: ModelID<*>): Set<ModelID<*>> {
        val relations = relationsTo[id.toInt()] ?: emptySet()
        return relations.map { ModelID<Any>(it) }.toSet()
    }

    internal fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> {
        return getAllRelated(id).map {
            val model = models[it.toInt()]
            if (model!!.props::class.qualifiedName!! == clazz.qualifiedName) {
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
        val result = mutableSetOf<Model<T>>()
        if (models[id.toInt()] == null) throw NoSuchElementException("Could not find model with id $id")
        getAllRelated(id).forEach { relatedId ->
            try {
                val related =
                    models[relatedId.toInt()]?.copy()
                        ?: throw NoSuchElementException("Could not find model with id $relatedId")
                val relatedProperty =
                    related.props::class.memberProperties.firstOrNull { it == property } ?: return@forEach
                val relatedPropertyValue = relatedProperty.getter.call(related.props) ?: return@forEach

                val isTypeModelID =
                    relatedProperty.returnType.toString().startsWith(ModelID::class.qualifiedName!!, false)
                @Suppress("UNCHECKED_CAST")
                if (isTypeModelID && ((relatedPropertyValue as ModelID<Any>) == id)) {
                    @Suppress("UNCHECKED_CAST")
                    result.add(related as Model<T>)
                }
            } catch (e: Exception) {
                log.error("Could not getRelated", e)
            }
        }
        return result
    }

    internal fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>
    ): Set<Model<T>> {
        val result = mutableSetOf<Model<T>>()
        getAllRelated(id).forEach { relatedId ->
            try {
                val related =
                    models[relatedId.toInt()]?.copy()
                        ?: throw NoSuchElementException("Could not find model with id $relatedId")
                val relatedProperty =
                    related.props::class.memberProperties.firstOrNull { it == property } ?: return@forEach
                val relatedPropertyValue = relatedProperty.getter.call(related.props) ?: return@forEach

                val isTypeModelID =
                    relatedProperty.returnType.toString().startsWith(ModelID::class.qualifiedName!!, false)

                // is it a collection of ModelIds?
                val anyType = KTypeProjection(KVariance.OUT, Any::class.createType())
                val modelIDType = KTypeProjection(
                    KVariance.INVARIANT,
                    ModelID::class.createType(arguments = listOf(anyType))
                )
                val isTypeCollectionOfModelId =
                    relatedProperty.returnType.isSubtypeOf(Collection::class.createType(arguments = listOf(modelIDType)))
                @Suppress("UNCHECKED_CAST")
                if (isTypeCollectionOfModelId && (relatedPropertyValue as Collection<ModelID<out Any>>).contains(
                        id
                    )
                ) {
                    result.add(related as Model<T>)
                }
            } catch (e: Exception) {
                log.error("Could not getRelated", e)
            }
        }
        return result
    }

    /**
     * Calculates relations for the model and updates the provided relationsMap
     */
    private fun <T : Any> updateRelations(
        model: Model<T>,
        relationsMap: MutableMap<Int, MutableSet<Int>>,
        klerkHasStarted: Boolean
    ) {
        // optimization: do this before write lock
        val fromId = model.id.toInt()

        if (klerkHasStarted && models[model.id.toInt()] != null) {   // we don't have to do this for new models
            // Simple (and inefficient?) algorithm: first remove all relations for this model, then create new relations for this model
            relationsMap.forEach { (_, relationSet) -> relationSet.remove(fromId) }
        }

        model.props::class.memberProperties.forEach { property ->
            if (property.returnType.toString().startsWith(ModelID::class.qualifiedName!!, false)) {
                val propValue = property.getter.call(model.props) ?: return@forEach
                val id = (propValue as ModelID<*>).toInt()
                createReference(fromId, id, relationsMap)
            } else {
                // is there a reference in a collection?
                try {
                    val AnyType = KTypeProjection(KVariance.OUT, Any::class.createType())
                    val modelIDType = KTypeProjection(
                        KVariance.INVARIANT,
                        ModelID::class.createType(arguments = listOf(AnyType))
                    )
                    if (property.returnType.isSubtypeOf(Collection::class.createType(arguments = listOf(modelIDType)))) {
                        val collection = property.getter.call(model.props) ?: return@forEach
                        @Suppress("UNCHECKED_CAST")
                        (collection as Collection<ModelID<out Any>>).forEach {
                            createReference(fromId, it.toInt(), relationsMap)
                        }
                    }
                } catch (e: Exception) {
                    log.error(e) { "Could not find out if there is a reference to the model" }
                }
            }
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

    fun isEmpty(): Boolean = models.isEmpty()

    fun isIdAvailable(uInt: Int): Boolean {
        return !models.containsKey(uInt)
    }

    fun clear() {
        models.clear()
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
     * Calculates the relations. This must be done when models have been added using #storeFromPersistence.
     */
    internal fun initRelations() {
        val concurrentMap = ConcurrentMap<Int, MutableSet<Int>>()
        models.values.parallelStream().forEach { model ->
            updateRelations(model, concurrentMap, false)
        }
        relationsTo.clear()
        relationsTo.putAll(concurrentMap)
    }

    /**
     * Returns all models (no copy).
     * @param reader is not used but must be provided to prove that there will be no concurrent modification
     */
    internal fun getAll(reader: Reader<*, *>): Map<Int, Model<out Any>> {
        return models
    }


}

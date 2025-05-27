package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

internal class ReaderWithAuth<C : KlerkContext, V>(
    val klerk: KlerkImpl<C, V>,
    val context: C,
) : Reader<C, V> {

    private val withoutAuth = ReaderWithoutAuth(klerk)

    override val data = klerk.config.collections

    internal val modelsRead = mutableSetOf<Model<*>>()

    override fun getAllRelatedIds(id: ModelID<*>): Set<ModelID<*>> = withoutAuth.getAllRelatedIds(id)

    override fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>,): Set<Model<T>> =
        withoutAuth.getRelated(clazz, id).map { checkAuth(it) }.toSet()

    override fun <T : Any, U : Any> getRelated(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = withoutAuth.getRelated(property, id).map { checkAuth(it) }.toSet()

    override fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = withoutAuth.getRelatedInCollection(property, id).map { checkAuth(it) }.toSet()

    override fun <T : Any> get(id: ModelID<T>): Model<T> = checkAuth(withoutAuth.get(id)).also { modelsRead.add(it) }

    override fun <T : Any> getFirstWhere(
        collection: ModelCollection<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T> = checkAuth(withoutAuth.getFirstWhere(collection, filter))

    override fun <T : Any> listIfAuthorized(
        collection: ModelCollection<T, C>,
    ): List<Model<T>> {
        return withoutAuth.list(collection).filter { isAuthorized(it, context, klerk.config, withoutAuth) }
    }

    override fun <T : Any> list(
        modelCollection: ModelCollection<T, C>,
        filter: ((Model<T>) -> Boolean)?
    ): List<Model<T>> {
        val result = withoutAuth.list(modelCollection, filter).map { checkAuth(it) }
        result.forEach { modelsRead.add(it) }
        return result
    }

    override fun <T : Any> query(
        collection: ModelCollection<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> {
        val result = withoutAuth.query(collection, options, filter)
        return result.copy(items = result.items.map { checkAuth(it) })
    }

    override fun <T : Any> getOrNull(id: ModelID<T>, ): Model<T>? =
        withoutAuth.getOrNull(id)?.let { checkAuth(it) }

    override fun <T : Any> firstOrNull(
        collection: ModelCollection<T, C>,
        filter: (Model<T>) -> Boolean
    ): Model<T>? = withoutAuth.firstOrNull(collection, filter)?.let { checkAuth(it) }

    override fun <T : Any> getIfAuthorizedOrNull(id: ModelID<T>): Model<T>? =
        withoutAuth.get(id).let { if (isAuthorized(it, context, klerk.config, withoutAuth)) it else null }



    private fun <T : Any> checkAuth(model: Model<T>): Model<T> {
        if (context.actor == SystemIdentity) {
            return model
        }
        initPropertyAuthorization(model, context, withoutAuth, klerk.config)
        when (val result = evaluateAuthorization(context, model, klerk.config, withoutAuth)) {
            is ReadResult.Fail -> throw result.problem.asException()
            is ReadResult.Ok -> return model
        }
    }

    override fun <T : Any> getPossibleVoidEvents(clazz: KClass<T>): Set<EventReference> =
        klerk.config.getPossibleVoidEvents(clazz, context)
            .filter { klerk.validator.validateWithoutParameters<T>(it, context, null, withoutAuth) }
            .toSet()

    override fun <T : Any> getPossibleEvents(id: ModelID<T>): Set<EventReference> {
        val model = get(id)
        return klerk.config.getStateMachine(model).getAvailableExternalEventsForModel(model, context)
            .filter { klerk.validator.validateWithoutParameters(it, context, model, withoutAuth) }
            .toSet()
    }

}

internal fun <T:Any, C:KlerkContext, V> isAuthorized(model: Model<T>, context: C, config: Config<C, V>, reader: ReaderWithoutAuth<C, V>): Boolean =
    evaluateAuthorization(context, model, config, reader) is ReadResult.Ok

internal fun <T : Any, C:KlerkContext, V> evaluateAuthorization(
    context: C,
    model: Model<T>,
    config: Config<C, V>,
    reader: ReaderWithoutAuth<C, V>
): ReadResult<T> {
    if (context.actor == SystemIdentity) {
        return ReadResult.Ok(model)
    }
    val brokenRule = config.authorization.readModelNegativeRules
        .firstOrNull { it(ArgModelContextReader(model, context, reader)) == NegativeAuthorization.Deny }

    if (brokenRule != null) {
        return ReadResult.Fail(AuthorizationProblem(context.translation.klerk.unauthorized,
            RuleDescription(brokenRule, RuleType.Authorization)))
    }

    if (config.authorization.readModelPositiveRules.map { it(ArgModelContextReader(model, context, reader)) }
            .none { it == PositiveAuthorization.Allow }) {
        logger.info("No policy explicitly allowed the request")
        return ReadResult.Fail(AuthorizationProblem(context.translation.klerk.unauthorized, null))
    }
    return ReadResult.Ok(model)
}

package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.reflect.KProperty1

internal class ReaderWithAuth<C : KlerkContext, V>(
    val klerk: KlerkImpl<C, V>,
    val context: C,
) : Reader<C, V>, ViewReader<C, V> {

    internal val withoutAuth = ReaderWithoutAuth(klerk)

    private val propertyAuth = PropertyAuthScope(context, klerk.spec, withoutAuth, klerk.settings.allowBypassAuthRead)

    override val views = klerk.spec.views

    private val jobReader = AuthorizingJobReader(klerk, context, withoutAuth)

    override val jobs: JobReader get() = jobReader

    private val attachedDataReader = AuthorizingAttachedDataReader(klerk, context)

    override val attachedData: AttachedDataReader get() = attachedDataReader

    override fun eventLog(
        id: ModelID<out Any>?,
        after: Instant,
        before: Instant,
        sequenceNumber: Long?,
    ): EventLogQuery {
        checkEventLogAuthorization(klerk, context, withoutAuth)
        return eventLogQuery(klerk, id, after, before, sequenceNumber)
    }

    internal val modelsRead = mutableSetOf<Model<*>>()

    override fun getAllRelatedIds(id: ModelID<*>): Set<ModelID<*>> = withoutAuth.getAllRelatedIds(id)

    override fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> =
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

    // Reads through the unauthorized reader so that an unreadable model can be dropped instead of throwing, which is
    // the point of the plain (non-OrThrow) view reads. Lazy: only what the caller consumes is read.
    override fun <T : Any> sequence(collection: ModelView<T, C>): Sequence<Model<T>> =
        collection.withReader(withoutAuth)
            .filter { isAuthorized(it, context, klerk.spec, withoutAuth) }
            .map { propertyAuth.secure(it) }

    override fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> =
        // The authorization check goes into the same pass that cuts the page, so pages stay full and the cursors
        // describe what the actor can actually see. It also means `filter` never sees a model the actor may not read.
        withoutAuth.queryInternal(collection, options, filter) { model ->
            model.takeIf { isAuthorized(it, context, klerk.spec, withoutAuth) }?.let { propertyAuth.secure(it) }
        }

    override fun <T : Any> queryOrThrow(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> {
        val result = withoutAuth.query(collection, options, filter)
        return result.copy(items = result.items.map { checkAuth(it) })
    }

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        val model = withoutAuth.getOrNull(id) ?: return null
        if (context.actor == SystemIdentity) {
            return model
        }
        return if (isAuthorized(model, context, klerk.spec, withoutAuth)) propertyAuth.secure(model) else null
    }


    private fun <T : Any> checkAuth(model: Model<T>): Model<T> {
        if (context.actor == SystemIdentity) {
            return model
        }
        // The readModels rules see the model as it is in the cache, not a secured copy.
        when (val result = evaluateAuthorization(context, model, klerk.spec, withoutAuth)) {
            is ReadResult.Fail -> throw result.problem.asException()
            is ReadResult.Ok -> return propertyAuth.secure(model)
        }
    }

    /**
     * Marks this reader as spent, so that using it after its read block (i.e. without the read lock) fails loudly
     * rather than reading a cache that may have changed. The models the reader has handed out are unaffected — their
     * property authorization was decided when they were handed out.
     */
    internal fun finishRead() {
        propertyAuth.finish()
        jobReader.finish()
        attachedDataReader.finish()
    }

    override fun <T : Any> getPossibleVoidEvents(clazz: KClass<T>, visibility: EventVisibility): Set<EventReference> =
        klerk.spec.getPossibleVoidEvents(clazz, context, visibility)
            .filter { klerk.validator.validateWithoutParameters<T>(it, context, null, withoutAuth) }
            .toSet()

    override fun <T : Any> getPossibleEvents(id: ModelID<T>, visibility: EventVisibility): Set<EventReference> {
        val model = get(id)
        return klerk.spec.getStateMachine(model).getAvailableEventsForModel(model, context, visibility)
            .filter { klerk.validator.validateWithoutParameters(it, context, model, withoutAuth) }
            .toSet()
    }

}

internal fun <T : Any, C : KlerkContext, V> isAuthorized(
    model: Model<T>,
    context: C,
    specification: Specification<C, V>,
    reader: ReaderWithoutAuth<C, V>
): Boolean =
    evaluateAuthorization(context, model, specification, reader) is ReadResult.Ok

internal fun <T : Any, C : KlerkContext, V> evaluateAuthorization(
    context: C,
    model: Model<T>,
    specification: Specification<C, V>,
    reader: ReaderWithoutAuth<C, V>
): ReadResult<T> {
    if (context.actor == SystemIdentity) {
        return ReadResult.Ok(model)
    }
    val brokenRule = specification.authorization.readModelNegativeRules
        .firstOrNull { it(ArgModelContextReader(model, context, reader)) == NegativeAuthorization.Deny }

    if (brokenRule != null) {
        return ReadResult.Fail(
            AuthorizationProblem(
                context.translation.klerk.unauthorized,
                RuleDescription(brokenRule, RuleType.Authorization), KlerkErrorCode.ReadNegativeAuthorizationExist
            )
        )
    }

    if (specification.authorization.readModelPositiveRules.map { it(ArgModelContextReader(model, context, reader)) }
            .none { it == PositiveAuthorization.Allow }) {
        logger.info("No policy explicitly allowed the request")
        return ReadResult.Fail(
            AuthorizationProblem(
                context.translation.klerk.unauthorized,
                null,
                KlerkErrorCode.ReadPositiveAuthorizationMissing
            )
        )
    }
    return ReadResult.Ok(model)
}

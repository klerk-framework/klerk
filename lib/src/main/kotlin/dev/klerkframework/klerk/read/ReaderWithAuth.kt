package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.AttachedDataReader
import dev.klerkframework.klerk.AuthorizationProblem
import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.InstanceEvent
import dev.klerkframework.klerk.JobReader
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.KlerkImpl
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.ModelReadRuleArgs
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.PendingRead
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.RuleDescription
import dev.klerkframework.klerk.RuleType
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.VoidEvent
import dev.klerkframework.klerk.logger
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.time.Instant

internal class ReaderWithAuth<C : KlerkContext, V>(val klerk: KlerkImpl<C, V>, val context: C) :
    Reader<C, V>,
    ViewReader<C, V> {

    internal val withoutAuth = ReaderWithoutAuth(klerk)

    private val propertyAuth =
        PropertyAuthScope(context, klerk.specification, withoutAuth, klerk.settings.allowBypassAuthRead)

    override val views = klerk.specification.views

    private val jobReader = AuthorizingJobReader(klerk, context, withoutAuth)

    override val jobs: JobReader get() = jobReader

    private val attachedDataReader = AuthorizingAttachedDataReader(klerk, context)

    override val attachedData: AttachedDataReader get() = attachedDataReader

    override fun eventLog(id: ModelID<out Any>?, after: Instant, before: Instant): PendingRead<List<EventLogEntry>> {
        checkEventLogAuthorization(klerk, context, withoutAuth)
        return eventLogQuery(klerk, id, after, before)
    }

    override fun eventLogEntry(sequenceNumber: Long): PendingRead<EventLogEntry?> {
        checkEventLogAuthorization(klerk, context, withoutAuth)
        return eventLogEntryQuery(klerk, sequenceNumber)
    }

    internal val modelsRead = mutableSetOf<Model<*>>()

    override fun referencingIds(id: ModelID<*>): Set<ModelID<*>> = withoutAuth.referencingIds(id)

    override fun <T : Any> referencing(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> =
        withoutAuth.referencing(clazz, id).readable()

    override fun <T : Any, U : Any> referencing(property: KProperty1<T, ModelID<U>?>, id: ModelID<*>): Set<Model<T>> =
        withoutAuth.referencing(property, id).readable()

    override fun <T : Any, U : Any> referencingInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = withoutAuth.referencingInCollection(property, id).readable()

    private fun <T : Any> Set<Model<T>>.readable(): Set<Model<T>> =
        filter { context.actor == SystemIdentity || isAuthorized(it, context, klerk.specification, withoutAuth) }
            .map { propertyAuth.secure(it) }
            .toSet()

    override fun <T : Any> get(id: ModelID<T>): Model<T> = checkAuth(withoutAuth.get(id)).also { modelsRead.add(it) }

    // Reads through the unauthorized reader so that an unreadable model can be dropped instead of throwing, which is
    // the point of the plain (non-OrThrow) view reads. Lazy: only what the caller consumes is read.
    override fun <T : Any> sequence(collection: ModelView<T, C>): Sequence<Model<T>> =
        collection.withReader(withoutAuth)
            .filter { isAuthorized(it, context, klerk.specification, withoutAuth) }
            .map { propertyAuth.secure(it) }

    override fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?,
    ): QueryResponse<T> =
        // The authorization check goes into the same pass that cuts the page, so pages stay full and the cursors
        // describe what the actor can actually see. It also means `filter` never sees a model the actor may not read.
        withoutAuth.queryInternal(collection, options, filter) { model ->
            model
                .takeIf { isAuthorized(it, context, klerk.specification, withoutAuth) }
                ?.let { propertyAuth.secure(it) }
        }

    override fun <T : Any> queryOrThrow(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?,
    ): QueryResponse<T> {
        val result = withoutAuth.query(collection, options, filter)
        return result.copy(items = result.items.map { checkAuth(it) })
    }

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        val model = withoutAuth.getOrNull(id) ?: return null
        if (context.actor == SystemIdentity) {
            return model
        }
        return if (isAuthorized(model, context, klerk.specification, withoutAuth)) propertyAuth.secure(model) else null
    }

    private fun <T : Any> checkAuth(model: Model<T>): Model<T> {
        if (context.actor == SystemIdentity) {
            return model
        }
        // The readModels rules see the model as it is in the cache, not a secured copy.
        when (val result = evaluateAuthorization(context, model, klerk.specification, withoutAuth)) {
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

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> possibleVoidEvents(clazz: KClass<T>, visibility: EventVisibility): Set<VoidEvent<T, *>> =
        (klerk.specification.getStateMachine(clazz) as StateMachine<T, *, C, V>).getEventsForVoidState(visibility)
            .filter { klerk.validator.validateWithoutParameters<T>(it.id, context, null, withoutAuth) }
            .toSet()

    override fun <T : Any> possibleEvents(id: ModelID<T>, visibility: EventVisibility): Set<InstanceEvent<T, *>> {
        val model = get(id)
        return klerk.specification.getStateMachine(model).getAvailableEventsForModel(model, visibility)
            .filter { klerk.validator.validateWithoutParameters(it.id, context, model, withoutAuth) }
            .toSet()
    }
}

internal fun <T : Any, C : KlerkContext, V> isAuthorized(
    model: Model<T>,
    context: C,
    specification: Specification<C, V>,
    reader: ReaderWithoutAuth<C, V>,
): Boolean = evaluateAuthorization(context, model, specification, reader) is ReadResult.Ok

internal fun <T : Any, C : KlerkContext, V> evaluateAuthorization(
    context: C,
    model: Model<T>,
    specification: Specification<C, V>,
    reader: ReaderWithoutAuth<C, V>,
): ReadResult<T> {
    if (context.actor == SystemIdentity) {
        return ReadResult.Ok(model)
    }
    val brokenRule = specification.authorization.readModelNegativeRules
        .firstOrNull { it(ModelReadRuleArgs(model, context, reader)) == NegativeAuthorization.Deny }

    if (brokenRule != null) {
        return ReadResult.Fail(
            AuthorizationProblem(
                context.translation.klerk.unauthorized,
                RuleDescription(brokenRule, RuleType.Authorization),
                KlerkErrorCode.ReadNegativeAuthorizationExist,
            ),
        )
    }

    if (specification.authorization.readModelPositiveRules.map { it(ModelReadRuleArgs(model, context, reader)) }
            .none { it == PositiveAuthorization.Allow }
    ) {
        logger.info("No policy explicitly allowed the request")
        return ReadResult.Fail(
            AuthorizationProblem(
                context.translation.klerk.unauthorized,
                null,
                KlerkErrorCode.ReadPositiveAuthorizationMissing,
            ),
        )
    }
    return ReadResult.Ok(model)
}

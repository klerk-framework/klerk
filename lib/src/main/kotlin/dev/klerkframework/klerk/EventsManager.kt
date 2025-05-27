package dev.klerkframework.klerk

import dev.klerkframework.klerk.CommandResult.Failure
import dev.klerkframework.klerk.CommandResult.Success
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.DebugOptions
import dev.klerkframework.klerk.command.DebugOptions.*
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.ModelModification
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.AuditEntry
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import mu.KLogger
import org.slf4j.event.Level

internal class EventsManagerImpl<C : KlerkContext, V>(
    private val config: Config<C, V>,
    private val klerk: KlerkImpl<C, V>,
    private val readWriteLock: ReadWriteLock,
    private val settings: KlerkSettings,
    private val jobs: JobManager<C, V>
) : EventsManager<C, V> {

    private val mutex = Mutex()
    private val processedCommandTokens = mutableSetOf<CommandToken>()
    private val timeTriggerManager = TriggerTimeManagerImpl(this, readWriteLock, klerk)
    private val eventProcessor = EventProcessor<C, V>(klerk, settings, readWriteLock, timeTriggerManager)

    internal suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions,
    ): CommandResult<T, C, V> {
        logger.log(sequence, options) { "Executing command ${command.event}" }

        validateToken(options.token, context)?.let {
            return Failure(listOf(it))
        }

        if (options.dryRun) {
            logger.log(misc, options) { "Aborting processing since dryRun" }
            val withoutAuth = ReaderWithoutAuth(klerk)
            val delta = eventProcessor.processPrimaryCommand(command, context, withoutAuth, options)
            return CommandResult.from(delta, withoutAuth, context, config)
        }

        val result = mutex.withLock {    // never process more than one event simultaneously, but we still allow reading
            logger.log(misc, options) { "Processing event ${command.event}" }

            // delta and commandResult is almost the same thing. Delta contains all the details whereas commandResult
            // is a slightly higher level description of the delta. We don't want to return the delta since it may
            // contain data that the user is not authorized to access.
            val readerWithoutAuth = ReaderWithoutAuth(klerk)
            val delta = eventProcessor.processPrimaryCommand(command, context, readerWithoutAuth, options)
            when (val commandResult = CommandResult.from(delta, readerWithoutAuth, context, config)) {
                is Failure -> {
                    logger.log(result, options) { "Command ${command.event} failed: ${commandResult.problems.joinToString(", ") { it.toString() }}" }
                    commandResult
                }

                is Success -> {
                    processedCommandTokens.add(options.token)
                    commit(delta, command, context)
                    logger.log(result, options) { "Command ${command.event} succeeded" }
                    timeTriggerManager.handle(delta)
                    commandResult
                }
            }
        }       // release the lock. Next command can now start processing
        return result
    }

    private suspend fun <T : Any, P> commit(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?
    ) {
        config.persistence.store(delta, command, context)

        if (delta.containsMutations()) {
            readWriteLock.acquireWrite()    // make sure nobody is reading while we mutate
            ModelCache.handleDelta(delta)
            updateViews(delta)
            readWriteLock.releaseWrite()    // mutation is done, reading is now permitted
        }

        maybeEraseAuditLog(config, delta.deletedModels)
        notifySubscribers(delta)
    }

    private fun <T : Any> updateViews(delta: ProcessingData<T, C, V>) {
        // we could possibly shorten the write lock time by executing these while in read-mode and then just apply a
        // view-delta in write-mode
        delta.functionsToUpdateViews.forEach { it.invoke() }
    }

    private suspend fun validateToken(token: CommandToken, context: C): Problem? {
        if (processedCommandTokens.contains(token)) {
            return IdempotenceProblem("CommandToken has already been used")
        }
        val anyModified = klerk.models.read(context) {
            token.models.any { modelId ->
                ModelCache.read(modelId).getOrNull()?.lastModifiedAt?.let { it > token.time } ?: false
            }
        }
        if (anyModified) {
            return StateProblem("A model has been modified since the token was created")
        }
        return null
    }

    override suspend fun getEventsInAuditLog(
        context: C,
        id: ModelID<Any>?,
        after: Instant,
        before: Instant
    ): Iterable<AuditEntry> {
        val reader = ReaderWithoutAuth<C, V>(klerk)
        readWriteLock.acquireRead()
        try {
            val args = ArgContextReader(context, reader)
            if (config.authorization.eventLogPositiveRules.none { it.invoke(args) == dev.klerkframework.klerk.PositiveAuthorization.Allow }) {
                throw AuthorizationException("Not allowed to read audit log")
            }
            if (config.authorization.eventLogNegativeRules.any { it.invoke(args) == dev.klerkframework.klerk.NegativeAuthorization.Deny }) {
                throw AuthorizationException("Not allowed to read audit log")
            }
        } finally {
            readWriteLock.releaseRead()
        }

        return config.persistence.readAuditLog(modelId = id?.toInt(), after, before)
    }

    internal suspend fun start() {
        eventProcessor.readAllModelsFromDisk()
        timeTriggerManager.start()
    }

    fun stop() {
        timeTriggerManager.stop()
    }

    private suspend fun <T : Any> notifySubscribers(result: ProcessingData<T, C, V>) {
        result.createdModels.forEach {
            klerk.models.modelWasModified(ModelModification.Created(it))
        }
        result.updatedModels.forEach {
            klerk.models.modelWasModified(ModelModification.PropsUpdated(it))
        }
        result.transitions.forEach {
            klerk.models.modelWasModified(ModelModification.Transitioned(it))
        }
        result.deletedModels.forEach {
            klerk.models.modelWasModified(ModelModification.Deleted(it))
        }
    }

    private fun maybeEraseAuditLog(config: Config<C, V>, deletedModels: List<ModelID<out Any>>) {
        if (settings.eraseAuditLogAfterModelDeletion != kotlin.time.Duration.ZERO) {
            return
        }
        deletedModels.forEach {
            config.persistence.modifyEventsInAuditLog(it.toInt()) { null }
        }
    }

    suspend fun modelTriggeredByTime(model: Model<out Any>, now: Instant) {
        logger.info { "Time-block was triggered for $model" }
        val options = ProcessingOptions(CommandToken.simple())
        val delta = eventProcessor.processTimeTrigger(model, options, now)
        if (delta.problems.isNotEmpty()) {
            val problem = delta.problems.first()
            logger.info {
                "The time-trigger for model ${model.id} was blocked. Reason: $problem"
            }
            commit<Any, Nothing>(
                ProcessingData(
                    updatedModels = listOf(model.id),
                    aggregatedModelState = mapOf(model.id to model.copy(timeTrigger = null)),
                    ),
                null,
                null,
            )
            return
        }
        commit<Any, Nothing>(delta, null, null)

        try {
            delta.unmanagedJobs.forEach { it.f.invoke() }
        } catch (e: Exception) {
            logger.warn(e) {
                "The processing of the time-triggered model was successful but an exception was thrown " +
                        "when calling an action function. It is considered bad practice to throw in any function " +
                        "provided to Klerk."
            }
        }
        delta.newJobs.forEach { jobs.scheduleAction(it) }

        timeTriggerManager.handle(delta)
    }

}

internal fun KLogger.log(debugCategory: DebugOptions, options: ProcessingOptions, function: () -> String) {
    atLevel(options.debugOptions[debugCategory] ?: defaultDebugOptions[debugCategory]).log(function)
}

internal val defaultDebugOptions = mapOf(
    sequence to Level.DEBUG,
    misc to Level.TRACE,
    result to Level.DEBUG
)

package dev.klerkframework.klerk

import dev.klerkframework.klerk.CommandResult.Failure
import dev.klerkframework.klerk.CommandResult.Success
import dev.klerkframework.klerk.attacheddata.AttachedDataImpl
import dev.klerkframework.klerk.attacheddata.AttachedDataPlan
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.DebugOptions
import dev.klerkframework.klerk.command.DebugOptions.*
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.job.JobCommit
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.ModelModification
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.AttachedDataDelta
import dev.klerkframework.klerk.storage.AuditEntry
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import org.slf4j.event.Level
import kotlin.time.Instant

internal class EventsManagerImpl<C : KlerkContext, V>(
    private val specification: Specification<C, V>,
    private val klerk: KlerkImpl<C, V>,
    private val readWriteLock: ReadWriteLock,
    private val settings: KlerkSettings,
    private val jobs: JobManagerInternal<C, V>,
    private val attachedData: AttachedDataImpl<C, V>
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

        if (command.event.visibility.level < EventVisibility.CODE.level) {
            return Failure(
                listOf(
                    BadRequestProblem(
                        "This event has visibility ${command.event.visibility} and therefore cannot be processed",
                        KlerkErrorCode.EventVisibilityTooLow
                    )
                )
            )
        }

        if (options.dryRun) {
            logger.log(misc, options) { "Aborting processing since dryRun" }
            val withoutAuth = ReaderWithoutAuth(klerk)
            val delta = eventProcessor.processPrimaryCommand(command, context, withoutAuth, options)
            return CommandResult.from(delta, withoutAuth, context, specification)
        }

        val result = mutex.withLock {    // never process more than one event simultaneously, but we still allow reading
            logger.log(misc, options) { "Processing event ${command.event}" }

            // delta and commandResult is almost the same thing. Delta contains all the details whereas commandResult
            // is a slightly higher level description of the delta. We don't want to return the delta since it may
            // contain data that the user is not authorized to access.
            val readerWithoutAuth = ReaderWithoutAuth(klerk)
            val delta = eventProcessor.processPrimaryCommand(command, context, readerWithoutAuth, options)
            when (val commandResult = CommandResult.from(delta, readerWithoutAuth, context, specification)) {
                is Failure -> {
                    logger.log(
                        result,
                        options
                    ) { "Command ${command.event} failed: ${commandResult.problems.joinToString(", ") { it.toString() }}" }
                    commandResult
                }

                is Success -> {
                    // Attached data is claimed and deleted as part of the command, so a rejected claim (the data is
                    // gone, or another model already owns it) must fail the command before anything is written.
                    when (val plan = attachedData.planFor(delta)) {
                        is AttachedDataPlan.Rejected -> {
                            logger.log(result, options) {
                                "Command ${command.event} failed: ${plan.problems.joinToString(", ") { it.toString() }}"
                            }
                            Failure(plan.problems)
                        }

                        is AttachedDataPlan.Ok -> {
                            // Jobs this command schedules are new work, so they go through admission control here —
                            // while a refusal can still fail the command, and before anything has been written.
                            when (val jobPlan = jobs.planNewJobs(delta.newJobs, context)) {
                                is NewJobPlan.Rejected -> {
                                    logger.log(result, options) {
                                        "Command ${command.event} failed: " +
                                                jobPlan.problems.joinToString(", ") { it.toString() }
                                    }
                                    Failure(jobPlan.problems)
                                }

                                is NewJobPlan.Ok -> {
                                    processedCommandTokens.add(options.token)
                                    commit(delta, command, context, plan.delta, jobPlan.commit)
                                    jobs.jobsWereCommitted(jobPlan)
                                    logger.log(result, options) { "Command ${command.event} succeeded" }
                                    timeTriggerManager.handle(delta)
                                    commandResult
                                }
                            }
                        }
                    }
                }
            }
        }       // release the lock. Next command can now start processing
        return result
    }

    /**
     * Commits one step of a job: the step's command (if any) and the job's own checkpoint, in a single transaction.
     *
     * Runs under the same mutex as [handle], so a job's command is serialized against user commands exactly like any
     * other.
     *
     * **A rejected command is not a job failure.** The model may simply have moved on while the job was queued, so a
     * command that fails still lets the job's checkpoint commit — the step counted as completed, and the next step
     * gets to see the failure in `previousResult` and decide what to do. Only the model delta is skipped.
     *
     * @return the outcome of [command], or null if the step emitted none.
     */
    internal suspend fun <T : Any, P> commitJobStep(
        command: Command<T, P>?,
        context: C?,
        options: ProcessingOptions,
        jobCommit: JobCommit,
    ): CommandResult<T, C, V>? = mutex.withLock {
        if (command == null || context == null) {
            settings.persistence.commitJobStep<Any, Nothing, C, V>(null, null, null, AttachedDataDelta(), jobCommit)
            return@withLock null
        }

        validateToken(options.token, context)?.let { problem ->
            checkpointOnly(jobCommit)
            return@withLock Failure<T, C, V>(listOf(problem))
        }

        val readerWithoutAuth = ReaderWithoutAuth(klerk)
        val delta = eventProcessor.processPrimaryCommand(command, context, readerWithoutAuth, options)
        when (val commandResult = CommandResult.from(delta, readerWithoutAuth, context, specification)) {
            is Failure -> {
                checkpointOnly(jobCommit)
                commandResult
            }

            is Success -> when (val plan = attachedData.planFor(delta)) {
                is AttachedDataPlan.Rejected -> {
                    checkpointOnly(jobCommit)
                    Failure(plan.problems)
                }

                is AttachedDataPlan.Ok -> when (val jobPlan = jobs.planNewJobs(delta.newJobs, context)) {
                    is NewJobPlan.Rejected -> {
                        // A command emitted by a job may itself schedule jobs, and those are new work. Refusing them
                        // fails that command — which the step sees as an ordinary rejection, not as its own failure.
                        checkpointOnly(jobCommit)
                        Failure(jobPlan.problems)
                    }

                    is NewJobPlan.Ok -> {
                        processedCommandTokens.add(options.token)
                        val merged = jobCommit.copy(upserted = jobCommit.upserted + jobPlan.records)
                        commit(delta, command, context, plan.delta, merged, isJobStep = true)
                        jobs.jobsWereCommitted(jobPlan)
                        timeTriggerManager.handle(delta)
                        commandResult
                    }
                }
            }
        }
    }

    /** Writes the job's checkpoint on its own, for a step whose command was not applied. */
    private fun checkpointOnly(jobCommit: JobCommit) {
        settings.persistence.commitJobStep<Any, Nothing, C, V>(null, null, null, AttachedDataDelta(), jobCommit)
    }

    private suspend fun <T : Any, P> commit(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedDataDelta: AttachedDataDelta,
        jobCommit: JobCommit = JobCommit(),
        isJobStep: Boolean = false,
    ) {
        // The models this command changes must be in memory before storage is written, or a read that misses on one of
        // them in the window between the two would fetch the new version while seeing the old version of everything
        // else. Created models cannot be missed (nothing knows their ids yet).
        ModelCache.ensureResident(delta.updatedModels + delta.transitions + delta.deletedModels)

        if (isJobStep) {
            settings.persistence.commitJobStep(delta, command, context, attachedDataDelta, jobCommit)
        } else {
            settings.persistence.store(delta, command, context, attachedDataDelta, jobCommit)
        }

        if (delta.containsMutations() || !attachedDataDelta.isEmpty()) {
            readWriteLock.withWrite {    // make sure nobody is reading while we mutate
                ModelCache.handleDelta(delta)
                attachedData.applyToMemory(attachedDataDelta)
                updateViews(delta)
            }
        }

        maybeEraseAuditLog(specification, delta.deletedModels)
        notifySubscribers(delta)
    }

    private fun <T : Any> updateViews(delta: ProcessingData<T, C, V>) {
        // we could possibly shorten the write lock time by executing these while in read-mode and then just apply a
        // view-delta in write-mode
        delta.functionsToUpdateViews.forEach { it.invoke() }
    }

    private suspend fun validateToken(token: CommandToken, context: C): Problem? {
        if (processedCommandTokens.contains(token)) {
            return IdempotenceProblem("CommandToken has already been used", KlerkErrorCode.CommandTokenAlreadyUsed)
        }
        val anyModified = klerk.models.read(context) {
            token.models.any { modelId ->
                ModelCache.read(modelId).getOrNull()?.lastModifiedAt?.let { it > token.time } ?: false
            }
        }
        if (anyModified) {
            return StateProblem(
                "A model has been modified since the token was created",
                "A model has been modified since the token was created",
                KlerkErrorCode.ModelModifiedSinceTokenCreation
            )
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
        readWriteLock.withRead {
            val args = ArgContextReader(context, reader)
            if (specification.authorization.eventLogPositiveRules.none { it.invoke(args) == dev.klerkframework.klerk.PositiveAuthorization.Allow }) {
                throw AuthorizationException(
                    KlerkErrorCode.AuditPositiveAuthorizationMissing,
                    "Not allowed to read audit log"
                )
            }
            if (specification.authorization.eventLogNegativeRules.any { it.invoke(args) == dev.klerkframework.klerk.NegativeAuthorization.Deny }) {
                throw AuthorizationException(
                    KlerkErrorCode.AuditNegativeAuthorizationExist,
                    "Not allowed to read audit log"
                )
            }
        }

        return settings.persistence.readAuditLog(modelId = id?.value, after, before)
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

    private fun maybeEraseAuditLog(specification: Specification<C, V>, deletedModels: List<ModelID<out Any>>) {
        if (specification.eraseAuditLogAfterModelDeletion != kotlin.time.Duration.ZERO) {
            return
        }
        deletedModels.forEach {
            settings.persistence.modifyEventsInAuditLog(it.value) { null }
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
                AttachedDataDelta(),
            )
            return
        }
        // A time-trigger can change props too, so its attached data must be diffed just like a command's. There is no
        // caller to return a Problem to, so a rejected plan can only be logged and the trigger abandoned.
        val attachedDataDelta = when (val plan = attachedData.planFor(delta)) {
            is AttachedDataPlan.Rejected -> {
                logger.error {
                    "The time-trigger for model ${model.id} could not be committed because of its attached data: " +
                            plan.problems.joinToString(", ") { it.toString() }
                }
                return
            }

            is AttachedDataPlan.Ok -> plan.delta
        }
        // A time-trigger can schedule jobs too. There is no caller to fail, so a refusal by admission control can only
        // be logged and the jobs dropped — the trigger's own model changes still commit.
        val systemContext = specification.systemContextProvider.invoke(SystemIdentity)
        val jobPlan = when (val planned = jobs.planNewJobs(delta.newJobs, systemContext)) {
            is NewJobPlan.Rejected -> {
                logger.warn {
                    "The jobs scheduled by the time-trigger for model ${model.id} were refused: " +
                            planned.problems.joinToString(", ") { it.toString() }
                }
                NewJobPlan.Ok(emptyList())
            }

            is NewJobPlan.Ok -> planned
        }
        commit<Any, Nothing>(delta, null, null, attachedDataDelta, jobPlan.commit)
        jobs.jobsWereCommitted(jobPlan)

        try {
            delta.unmanagedJobs.forEach { it.f.invoke() }
        } catch (e: Exception) {
            logger.warn(e) {
                "The processing of the time-triggered model was successful but an exception was thrown " +
                        "when calling an action function. It is considered bad practice to throw in any function " +
                        "provided to Klerk."
            }
        }

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

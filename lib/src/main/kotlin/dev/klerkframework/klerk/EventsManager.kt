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
import dev.klerkframework.klerk.read.withoutReadRestrictions
import dev.klerkframework.klerk.storage.AttachedDataDelta
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.statemachine.UnmanagedJob
import dev.klerkframework.klerk.storage.ModelCache
import java.util.concurrent.atomic.AtomicLong
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
) {

    private val mutex = Mutex()

    /** The number handed to the most recently started commit. Only ever read under [mutex]. */
    private val assignedSequenceNumber = AtomicLong()

    /**
     * The highest event-log sequence number a reader may see. Raised under the write lock, in the same critical
     * section that makes the commit visible to reads, so the log never runs ahead of the models.
     */
    @Volatile
    internal var visibleSequenceNumber: Long = 0
        private set

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
            val delta = eventProcessor.processPrimaryCommand(withoutReadRestrictions(command), context, withoutAuth, options)
            return CommandResult.from(delta, withoutAuth, context, specification, settings.allowBypassAuthRead)
        }

        // Actions run outside the lock, so they are collected here and invoked after it is released.
        var actions: List<UnmanagedJob> = emptyList()
        val result = mutex.withLock {    // never process more than one event simultaneously, but we still allow reading
            logger.log(misc, options) { "Processing event ${command.event}" }

            // delta and commandResult is almost the same thing. Delta contains all the details whereas commandResult
            // is a slightly higher level description of the delta. We don't want to return the delta since it may
            // contain data that the user is not authorized to access.
            val readerWithoutAuth = ReaderWithoutAuth(klerk)
            val delta = eventProcessor.processPrimaryCommand(withoutReadRestrictions(command), context, readerWithoutAuth, options)
            when (val commandResult = CommandResult.from(delta, readerWithoutAuth, context, specification, settings.allowBypassAuthRead)) {
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
                                    logger.log(result, options) { "Command ${command.event} succeeded" }
                                    timeTriggerManager.handle(delta)
                                    actions = delta.unmanagedJobs
                                    commandResult
                                }
                            }
                        }
                    }
                }
            }
        }       // release the lock. Next command can now start processing

        try {
            actions.forEach { it.f.invoke() }
        } catch (e: Exception) {
            logger.warn(e) {
                "The command was successful but an exception was thrown when calling an action function. It is " +
                        "considered bad practice to throw in any function provided to Klerk."
            }
        }
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
            commitJobsOnly(jobCommit)
            return@withLock null
        }

        validateToken(options.token, context)?.let { problem ->
            checkpointOnly(jobCommit)
            return@withLock Failure<T, C, V>(listOf(problem))
        }

        val readerWithoutAuth = ReaderWithoutAuth(klerk)
        val delta = eventProcessor.processPrimaryCommand(withoutReadRestrictions(command), context, readerWithoutAuth, options)
        when (val commandResult = CommandResult.from(delta, readerWithoutAuth, context, specification, settings.allowBypassAuthRead)) {
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
                        timeTriggerManager.handle(delta)
                        commandResult
                    }
                }
            }
        }
    }

    /** Writes the job's checkpoint on its own, for a step whose command was not applied. */
    private suspend fun checkpointOnly(jobCommit: JobCommit) = commitJobsOnly(jobCommit)

    /**
     * Persists a commit that touches jobs but no models, and applies it to memory under the write lock.
     *
     * These paths never reach [commit], so without this they would write storage and leave the in-memory queue
     * behind — which is the whole failure this change exists to prevent.
     */
    private suspend fun commitJobsOnly(jobCommit: JobCommit) {
        // No command, so no event log entry and no sequence number is consumed.
        settings.persistence.commitJobStep<Any, Nothing, C, V>(
            null, null, null, AttachedDataDelta(), jobCommit, sequenceNumber = 0
        )
        readWriteLock.withWrite { jobs.applyToMemory(jobCommit) }
        jobs.notifyCommitted(jobCommit)
    }

    private suspend fun <T : Any, P> commit(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?,
        attachedDataDelta: AttachedDataDelta,
        jobCommit: JobCommit = JobCommit(),
        isJobStep: Boolean = false,
    ) {

        // Persisting to the database can take several milliseconds, and reads keep running throughout: the write lock
        // is taken only for the in-memory flip at the end.
        //
        // What makes that safe is that the sole storage read a reader performs while holding the read lock is
        // ModelCache.getBody's repair of an evicted model. beginCommit pins the pre-commit body of every model this
        // command changes, so that repair is answered from the pin instead of from storage, which may already hold
        // the new state. Everything else a read touches -- views, relations, attached-data metadata -- is in memory
        // and flips under the write lock. A read therefore sees either all of the commit or none of it. Created
        // models need no pin: nothing knows their ids yet, so nobody can be reading them.
        //
        // The event log is read from storage rather than from memory, so it cannot be pinned the same way. Instead the
        // entry carries this commit's sequence number, and a reader only sees entries at or below
        // [visibleSequenceNumber] — which is raised below, in the same critical section that flips the cache.
        val touchedIds = delta.updatedModels + delta.transitions + delta.deletedModels
        val sequenceNumber = assignedSequenceNumber.incrementAndGet()
        ModelCache.beginCommit(touchedIds)
        try {
            if (isJobStep) {
                settings.persistence.commitJobStep(delta, command, context, attachedDataDelta, jobCommit, sequenceNumber)
            } else {
                settings.persistence.store(delta, command, context, attachedDataDelta, jobCommit, sequenceNumber)
            }
        } catch (e: Exception) {
            ModelCache.endCommit()
            throw e
        }

        readWriteLock.withWrite {
            ModelCache.handleDelta(delta)
            attachedData.applyToMemory(attachedDataDelta)
            jobs.applyToMemory(jobCommit)
            updateViews(delta)
            visibleSequenceNumber = sequenceNumber
            ModelCache.endCommit()
        }
        jobs.notifyCommitted(jobCommit)
        notifySubscribers(delta)
        maybeEraseEventLog(specification, delta.deletedModels)
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

    internal suspend fun start() {
        val lastPersisted = settings.persistence.lastEventLogSequenceNumber()
        assignedSequenceNumber.set(lastPersisted)
        visibleSequenceNumber = lastPersisted
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

    private fun maybeEraseEventLog(specification: Specification<C, V>, deletedModels: List<ModelID<out Any>>) {
        if (specification.eraseEventLogAfterModelDeletion != kotlin.time.Duration.ZERO) {
            return
        }
        deletedModels.forEach {
            settings.persistence.modifyEventLog(it.value) { null }
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

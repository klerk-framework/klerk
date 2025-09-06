package dev.klerkframework.klerk

import dev.klerkframework.klerk.job.RunnableJob
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.DebugOptions
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.IdFactory
import dev.klerkframework.klerk.misc.IdProvider
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.statemachine.*
import dev.klerkframework.klerk.statemachine.BlockType.*
import dev.klerkframework.klerk.storage.ModelCache
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import kotlinx.datetime.Clock
import mu.KotlinLogging
import kotlinx.datetime.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The system switches between two modes: read and write. In read mode, many coroutines are allowed to access the
 * models simultaneously. When an event is sent to the framework, validation is done while still in read mode. If the event
 * is accepted, the system pauses new reads and when all ongoing reads have completed, the system switches to write mode
 * and all external reads are blocked.
 */
internal class EventProcessor<C : KlerkContext, V>(
    private val klerk: KlerkImpl<C, V>,
    private val settings: KlerkSettings,
    private val readWriteLock: ReadWriteLock,
    private val timeTriggerManager: TriggerTimeManager,
//    private val migrations: IMigrations<EventType>?,
) {
    private val logger = KotlinLogging.logger {}
    val counterPrimaryEventsTotal: Counter
    val timerStartup: Timer

    init {
        counterPrimaryEventsTotal = Counter.builder("klerk.events.primary.total")
            .description("The total number of events processed since startup").baseUnit("events")
            .register(klerk.config.meterRegistry)

        timerStartup =
            Timer.builder("klerk.events.startupTime").description("The time of reading all stored events at startup")
                .register(klerk.config.meterRegistry)
    }

    internal suspend fun readAllModelsFromDisk() {
        logger.debug { "Reading all persisted models into ModelCache" }
        require(ModelCache.count == 0) { "ModelCache is not empty" }

        val allLists = mutableMapOf<String, MutableList<Int>>()
        klerk.config.managedModels.forEach {
            allLists[it.kClass.simpleName!!] = it.collections._all
        }

        val clock = Clock.System
        val start = clock.now()
        var lastLog = clock.now()
        klerk.config.persistence.readAllModels { model ->
            if (clock.now().minus(10.seconds) > lastLog) {
                lastLog = clock.now()
                logger.info { "Has read ${ModelCache.count}" }
            }

            ModelCache.storeFromPersistence(model)
            (allLists[model.props::class.simpleName!!]
                ?: throw NullPointerException()).add(model.id.toInt())  // the 'all' list-source
        }
        val readModelsMilliS = clock.now().toEpochMilliseconds() - start.toEpochMilliseconds()
        val durationSeconds = readModelsMilliS.toFloat() / 1000
        val modelsPerSecondString =
            if (durationSeconds == 0F) "" else "(${(ModelCache.count / durationSeconds).roundToInt()} models/s)"
        logger.info { "Read ${ModelCache.count} models in $readModelsMilliS ms $modelsPerSecondString" }

        val relationsTime = measureTime {
            ModelCache.initRelations()
        }
        logger.info { "Built relations map in ${relationsTime.inWholeMilliseconds} ms" }

        val timeTriggerTime = measureTime {
            updateTimeTriggerOnAllModels()
        }
        logger.info { "Checked timeTriggers in ${timeTriggerTime.inWholeMilliseconds} ms" }

        timerStartup.record(readModelsMilliS, TimeUnit.MILLISECONDS)
    }

    /**
     * We need to check every model so that
     * 1. We will keep track of if/when it should be triggered because of time
     * 2. The code may have changed so that the state of the model now contains a time-block. If so, we must set the
     * timeTrigger. Note that the code may also have changed so that the duration or atTime-function now produces an
     * instance that is closer in time than the current timeTrigger-value. We must therefore evaluate them all again!
     */
    private fun updateTimeTriggerOnAllModels() {
        val reader = ReaderWithoutAuth<C, V>(klerk)
        processTriggerTimeForModels(ModelCache.getAll(reader).map { it.value }, reader)
    }

    private fun processTriggerTimeForModels(models: List<Model<out Any>>, reader: Reader<C, V>) {
        val time = Clock.System.now()
        val calculated = models.map { it to calculateTriggerTime(it, time, reader) }

        timeTriggerManager.init(calculated.map { it.second })

        calculated.filter {
            (it.first.timeTrigger == null && it.second.timeTrigger != null) // there was no trigger but now it should be
                    || (it.first.timeTrigger != null && it.second.timeTrigger == null) // there was a trigger but it shouldn't anymore
                    || (it.first.timeTrigger != null && it.second.timeTrigger != null && it.first.timeTrigger!! > it.second.timeTrigger!!      // there was a trigger, and still is. However, the new config says that it should trigger earlier than the stored value. (note that we cannot say anything about the opposite situation)
            )
        }.forEach {
            ModelCache.store(it.second)
        }
    }

    /**
     * Returns a new model with the re-calculated value for triggerTime.
     */
    private fun calculateTriggerTime(
        model: Model<out Any>,
        time: Instant,
        reader: Reader<C, V>
    ): Model<out Any> {
        val state = klerk.config.getStateMachine(model).getStateByName(model.state)
        check(state is InstanceState)
        @Suppress("UNCHECKED_CAST")
        var instant = (state.atTimeFunction as? (ArgForInstanceNonEvent<out Any, C, V>) -> Instant)?.invoke(
            ArgForInstanceNonEvent(model, time, reader)
        )
        if (instant == null && state.afterDuration != null) {
            instant = time.plus(state.afterDuration!!)
        }
        return model.copy(timeTrigger = instant)
    }

    /**
     * Produces a ProcessingData, which contains all the changes, jobs and actions that this command will lead to.
     * Note that the command may produce other commands, which will also be processed.
     * @param reader a reader that does not enforce authorization rules
     */
    internal fun <T : Any, P> processPrimaryCommand(
        command: Command<T, P>,
        context: C,
        reader: Reader<C, V>,
        options: ProcessingOptions,
    ): ProcessingData<T, C, V> {
        counterPrimaryEventsTotal.increment()
        val processingData = ProcessingData<T, C, V>(remainingCommands = listOf(command), primaryModel = command.model)
        val result = process(processingData, context, reader, isPrimary = true, options, context.time)
        return result
    }

    internal suspend fun <T : Any> processTimeTrigger(
        model: Model<T>,
        options: ProcessingOptions,
        time: Instant,
    ): ProcessingData<T, C, V> {
        val processingData = ProcessingData<T, C, V>(remainingTimeTrigger = model, primaryModel = model.id)
        readWriteLock.acquireRead()
        val reader = ReaderWithoutAuth(klerk)
        val context = klerk.config.contextProvider!!.invoke(SystemIdentity)
        val result = process(processingData, context, reader, isPrimary = true, options, time)
        readWriteLock.releaseRead()
        return result
    }


    /**
     * This is how the processing works:
     * 1. This function receives a ProcessingData object.
     * 2. We figure out which is the first block that needs to be executed. If no block -> exit.
     * 3. We execute the block by passing in the ProcessingData object. The block will return a new ProcessingData
     * object which contains only the result of the blocks own execution.
     * 4. We merge the new and the old ProcessingData object.
     * 5. We call this function recursively until there is no need to execute any more blocks.
     *
     * Transitions and exit blocks need a more detailed description: When a transition is triggered, we cannot make the
     * transform the model immediately since the exit block must be executed using input of how the model exists before
     * the transition. We will therefore finalize the transition when we merge with the result of the exit block.
     *
     * @param Primary is type of the original state machine. Not used in the processing but needed in the response.
     * @param isPrimary true if this is the initial state machine (and initial command/time-trigger)
     */
    private fun <Primary : Any> process(
        processingData: ProcessingData<Primary, C, V>,
        context: C,
        reader: Reader<C, V>,
        isPrimary: Boolean,
        options: ProcessingOptions,
        time: Instant,
    ): ProcessingData<Primary, C, V> {
        if (processingData.remainingCommands.isEmpty() && processingData.remainingTimeTrigger == null) {
            return processingData.withTimeTriggersOnModels()
        }

        val beforeBlockProcessing = prepareNextBlock(processingData, options, reader, context)

        val after = processBlocks<Primary, Any, Any>(beforeBlockProcessing, context, reader, options, time)
        val withCorrectPrimary = if (isPrimary && after.primaryModel == null) {
            @Suppress("UNCHECKED_CAST")
            after.copy(primaryModel = after.aggregatedModelState[after.createdModels.singleOrNull()]?.id as? ModelID<Primary>)
        } else after
        return process(withCorrectPrimary, context, reader, isPrimary = false, options, time)
    }

    /**
     * Transforms the processingData so that it the remaining time-trigger or first remaining command is replaced
     * with one block.
     */
    private fun <Primary : Any> prepareNextBlock(
        processingData: ProcessingData<Primary, C, V>,
        options: ProcessingOptions,
        reader: Reader<C, V>,
        context: C,
    ): ProcessingData<Primary, C, V> {
        // is there a timeTrigger?
        processingData.remainingTimeTrigger?.let { model ->
            val block = when (val state = klerk.config.getStateMachine(model).getStateByName(model.state)) {
                is InstanceState -> state.timeBlock
                is VoidState -> throw IllegalStateException()
            }
            return processingData.copy(
                remainingTimeTrigger = null,
                remainingBlocks = listOf(requireNotNull(block)),
                currentModel = model.id
            )
        }

        // no, let's take the first remaining command
        check(processingData.remainingCommands.isNotEmpty())
        val currentCommand = processingData.remainingCommands.first()
        val remaining = processingData.remainingCommands.drop(1)
        logger.log(DebugOptions.sequence, options) { "Processing command ${currentCommand.event}" }

        val commandValidationProblems = klerk.validator.validateCommand(currentCommand, reader, context)
        if (commandValidationProblems.isNotEmpty()) {
            return ProcessingData(problems = commandValidationProblems)
        }

        val modelId = currentCommand.model
        val model = processingData.aggregatedModelState[modelId] ?: currentCommand.model?.let { reader.getOrNull(it) }
        val state = klerk.config.getStateMachineForEvent(currentCommand.event).getStateByName(model?.state)
        val block = when (state) {
            is InstanceState -> state.getBlockByEventReference(currentCommand.event.id)
            is VoidState -> state.getBlockByEventReference(currentCommand.event.id)
        }

        return processingData.copy(
            remainingCommands = remaining,
            remainingBlocks = listOf(block),
            currentCommand = currentCommand,
            currentBlock = block,
            currentModel = model?.id,
            )
    }


    /**
     * @param modelAsResultOfCommandSoFar Used as parameter in the blocks, not as a final result. An example how this is
     * used: A void event causes a new model to be created. The model immediately transitions to state A. The
     * modelAsResultOfCommandSoFar will be used in executables in onEnter for state A. We need this since the command
     * doesn't contain a reference (and even if it did, the model is not stored in ModelCache yet so the reader will not
     * find it).
     * @param context is null when we are processing the state machine due to a time-trigger (i.e. not by a command).
     * But whenever there is an Event, we know that it was caused via a command and therefore we know that context is
     * non-null.
     */
    private fun <Primary : Any, T : Any, P> processBlocks(
        processingData: ProcessingData<Primary, C, V>,
        context: C,
        reader: Reader<C, V>,
        options: ProcessingOptions,
        time: Instant
    ): ProcessingData<Primary, C, V> {
        if (processingData.remainingBlocks.isEmpty()) {
            return processingData
        }
        val currentBlock = processingData.remainingBlocks.first()
        val remaining = processingData.remainingBlocks.drop(1)
        logger.log(DebugOptions.sequence, options) { "Processing block ${currentBlock}" }

        val processingOptions = EventProcessingOptions(
            disregardPreventingRules = false,
            idProvider = IdFactory(klerk.jobs::isJobIdAvailable),
            performActions = false,
            preventModelUpdates = false
        )
        val modelId = processingData.currentCommand?.model ?: processingData.currentModel

        @Suppress("UNCHECKED_CAST")
        val model = (processingData.aggregatedModelState[modelId] ?: modelId?.let { reader.getOrNull(it) }) as? Model<T>
        val stateMachine = model?.let { klerk.config.getStateMachine(it) }
            ?: klerk.config.getStateMachineForEvent(processingData.currentCommand!!.event)

        @Suppress("UNCHECKED_CAST")
        val view = stateMachine.modelViews as ModelViews<T, C>
        val result = when (currentBlock) {

            is Block.VoidNonEventBlock -> {
                ProcessingData(currentBlock = currentBlock)
            }

            is Block.VoidEventBlock<*, *, *, C, V> -> {
                @Suppress("UNCHECKED_CAST")
                processingData.currentCommand as Command<T, P>
                val args = ArgForVoidEvent(
                    requireNotNull(processingData.currentCommand),
                    context,
                    reader
                )

                @Suppress("UNCHECKED_CAST")
                currentBlock.executables.map { it as VoidEventExecutable<T, P, C, V> }
                    .filter { it.onCondition?.invoke(args) ?: true }
                    .map { it.process(args, processingOptions, view, klerk.config, processingData) }
                    .reduceOrNull { acc, delta -> acc.merge(delta) }
                    ?: ProcessingData(currentBlock = currentBlock)
            }

            is Block.InstanceEventBlock<*, *, *, C, V> -> {
                @Suppress("UNCHECKED_CAST")
                processingData.currentCommand as Command<T, P>
                val command = requireNotNull(processingData.currentCommand)
                val args = ArgForInstanceEvent(requireNotNull(model), command, context, reader)

                @Suppress("UNCHECKED_CAST")
                currentBlock.executables.map { it as InstanceEventExecutable<T, P, C, V> }
                    .filter { it.onCondition?.invoke(args) ?: true }
                    .map { it.process(args, processingOptions, view, klerk.config, processingData) }
                    .reduceOrNull { acc, delta -> acc.merge(delta) }
                    ?: ProcessingData(currentBlock = currentBlock)
            }

            is Block.InstanceNonEventBlock -> {
                val args = ArgForInstanceNonEvent(requireNotNull(model), Clock.System.now(), reader)
                @Suppress("UNCHECKED_CAST")
                currentBlock.executables.map { it as InstanceNonEventExecutable<T, C, V> }
                    .filter { it.onCondition?.invoke(args) ?: true }
                    .map { it.process(args, processingOptions, view, klerk.config, processingData) }
                    .reduceOrNull { acc, delta -> acc.merge(delta) }
                    ?: ProcessingData(currentBlock = currentBlock)
            }
        }
        val updatedDelta = processingData.copy(remainingBlocks = remaining)      // keep the old (except current)
            .merge(result, currentBlock.type == Exit)                          // new block may be added here
        val withTimeTriggers = withTimeTriggers<Primary, T>(updatedDelta, time, reader)
        return processBlocks<Primary, T, P>(withTimeTriggers, context, reader, options, time)
    }

    /**
     * Returns an updated ProcessingData in which modifiedModels have a correct timeTrigger value
     */
    private fun <Primary : Any, T : Any> withTimeTriggers(
        processingData: ProcessingData<Primary, C, V>,
        time: Instant,
        reader: Reader<C, V>,
    ): ProcessingData<Primary, C, V> {
        val newTimeTriggers =
            processingData.transitions
                .map { processingData.aggregatedModelState[it]!! }
                .map { model ->
                    @Suppress("UNCHECKED_CAST")
                    Pair(model.id, findTimeTrigger(model as Model<T>, ArgForInstanceNonEvent(model, time, reader)))
            }

        return processingData.copy(timeTriggers = newTimeTriggers.associate { it })/*        return processingData.copy(modifiedModels = processingData.modifiedModels
                    .filter { processingData.transitions.keys.contains(it.key) || processingData.createdModels.keys.contains(it.key) }
                    .map { (id, model) -> id to model.copy(timeTrigger = findTimeTrigger(model as Model<T>, ArgForInstanceNonEvent(model, context, reader))) }
                    .toMap())
         */
    }

    private fun <T : Any> findTimeTrigger(
        newModel: Model<T>, transformedArgs: ArgForInstanceNonEvent<T, C, V>
    ): Instant? {
        val state = klerk.config.getStateMachine(newModel).getStateByName(newModel.state)
        check(state is InstanceState)
        var instant = state.atTimeFunction?.invoke(transformedArgs)
        if (instant == null && state.afterDuration != null) {
            instant = transformedArgs.time.plus(state.afterDuration!!)
        }
        return instant
    }


}

/**
 * Note that lists are used when ordering matters and maps are used for models as it is possible that a model is
 * updated/transitioned more than once for the same command.
 *
 * @property primaryModel is the model that was created or manipulated by the primary command (i.e. the command that
 * the user initiated).
 * @property currentModel is the model that was created or manipulated by the current command. Will be the same as
 * primary unless the current command is a sub-command.
 * @property aggregatedModelState the models as they are after the last block was executed
 * @property createdModels models that were created. Note that they may have been modified after creation, which means
 * that the model in this map may NOT be the final outcome for this model. See modifiedModels instead.
 * @property updatedModels models that were updated. Note that they may have been modified after the update, which means
 * that the model in this map may NOT be the final outcome for this model. See modifiedModels instead.
 * @property transitions models that changed state. Note that they may have been modified after the transition, which means
 * that the model in this map may NOT be the final outcome for this model. See modifiedModels instead.
 */
public data class ProcessingData<Primary : Any, C : KlerkContext, V>(
    val primaryModel: ModelID<Primary>? = null,
    val currentModel: ModelID<out Any>? = null,
    val unmanagedJobs: List<UnmanagedJob> = emptyList(),
    val createdModels: List<ModelID<out Any>> = emptyList(),
    val updatedModels: List<ModelID<out Any>> = emptyList(),
    val transitions: List<ModelID<out Any>> = emptyList(),
    val deletedModels: List<ModelID<out Any>> = emptyList(),
    val unFinalizedTransition: Triple<String, Instant, Model<out Any>>? = null,
    val aggregatedModelState: Map<ModelID<out Any>, Model<out Any>> = emptyMap(),
    val newJobs: List<RunnableJob<C, V>> = emptyList(),
    val remainingBlocks: List<Block<*, *, C, V>> = emptyList(),
    val currentBlock: Block<*, *, C, V>? = null,
    val processedBlocks: List<Block<*, *, C, V>> = emptyList(),
    val remainingCommands: List<Command<out Any, out Any?>> = emptyList(),
    val currentCommand: Command<out Any, out Any?>? = null,
    val processedCommands: List<Command<out Any, out Any?>> = emptyList(),
    val log: List<String> = emptyList(),
    val functionsToUpdateViews: List<() -> Unit> = emptyList(),
    val problems: List<Problem> = emptyList(),
    val timeTriggers: Map<ModelID<out Any>, Instant?> = emptyMap(),
    val remainingTimeTrigger: Model<out Any>? = null
) {

    override fun toString(): String = currentBlock?.name ?: "unknown"

    /**
     * @param subsequentIsExitBlock if true, an exit block has been processed. We thus know there was a transition, and we can
     * now update the model with transition info.
     */
    internal fun merge(
        subsequent: ProcessingData<Primary, C, V>,
        subsequentIsExitBlock: Boolean = false
    ): ProcessingData<Primary, C, V> {
        val (updatedModifiedModels, updatedTransitions, toFinalize) = calculateModifiedModels(
            subsequent,
            subsequentIsExitBlock
        )
        return copy(
            currentModel = currentModel ?: subsequent.currentModel,
            unmanagedJobs = unmanagedJobs.plus(subsequent.unmanagedJobs),
            createdModels = createdModels.plus(subsequent.createdModels),
            updatedModels = updatedModels.plus(subsequent.updatedModels),
            deletedModels = deletedModels.plus(subsequent.deletedModels),
            transitions = updatedTransitions,
            unFinalizedTransition = toFinalize,
            aggregatedModelState = updatedModifiedModels,
            newJobs = newJobs.plus(subsequent.newJobs),
            remainingBlocks = remainingBlocks.plus(subsequent.remainingBlocks),
            processedBlocks = currentBlock?.let { processedBlocks.plus(it) } ?: processedBlocks,
            remainingCommands = remainingCommands.plus(subsequent.remainingCommands),
            processedCommands = currentCommand?.let { processedCommands.plus(it) } ?: processedCommands,
            functionsToUpdateViews = functionsToUpdateViews.plus(subsequent.functionsToUpdateViews),
            problems = problems.plus(subsequent.problems),
            remainingTimeTrigger = subsequent.remainingTimeTrigger,
            log = log.plus(subsequent.log)
        )
    }

    internal data class CalculatedStuff(
        val modified: Map<ModelID<out Any>, Model<out Any>>,
        val transitions: List<ModelID<out Any>>,
        val toFinalize: Triple<String, Instant, Model<out Any>>?,
    )

    /**
     * Calculates how modified models look like after the block.
     * @param subsequent ProcessingData from the result of a processed block.
     * @param finalizeTransition if true, an exit block has been processed. We thus know there was a transition or creation, and we can
     * now update the model with transition info.
     * @return modified models and transitions
     */
    private fun <Primary : Any, C : KlerkContext, V> calculateModifiedModels(
        subsequent: ProcessingData<Primary, C, V>,
        finalizeTransition: Boolean,
    ): CalculatedStuff {
        val modified = mutableMapOf<ModelID<out Any>, Model<out Any>>()
        modified.putAll(aggregatedModelState)

        val newTransitions = mutableListOf<ModelID<out Any>>()
        newTransitions.addAll(transitions)

        var toFinalize: Triple<String, Instant, Model<out Any>>? = null
        subsequent.deletedModels.forEach { modified.remove(it) }

        if (finalizeTransition) {
            val id = requireNotNull(currentModel ?: subsequent.currentModel)
            require(subsequent.transitions.isEmpty()) { "There can be no transition in exit blocks" }
            // we will now apply the transition from the previous block instead
            val (newState, time) = requireNotNull(unFinalizedTransition)

            // we first look in subsequent (updates in exit-block).
            val inModified = subsequent.aggregatedModelState[currentModel] ?: modified[currentModel] ?: unFinalizedTransition.third

            modified[id] = inModified.copy(
                state = newState,
                lastStateTransitionAt = time
            )

            newTransitions.add(id)
        } else {
            modified.putAll(subsequent.aggregatedModelState)
            toFinalize = subsequent.unFinalizedTransition ?: unFinalizedTransition
        }

        return CalculatedStuff(modified, newTransitions, toFinalize)
    }

    internal fun containsMutations(): Boolean {
        return true
    }

    internal fun withTimeTriggersOnModels(): ProcessingData<Primary, C, V> {
        val aggStates = mutableMapOf<ModelID<out Any>, Model<out Any>>()
        aggStates.putAll(aggregatedModelState)
        timeTriggers.forEach { (id, instant) ->
            aggStates[id] = aggregatedModelState[id]!!.copy(timeTrigger = instant)   // force non-null. If null -> bug!
        }
        return this.copy(
            aggregatedModelState = aggStates
        )
    }

}

internal data class EventProcessingOptions(
    val storeEvent: Boolean = true,
    val performActions: Boolean,
    val notifyListeners: Boolean = true,
    val preventModelUpdates: Boolean,
    val idProvider: IdProvider,
    val disregardPreventingRules: Boolean
)

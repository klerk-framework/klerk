package dev.klerkframework.klerk

import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.DebugOption
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.IdFactory
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.misc.makeExactSerializable
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.statemachine.*
import dev.klerkframework.klerk.statemachine.BlockType.Exit
import dev.klerkframework.klerk.storage.ModelCache
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import mu.KotlinLogging
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.measureTime

/**
 * The system switches between two modes: read and write. In read mode, many coroutines are allowed to access the
 * models simultaneously. When an event is sent to the framework, validation is done while still in read mode. If the
 * event is accepted, the system pauses new reads and when all ongoing reads have completed, the system switches to
 * write mode and all external reads are blocked.
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
            .register(klerk.settings.meterRegistry)

        timerStartup =
            Timer.builder("klerk.events.startupTime").description("The time of reading all stored events at startup")
                .register(klerk.settings.meterRegistry)
    }

    internal suspend fun readAllModelsFromDisk() {
        logger.debug { "Reading all persisted models into ModelCache" }
        require(ModelCache.count == 0) { "ModelCache is not empty" }

        val allLists = mutableMapOf<String, MutableList<Int>>()
        for (managedModel in klerk.specification.managedModels) {
            managedModel.views.prepareForLoad()
            allLists[managedModel.kClass.simpleName!!] = managedModel.views._all
        }

        val clock = Clock.System
        val start = clock.now()
        var lastLog = clock.now()
        klerk.settings.persistence.readAllModels { model ->
            if (clock.now().minus(10.seconds) > lastLog) {
                lastLog = clock.now()
                logger.info { "Has read ${ModelCache.count}" }
            }

            ModelCache.storeFromPersistence(model)
            allLists.getValue(model.props::class.simpleName!!).add(model.id.value)  // the 'all' list-source

            val problems = klerk.validator.validateDataContainers(model.props, DefaultTranslation)
            if (problems.isNotEmpty()) {
                throw PersistedModelValidationException(
                    model.props::class.simpleName!!,
                    model.id.value,
                    problems.joinToString(),
                )
            }
        }
        val readModelsMilliS = clock.now().toEpochMilliseconds() - start.toEpochMilliseconds()
        val durationSeconds = readModelsMilliS.toFloat() / 1000
        val modelsPerSecondString =
            if (durationSeconds == 0F) "" else "(${(ModelCache.count / durationSeconds).roundToInt()} models/s)"
        if (readModelsMilliS < 1000) {
            logger.info { "Read ${ModelCache.count} models" }
        } else {
            logger.info { "Read ${ModelCache.count} models in ${readModelsMilliS / 1000} s $modelsPerSecondString" }
        }

        // The load filled the 'all' lists directly, so the matching id sets have to catch up.
        for (managedModel in klerk.specification.managedModels) {
            managedModel.views.indexLoadedModels()
        }

        val timeTriggerTime = measureTime {
            updateTimeTriggerOnAllModels()
        }
        logger.info { "Checked timeTriggers in ${timeTriggerTime.inWholeMilliseconds} ms" }

        // From here on the set of views is fixed. Views derived later are not indexed and not retained.
        for (managedModel in klerk.specification.managedModels) {
            managedModel.views.freeze()
        }

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
        // Goes through the ids rather than what happens to be resident: every model must be checked, and with a
        // bounded cache some of them will already have been evicted by the time the startup read finishes.
        val models = ModelCache.allIds(reader).mapNotNull { ModelCache.getOrNull<Any>(ModelID(it)) }
        processTriggerTimeForModels(models, reader)
    }

    private fun processTriggerTimeForModels(models: List<Model<out Any>>, reader: ModelReader<C, V>) {
        val time = klerk.settings.now()
        val context = klerk.specification.systemContextProvider.invoke()
        val calculated = models.map { it to calculateTriggerTime(it, time, context, reader) }

        timeTriggerManager.init(calculated.map { it.second })

        for ((stored, recalculated) in calculated) {
            val before = stored.timeTrigger
            val after = recalculated.timeTrigger
            // A trigger that moved later is left alone: nothing can be said about that situation.
            val changed = (before == null) != (after == null) || (before != null && after != null && before > after)
            if (changed) {
                ModelCache.store(recalculated)
            }
        }
    }

    /**
     * Returns a new model with the re-calculated value for triggerTime.
     */
    private fun calculateTriggerTime(
        model: Model<out Any>,
        time: Instant,
        context: C,
        reader: ModelReader<C, V>,
    ): Model<out Any> {
        val state = klerk.specification.getStateMachine(model).getStateByName(model.state)
        check(state is InstanceState)
        @Suppress("UNCHECKED_CAST")
        val instant = (state.atTimeFunction as? (LifecycleArgs<out Any, C, V>) -> Instant)?.invoke(
            LifecycleArgs(model, context, reader),
        ) ?: state.afterDuration?.let { time.plus(it) }
        return model.copy(timeTrigger = instant?.let { makeExactSerializable(it) })
    }

    /**
     * Produces a ProcessingData, which contains all the changes, jobs and actions that this command will lead to.
     * Note that the command may produce other commands, which will also be processed. [reader] must not enforce
     * authorization rules.
     */
    internal fun <T : Any, P> processPrimaryCommand(
        command: Command<T, P>,
        context: C,
        reader: ModelReader<C, V>,
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
        return readWriteLock.withRead {
            val reader = ReaderWithoutAuth(klerk)
            val context = klerk.specification.systemContextProvider.invoke()
            process(processingData, context, reader, isPrimary = true, options, time)
        }
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
     * [Primary] is the type of the original state machine. It is not used in the processing but needed in the
     * response. [isPrimary] is true for the initial state machine (and initial command/time-trigger).
     */
    private fun <Primary : Any> process(
        processingData: ProcessingData<Primary, C, V>,
        context: C,
        reader: ModelReader<C, V>,
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
            val created = after.aggregatedModelState[after.createdModels.singleOrNull()]?.id as? ModelID<Primary>
            after.copy(primaryModel = created)
        } else {
            after
        }
        return process(withCorrectPrimary, context, reader, isPrimary = false, options, time)
    }

    /**
     * Transforms the processingData so that it the remaining time-trigger or first remaining command is replaced
     * with one block.
     */
    private fun <Primary : Any> prepareNextBlock(
        processingData: ProcessingData<Primary, C, V>,
        options: ProcessingOptions,
        reader: ModelReader<C, V>,
        context: C,
    ): ProcessingData<Primary, C, V> {
        // is there a timeTrigger?
        processingData.remainingTimeTrigger?.let { model ->
            val block = when (val state = klerk.specification.getStateMachine(model).getStateByName(model.state)) {
                is InstanceState -> state.timeBlock
                is VoidState -> throw IllegalStateException()
            }
            return processingData.copy(
                remainingTimeTrigger = null,
                remainingBlocks = listOf(requireNotNull(block)),
                currentModel = model.id,
            )
        }

        // no, let's take the first remaining command
        check(processingData.remainingCommands.isNotEmpty())
        val currentCommand = processingData.remainingCommands.first()
        val remaining = processingData.remainingCommands.drop(1)
        logger.log(DebugOption.Sequence, options) { "Processing command ${currentCommand.event}" }

        val commandValidationProblems = klerk.validator.validateCommand(currentCommand, reader, context)
        if (commandValidationProblems.isNotEmpty()) {
            return ProcessingData(problems = commandValidationProblems)
        }

        val modelId = currentCommand.model
        val model = processingData.aggregatedModelState[modelId] ?: currentCommand.model?.let { reader.getOrNull(it) }
        val state = klerk.specification.getStateMachineForEvent(currentCommand.event).getStateByName(model?.state)
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
     * [context] is null when we are processing the state machine due to a time-trigger (i.e. not by a command). But
     * whenever there is an Event, we know that it was caused via a command and therefore we know that [context] is
     * non-null.
     */
    private fun <Primary : Any, T : Any, P> processBlocks(
        processingData: ProcessingData<Primary, C, V>,
        context: C,
        reader: ModelReader<C, V>,
        options: ProcessingOptions,
        time: Instant,
    ): ProcessingData<Primary, C, V> {
        if (processingData.remainingBlocks.isEmpty()) {
            return processingData
        }
        val currentBlock = processingData.remainingBlocks.first()
        val remaining = processingData.remainingBlocks.drop(1)
        logger.log(DebugOption.Sequence, options) { "Processing block $currentBlock" }

        val processingOptions = EventProcessingOptions(
            disregardPreventingRules = false,
            idProvider = IdFactory(klerk.jobs::isJobIdAvailable),
            performActions = false,
            preventModelUpdates = false,
        )
        val modelId = processingData.currentCommand?.model ?: processingData.currentModel

        @Suppress("UNCHECKED_CAST")
        val model = (processingData.aggregatedModelState[modelId] ?: modelId?.let { reader.getOrNull(it) }) as? Model<T>
        val stateMachine = model?.let { klerk.specification.getStateMachine(it) }
            ?: klerk.specification.getStateMachineForEvent(checkNotNull(processingData.currentCommand).event)

        @Suppress("UNCHECKED_CAST")
        val view = stateMachine.modelViews as ModelViews<T, C>
        val result = when (currentBlock) {

            is Block.VoidLifecycleBlock -> {
                ProcessingData(currentBlock = currentBlock)
            }

            is Block.VoidEventBlock<*, *, *, C, V> -> {
                @Suppress("UNCHECKED_CAST")
                processingData.currentCommand as Command<T, P>
                val args = VoidEventArgs(
                    requireNotNull(processingData.currentCommand),
                    context,
                    reader,
                )

                @Suppress("UNCHECKED_CAST")
                currentBlock.executables.map { it as Executable<T, VoidEventArgs<T, P, C, V>, C, V> }
                    .filter { it.onCondition?.invoke(args) ?: true }
                    .map { it.process(args, processingOptions, view, klerk.specification, processingData) }
                    .reduceOrNull { acc, delta -> acc.merge(delta) }
                    ?: ProcessingData(currentBlock = currentBlock)
            }

            is Block.InstanceEventBlock<*, *, *, C, V> -> {
                @Suppress("UNCHECKED_CAST")
                processingData.currentCommand as Command<T, P>
                val command = requireNotNull(processingData.currentCommand)
                val args = InstanceEventArgs(requireNotNull(model), command, context, reader)

                @Suppress("UNCHECKED_CAST")
                currentBlock.executables.map { it as Executable<T, InstanceEventArgs<T, P, C, V>, C, V> }
                    .filter { it.onCondition?.invoke(args) ?: true }
                    .map { it.process(args, processingOptions, view, klerk.specification, processingData) }
                    .reduceOrNull { acc, delta -> acc.merge(delta) }
                    ?: ProcessingData(currentBlock = currentBlock)
            }

            is Block.InstanceLifecycleBlock -> {
                val args = LifecycleArgs(requireNotNull(model), context, reader)
                @Suppress("UNCHECKED_CAST")
                currentBlock.executables.map { it as Executable<T, LifecycleArgs<T, C, V>, C, V> }
                    .filter { it.onCondition?.invoke(args) ?: true }
                    .map { it.process(args, processingOptions, view, klerk.specification, processingData) }
                    .reduceOrNull { acc, delta -> acc.merge(delta) }
                    ?: ProcessingData(currentBlock = currentBlock)
            }
        }
        val updatedDelta = processingData.copy(remainingBlocks = remaining)      // keep the old (except current)
            .merge(result, currentBlock.type == Exit)                          // new block may be added here
        val withTimeTriggers = withTimeTriggers<Primary, T>(updatedDelta, time, context, reader)
        return processBlocks<Primary, T, P>(withTimeTriggers, context, reader, options, time)
    }

    /**
     * Returns an updated ProcessingData in which modifiedModels have a correct timeTrigger value
     */
    private fun <Primary : Any, T : Any> withTimeTriggers(
        processingData: ProcessingData<Primary, C, V>,
        time: Instant,
        context: C,
        reader: ModelReader<C, V>,
    ): ProcessingData<Primary, C, V> {
        val newTimeTriggers =
            processingData.transitions
                .map { processingData.aggregatedModelState.getValue(it) }
                .map { model ->
                    @Suppress("UNCHECKED_CAST")
                    model.id to findTimeTrigger(model as Model<T>, LifecycleArgs(model, context, reader), time)
                }

        return processingData.copy(timeTriggers = newTimeTriggers.associate { it })
    }

    private fun <T : Any> findTimeTrigger(
        newModel: Model<T>, transformedArgs: LifecycleArgs<T, C, V>, time: Instant,
    ): Instant? {
        val state = klerk.specification.getStateMachine(newModel).getStateByName(newModel.state)
        check(state is InstanceState)
        val instant = state.atTimeFunction?.invoke(transformedArgs) ?: state.afterDuration?.let { time.plus(it) }
        return instant?.let { makeExactSerializable(it) }
    }

}

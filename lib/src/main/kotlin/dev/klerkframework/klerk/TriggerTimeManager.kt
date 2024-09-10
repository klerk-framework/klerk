package dev.klerkframework.klerk

import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

internal interface TriggerTimeManager {

    fun init(models: List<Model<out Any>>)
    fun start()
    fun stop()

}

internal class TriggerTimeManagerImpl<C : KlerkContext, V>(
    val eventsManager: EventsManagerImpl<C, V>,
    val readWriteLock: ReadWriteLock,
    val klerk: KlerkImpl<C, V>
) : TriggerTimeManager {

    private var worker: Job? = null
    private lateinit var timeTriggers: PriorityBlockingQueue<TimeTriggerModel>

    override fun init(models: List<Model<out Any>>) {
        require(worker == null)
        val modelsWithTriggers = models.filter { it.timeTrigger != null }
        timeTriggers = PriorityBlockingQueue<TimeTriggerModel>(max(modelsWithTriggers.size, 1000))
        modelsWithTriggers.forEach {
            timeTriggers.add(TimeTriggerModel(it.timeTrigger!!, it.id.toInt()))
        }
    }

    fun handle(delta: ProcessingData<out Any, C, V>) {
        val deleted = delta.deletedModels.map { it.toInt() }
        timeTriggers.removeIf { deleted.contains(it.id) }

        val newTriggers = delta.createdModels
            .union(delta.transitions)
            .map { requireNotNull(delta.aggregatedModelState[it]) }
            .filter { it.timeTrigger != null }
            .map { TimeTriggerModel(it.timeTrigger!!, it.id.toInt()) }
        timeTriggers.addAll(newTriggers)
    }

    override fun start() {
        thread(isDaemon = true) {
            runBlocking {
                worker = launch {
                    while (true) {
                        try {
                            do {
                                val maybeMoreToProcess = processQueue()
                            } while (maybeMoreToProcess)
                        } catch (e: Exception) {
                            logger.error(e) { "Could not handle triggers" }
                        }
                        delay(5.seconds)
                    }
                }
            }
        }
    }

    override fun stop() {
        worker?.cancel()
    }

    /**
     * Returns true if there may be another item to process now
     */
    private suspend fun processQueue(): Boolean {
        val now = Clock.System.now()
        val next = timeTriggers.peek()
        if (next == null || next.instant > now) {
            return false
        }
        val current = requireNotNull(timeTriggers.poll())
        readWriteLock.acquireRead()
        val reader = ReaderWithoutAuth<C, V>(klerk)
        val model = reader.getOrNull(ModelID(current.id))
        if (model == null) {
            logger.error { "Could not find model ${current.id}" }
            return true
        }
        readWriteLock.releaseRead()
        if (model.timeTrigger == null || model.timeTrigger > now) {
            logger.error { "I thought that model ${model.id} should be time-triggered but on a closer look it is not the case. Times: ${model.timeTrigger?.toEpochMilliseconds()} - ${now.toEpochMilliseconds()}" }
            return true
        }
        eventsManager.modelTriggeredByTime(model, now)
        return true
    }

}

private data class TimeTriggerModel(val instant: Instant, val id: Int) : Comparable<TimeTriggerModel> {
    override fun compareTo(other: TimeTriggerModel): Int = instant.compareTo(other.instant)
}

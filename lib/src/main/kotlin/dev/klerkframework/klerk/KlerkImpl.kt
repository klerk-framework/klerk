package dev.klerkframework.klerk

import dev.klerkframework.klerk.attacheddata.AttachedDataImpl
import kotlinx.coroutines.runBlocking
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.job.JobManagerImpl
import dev.klerkframework.klerk.log.ActivityLogImpl
import dev.klerkframework.klerk.log.LogCommandSucceeded
import dev.klerkframework.klerk.log.LogKlerkStarted
import dev.klerkframework.klerk.log.LogKlerkStopped
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.KlerkModelsImpl
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.klerk.validation.Validator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTime


/**
 * The implementation behind a [Klerk] handle, for the framework's own use — a job step is handed the interface, but
 * Klerk's own job types need the internals.
 */
internal fun <C : KlerkContext, V> Klerk<C, V>.impl(): KlerkImpl<C, V> = this as KlerkImpl<C, V>

internal class KlerkImpl<C : KlerkContext, V>(
    override val specification: Specification<C, V>,
    override val settings: KlerkSettings,
) :
    Klerk<C, V> {

    override val jobs = JobManagerImpl<C, V>(this)

    internal val readWriteLock = ReadWriteLock()
    internal val modelsManager = KlerkModelsImpl<C, V>(this, readWriteLock)
    internal val attachedDataImpl = AttachedDataImpl<C, V>(this, readWriteLock, settings)
    internal val eventsManager =
        EventsManagerImpl<C, V>(specification, this, readWriteLock, settings, jobs, attachedDataImpl)
    private val klerkMeta = KlerkMetaImpl(this)
    internal val activityLogImpl: ActivityLogImpl = ActivityLogImpl()
    internal val validator = Validator(this)

    init {
        specification.initialize(settings)
        ModelCache.initialize(settings.persistence, settings.modelCache)
        ModelCache.initMetrics(settings.meterRegistry)

        for (managed in specification.managedModels) {
            managed.views.initialize()
            for (view in managed.views.views) {
                view.setIdBase(managed.kClass.simpleName)
            }
            managed.stateMachine.setView(managed.views)
        }

    }


    private fun modelViewProvider(modelType: String): ModelViews<*, C> =
        specification.managedModels.find { it.kClass.simpleName == modelType }?.views
            ?: error("Can't find model view for type '$modelType'")



    override val modelChanges = modelsManager
    override val unsafe = modelsManager

    override val meta = klerkMeta

    override val activityLog = activityLogImpl

    override val attachedData = attachedDataImpl

    override suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions,
    ): CommandResult<T> {
        val result = try {
            eventsManager.handle(command, context, options)
        } catch (e: Exception) {
            logger.error(e) { "Bug in Klerk: Could not process command (${command.event})" }
            return CommandResult.Failure(listOf(InternalProblem(DefaultKlerkTranslation.internalError)))
        }

        if (result is CommandResult.Success<T>) {
            activityLog.add(LogCommandSucceeded(command, context, result))
        }

        return result
    }

    override suspend fun <T> read(context: C, readFunction: Reader<C, V>.() -> T): T =
        modelsManager.read(context, readFunction)


    override suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, V>.() -> T): T =
        modelsManager.readSuspend(context, readFunction)


}

internal class KlerkMetaImpl<V, C : KlerkContext>(private val klerk: KlerkImpl<C, V>) : KlerkMeta {
    private val state = AtomicInteger(0)    // 0 = not started, 1 = started, 2 = stopped

    override suspend fun start(installShutdownHook: Boolean) {
        if (state.compareAndSet(0, 1)) {
            if (installShutdownHook) {
                Runtime.getRuntime().addShutdownHook(object : Thread() {
                    override fun run() {
                        runBlocking { klerk.meta.stop() }
                    }
                })
            }

            val startTime = measureTime {
                ModelCache.clear()
                klerk.settings.persistence.setSpecification(klerk.specification)

                val migrations = klerk.specification.migrationSteps.toMutableList()
                migrations.removeIf { it.migratesToVersion <= klerk.settings.persistence.currentModelSchemaVersion }
                if (migrations.isNotEmpty()) {
                    klerk.settings.persistence.migrate(migrations)
                }

                klerk.eventsManager.start()
                klerk.attachedDataImpl.start()
                // Jobs start last: reloading them may need models and attached data to be in place already.
                klerk.jobs.start()
                for (plugin in klerk.specification.plugins) {
                    logger.info { "Initializing plugin: ${plugin.name}" }
                    plugin.start(klerk)
                }
            }
            klerk.activityLog.add(LogKlerkStarted(startTime))
        } else {
            throw IllegalStateException("Klerk has already been started")
        }
    }

    override suspend fun stop() {
        val previousState = state.getAndSet(2)
        if (previousState == 0) {
            throw IllegalStateException("Klerk has not been started")
        }
        if (previousState == 2) {
            return  // already stopped
        }
        for (plugin in klerk.specification.plugins.asReversed()) {
            try {
                plugin.stop()
            } catch (e: Exception) {
                logger.error(e) { "Plugin ${plugin.name} failed to stop" }
            }
        }
        klerk.eventsManager.stop()
        klerk.jobs.stop()    // stopping jobs after events in case a job is created that must execute on this instance.
        klerk.activityLog.add(LogKlerkStopped())
    }

    override val modelsCount: Int
        get() = ModelCache.count

}

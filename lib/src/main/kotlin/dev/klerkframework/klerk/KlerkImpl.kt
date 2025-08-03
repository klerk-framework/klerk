package dev.klerkframework.klerk

import dev.klerkframework.klerk.actions.JobManagerImpl
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.keyvaluestore.KeyValueStoreImpl
import dev.klerkframework.klerk.log.KlerkLogImpl
import dev.klerkframework.klerk.log.LogCommandSucceeded
import dev.klerkframework.klerk.log.LogKlerkStarted
import dev.klerkframework.klerk.log.LogKlerkStopped
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.KlerkModelsImpl
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.klerk.validation.Validator
import kotlin.time.Duration
import kotlin.time.measureTime


internal class KlerkImpl<C : KlerkContext, V>(override val config: Config<C, V>, val settings: KlerkSettings) :
    Klerk<C, V> {

    override val jobs = JobManagerImpl<C, V>(this)

    private val readWriteLock = ReadWriteLock()
    private val modelsManager = KlerkModelsImpl<C, V>(this, readWriteLock)
    internal val eventsManager = EventsManagerImpl<C, V>(config, this, readWriteLock, settings, jobs)
    private val klerkMeta = KlerkMetaImpl(this)
    private val klerkKeyValueStore = KeyValueStoreImpl(config)
    private val klerkLog = KlerkLogImpl()
    internal val validator = Validator(this)

    init {
        config.initialize()
        ModelCache.initMetrics(config.meterRegistry)
        /*
        SingletonStuff.views = views as Any
        SingletonStuff.readModelPositiveRules = authorizationReadPositiveRules as Set<(UserIdentity, Model<out Any>, Any) -> PositiveAuthorization>
        SingletonStuff.readModelNegativeRules = authorizationReadNegativeRules as Set<(UserIdentity, Model<out Any>, Any) -> NegativeAuthorization>
        SingletonStuff.eventPositiveRules = authorizationEventPositiveRules as Set<(UserIdentity, IEvent, Any) -> PositiveAuthorization>
        SingletonStuff.eventNegativeRules = authorizationEventNegativeRules as Set<(UserIdentity, IEvent, Any) -> NegativeAuthorization>

         */

        config.managedModels.forEach { managed ->
            managed.collections.initialize()
            managed.collections.getCollections().forEach { it.setIdBase(managed.kClass.simpleName) }
            managed.stateMachine.setView(managed.collections)
        }

    }


    private fun modelViewProvider(modelType: String): ModelCollections<*, C> {
        return config.managedModels.find { it.kClass.simpleName == modelType }?.collections
            ?: throw RuntimeException("Can't find model view for type '$modelType'")
    }

    /*
        intellij gillar inte denna. Men den används inte?
        private inline fun <reified T : Any> getStateMachine(): StateMachine<*, *, V> {
            return config.managedModels.find { it.kClass == T::class }!!.stateMachine
        }

     */


    override val events = eventsManager

    override val models = modelsManager

    override val meta = klerkMeta

    override val log = klerkLog

    override val keyValueStore = klerkKeyValueStore

    override suspend fun <T : Any, P> handle(
        command: Command<T, P>,
        context: C,
        options: ProcessingOptions
    ): CommandResult<T, C, V> {
        val result = try {
            events.handle(command, context, options)
        } catch (e: Exception) {
            logger.error(e) { "Bug in Klerk: Could not process command (${command.event})" }
            return CommandResult.Failure(listOf(InternalProblem(DefaultKlerkTranslation.internalError)))
        }

        if (result is CommandResult.Success<T, C, V>) {
            try {
                result.unmanagedJobs.forEach { it.f.invoke() }
            } catch (e: Exception) {
                logger.warn(e) {
                    "The command was successful but an exception was thrown when calling an action " +
                            "function. It is considered bad practice to throw in any function provided to Klerk."
                }
            }
            result.jobs.forEach { jobs.scheduleAction(it) }
            log.add(LogCommandSucceeded(command, context, result))
        }

        return result
    }

    override suspend fun <T> readSuspend(context: C, readFunction: suspend Reader<C, V>.() -> T): T =
        models.readSuspend(context, readFunction)

    override suspend fun <T> read(context: C, readFunction: Reader<C, V>.() -> T): T =
        models.read(context, readFunction)

}

internal class KlerkMetaImpl<V, C : KlerkContext>(private val klerk: KlerkImpl<C, V>) : KlerkMeta {
    override suspend fun start(installShutdownHook: Boolean) {
        require(klerk.settings.eraseAuditLogAfterModelDeletion == null || klerk.settings.eraseAuditLogAfterModelDeletion == Duration.ZERO) { "settings.eraseAuditLogAfterModelDeletion can only be null or zero" }

        if (installShutdownHook) {
            Runtime.getRuntime().addShutdownHook(object : Thread() {
                override fun run() {
                    klerk.meta.stop()
                }
            })
        }

        val startTime = measureTime {
            ModelCache.clear()
            klerk.config.persistence.setConfig(klerk.config)

            val migrations = klerk.config.migrationSteps.toMutableList()
            migrations.removeIf { it.migratesToVersion <= klerk.config.persistence.currentModelSchemaVersion }
            if (migrations.isNotEmpty()) {
                klerk.config.persistence.migrate(migrations)
            }

            klerk.eventsManager.start()
            klerk.config.plugins.forEach {
                logger.info { "Initializing plugin: ${it.name}" }
                it.start(klerk)
            }
        }
        klerk.log.add(LogKlerkStarted(startTime))
    }

    override fun stop() {
        klerk.eventsManager.stop()
        //   JobManagerImpl.stop() // stopping jobs after events in case a job is created that must execute on this instance.
        klerk.log.add(LogKlerkStopped())
    }

}
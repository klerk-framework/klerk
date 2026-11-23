package dev.klerkframework.klerk

import dev.klerkframework.klerk.misc.requireNamedRule
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.subscription.ChangeHub
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * How long the event log keeps what it records. Declared with `eventLogRetention(...)` in the specification, where it
 * is required: keeping personal data forever is a decision the application must make explicitly.
 *
 * Every duration is `null` to keep the data forever, [Duration.ZERO] to erase it immediately, or at least one hour;
 * Klerk erases what has expired once an hour, so data is erased at most an hour after its retention has passed.
 *
 * Erasure does not reach backups of the database.
 *
 * [afterModelDeletion] is how long the entries of a model are kept after the model has been deleted. Only entries whose
 * [EventLogEntry.model] is the deleted model are erased; parameters of commands on other models that mention it are
 * not, which is what [paramsAndExtra] is for. Until they are erased, the deleted model's id is not given to a new
 * model.
 *
 * [paramsAndExtra] is how long [EventLogEntry.params] and [EventLogEntry.extra] are kept, counted from
 * [EventLogEntry.time]. After that, the entry still says who did what to which model, and when, but no longer with
 * which parameters. With [Duration.ZERO], they are never stored.
 *
 * Give the same duration to everything:
 *
 * ```kotlin
 * eventLogRetention(afterModelDeletion = 30.days, paramsAndExtra = 365.days)
 * ```
 *
 * or decide per model and per event with named functions:
 *
 * ```kotlin
 * eventLogRetention(afterModelDeletion = ::keepAfterDeletion, paramsAndExtra = ::keepParams)
 *
 * fun keepAfterDeletion(args: DeletedModelArgs): Duration? = when (args.model.props) {
 *     is Document -> Duration.ZERO
 *     else -> 5.years
 * }
 *
 * fun keepParams(event: EventReference): Duration? = if (event.modelName == "Customer") 30.days else 1.years
 * ```
 */
public class EventLogRetention private constructor(
    private val afterModelDeletionRule: (DeletedModelArgs) -> Duration?,
    private val paramsAndExtraRule: (EventReference) -> Duration?,
    private val fixedAfterModelDeletion: Fixed?,
) {

    private class Fixed(val duration: Duration?)

    /** The same retention for every model and every event. */
    public constructor(afterModelDeletion: Duration?, paramsAndExtra: Duration?) : this(
        { afterModelDeletion },
        { paramsAndExtra },
        Fixed(afterModelDeletion),
    ) {
        validated("afterModelDeletion", afterModelDeletion)
        validated("paramsAndExtra", paramsAndExtra)
    }

    /**
     * Decided per deleted model and per event by [afterModelDeletion] and [paramsAndExtra], which must be named
     * function references. They run inside Klerk's command processing: keep them quick, and let them depend on nothing
     * but their argument. Durations that are not null, zero or at least one hour make the command fail.
     */
    public constructor(
        afterModelDeletion: (DeletedModelArgs) -> Duration?,
        paramsAndExtra: (EventReference) -> Duration?,
    ) : this(
        requireNamed(afterModelDeletion, "The afterModelDeletion rule of eventLogRetention"),
        requireNamed(paramsAndExtra, "The paramsAndExtra rule of eventLogRetention"),
        null,
    )

    /**
     * How long the log of [model] is kept after it is deleted. A model that was created and deleted by the same command
     * is not known to the rule; it gets the fixed duration if there is one, otherwise zero.
     */
    internal fun afterModelDeletion(model: Model<out Any>?): Duration? = when {
        model != null -> validated("afterModelDeletion", afterModelDeletionRule(DeletedModelArgs(model)))
        fixedAfterModelDeletion != null -> fixedAfterModelDeletion.duration
        else -> Duration.ZERO
    }

    /** How long the parameters and extra of entries of [event] are kept. */
    internal fun paramsAndExtra(event: EventReference): Duration? =
        validated("paramsAndExtra", paramsAndExtraRule(event))

    private fun validated(name: String, retention: Duration?): Duration? {
        if (retention == null || retention == Duration.ZERO || retention >= SWEEP_INTERVAL) {
            return retention
        }
        throw IllegalConfigurationException(
            KlerkErrorCode.InvalidEventLogRetention,
            "Event log retention '$name' is $retention, but must be null, zero or at least $SWEEP_INTERVAL",
        )
    }

    internal companion object {
        val SWEEP_INTERVAL: Duration = 1.hours

        private fun <A> requireNamed(rule: (A) -> Duration?, where: String): (A) -> Duration? {
            requireNamedRule(rule, where)
            return rule
        }
    }
}

/** What an `afterModelDeletion` rule of [EventLogRetention] is told about the model that was deleted. */
public class DeletedModelArgs(
    /** The model as it was just before the command deleted it. */
    public val model: Model<out Any>,
)

/** Erases what [EventLogRetention] says has expired, at startup and then every [EventLogRetention.SWEEP_INTERVAL]. */
internal class EventLogRetentionManager(
    private val retention: EventLogRetention,
    private val settings: KlerkSettings,
    private val changeHub: ChangeHub,
) {
    private var scope: CoroutineScope? = null

    /**
     * The deleted models whose event log has not been erased yet, with when it is to be erased ([NEVER] if it is kept
     * forever). Their ids must not be given to new models, which would otherwise inherit the log. Kept in memory so
     * that allocating an id needs no storage lookup.
     */
    private val tombstones = ConcurrentHashMap<Int, Instant>()

    /** True if [id] belonged to a deleted model whose event log still exists. */
    fun isRetired(id: Int): Boolean = tombstones.containsKey(id)

    fun start() {
        tombstones.clear()
        tombstones.putAll(settings.persistence.readEventLogTombstones())
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            while (true) {
                try {
                    sweep()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Could not erase expired event log data" }
                }
                delay(EventLogRetention.SWEEP_INTERVAL)
            }
        }
    }

    fun stop() {
        scope?.cancel()
    }

    fun sweep() {
        val now = settings.now()
        eraseDeletedModels(eraseAtOrBefore = now)
        val cutoff = { event: EventReference -> retention.paramsAndExtra(event)?.let { now - it } }
        settings.persistence.eraseEventLogParamsAndExtra(cutoff)
        changeHub.eraseParamsAndExtra(cutoff)
    }

    /** When the event log of a model deleted now is to be erased, according to [retention]. */
    fun eraseAtForDeletion(model: Model<out Any>?, deletedAt: Instant): Instant =
        retention.afterModelDeletion(model)?.let { deletedAt + it } ?: NEVER

    /**
     * Called after a commit that deleted models, with their tombstones. Under the command mutex, so no id can be
     * allocated before the tombstones are known. Logs that are already due are erased at once.
     */
    fun afterDeletion(deleted: Map<ModelID<out Any>, Instant>) {
        for ((id, eraseAt) in deleted) {
            tombstones[id.value] = eraseAt
        }
        val now = settings.now()
        if (deleted.values.any { it <= now }) {
            eraseDeletedModels(eraseAtOrBefore = now)
        }
    }

    private fun eraseDeletedModels(eraseAtOrBefore: Instant) {
        settings.persistence.eraseEventLogsOfDeletedModels(eraseAtOrBefore)
        val erased = tombstones.filterValues { it <= eraseAtOrBefore }.keys
        tombstones.keys.removeAll(erased)
        changeHub.eraseEventLogs(erased)
    }

    companion object {
        /** The erasure time of a log that is kept forever. */
        val NEVER: Instant = Instant.DISTANT_FUTURE
    }
}

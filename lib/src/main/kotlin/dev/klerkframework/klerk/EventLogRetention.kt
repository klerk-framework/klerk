package dev.klerkframework.klerk

import dev.klerkframework.klerk.storage.EventLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * How long the event log keeps what it records. Declared with `eventLogRetention(...)` in the specification, where it
 * is required: keeping personal data forever is a decision the application must make explicitly.
 *
 * `null` keeps the data forever. [Duration.ZERO] erases it immediately. Any other value must be at least one hour;
 * Klerk erases what has expired once an hour, so data is erased at most an hour after its retention has passed.
 *
 * Erasure does not reach backups of the database.
 *
 * [afterModelDeletion] is how long the entries of a model are kept after the model has been deleted. Only entries whose
 * [EventLogEntry.model] is the deleted model are erased; parameters of commands on other models that mention it are
 * not, which is what [paramsAndExtra] is for.
 *
 * [paramsAndExtra] is how long [EventLogEntry.params] and [EventLogEntry.extra] are kept, counted from
 * [EventLogEntry.time]. After that, the entry still says who did what to which model, and when, but no longer with
 * which parameters. With [Duration.ZERO], they are never stored.
 *
 * ```kotlin
 * eventLogRetention(afterModelDeletion = 30.days, paramsAndExtra = 365.days)
 * ```
 */
public data class EventLogRetention(val afterModelDeletion: Duration?, val paramsAndExtra: Duration?) {
    init {
        requireValid("afterModelDeletion", afterModelDeletion)
        requireValid("paramsAndExtra", paramsAndExtra)
    }

    private fun requireValid(name: String, retention: Duration?) {
        if (retention == null || retention == Duration.ZERO || retention >= SWEEP_INTERVAL) {
            return
        }
        throw IllegalConfigurationException(
            KlerkErrorCode.InvalidEventLogRetention,
            "Event log retention '$name' is $retention, but must be null, zero or at least $SWEEP_INTERVAL",
        )
    }

    internal companion object {
        val SWEEP_INTERVAL: Duration = 1.hours
    }
}

/** Erases what [EventLogRetention] says has expired, at startup and then every [EventLogRetention.SWEEP_INTERVAL]. */
internal class EventLogRetentionManager(
    private val retention: EventLogRetention,
    private val settings: KlerkSettings,
) {
    private var scope: CoroutineScope? = null

    private val needsSweeping: Boolean =
        retention.afterModelDeletion != null || (retention.paramsAndExtra ?: Duration.ZERO) > Duration.ZERO

    fun start() {
        if (!needsSweeping) {
            return
        }
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
        retention.afterModelDeletion?.let {
            settings.persistence.eraseEventLogsOfDeletedModels(deletedAtOrBefore = now - it)
        }
        retention.paramsAndExtra?.takeIf { it > Duration.ZERO }?.let {
            settings.persistence.eraseEventLogParamsAndExtra(before = now - it)
        }
    }

    /** Called after a commit that deleted models, so that [Duration.ZERO] erases immediately. */
    fun afterDeletion() {
        if (retention.afterModelDeletion == Duration.ZERO) {
            settings.persistence.eraseEventLogsOfDeletedModels(deletedAtOrBefore = settings.now())
        }
    }
}

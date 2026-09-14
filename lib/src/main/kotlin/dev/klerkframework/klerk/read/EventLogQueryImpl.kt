package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.EventLogRuleArgs
import dev.klerkframework.klerk.EventLogEntryQuery
import dev.klerkframework.klerk.EventLogQuery
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.impl
import dev.klerkframework.klerk.storage.EventLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Built inside a read block by [Reader.eventLog], which is where the sequence number is captured and authorization is
 * checked. Reading the entries themselves is deferred to [get] so that no database query happens under the read lock.
 */
internal class EventLogQueryImpl<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val modelId: Int?,
    private val after: Instant,
    private val before: Instant,
    private val upToSequenceNumber: Long,
) : EventLogQuery {

    override suspend fun get(): List<EventLogEntry> {
        ReadBlockGuard.checkNotInsideReadBlock(
            "EventLogQuery.get()",
            "Create the query inside the read block and call get() after it has ended.",
        )
        return withContext(Dispatchers.IO) {
            klerk.settings.persistence
                .readEventLog(modelId, after, before, upToSequenceNumber)
                .toList()
        }
    }
}

/** Built inside a read block by [Reader.eventLogEntry]. See [EventLogQueryImpl]. */
internal class EventLogEntryQueryImpl<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val sequenceNumber: Long,
    private val upToSequenceNumber: Long,
) : EventLogEntryQuery {

    override suspend fun get(): EventLogEntry? {
        ReadBlockGuard.checkNotInsideReadBlock(
            "EventLogEntryQuery.get()",
            "Create the query inside the read block and call get() after it has ended.",
        )
        if (sequenceNumber > upToSequenceNumber) {
            return null
        }
        return withContext(Dispatchers.IO) {
            klerk.settings.persistence.readEventLogEntry(sequenceNumber)
        }
    }
}

/**
 * The shared part of `Reader.eventLog`: captures the sequence number of the last commit that is visible to this read
 * block, so that a commit already written to storage but not yet applied to memory is excluded.
 */
internal fun <C : KlerkContext, V> eventLogQuery(
    klerk: Klerk<C, V>,
    id: ModelID<out Any>?,
    after: Instant,
    before: Instant,
): EventLogQuery = EventLogQueryImpl(
    klerk = klerk,
    modelId = id?.value,
    after = after,
    before = before,
    upToSequenceNumber = klerk.impl().eventsManager.visibleSequenceNumber,
)

/** The shared part of `Reader.eventLogEntry`. See [eventLogQuery]. */
internal fun <C : KlerkContext, V> eventLogEntryQuery(
    klerk: Klerk<C, V>,
    sequenceNumber: Long,
): EventLogEntryQuery = EventLogEntryQueryImpl(
    klerk = klerk,
    sequenceNumber = sequenceNumber,
    upToSequenceNumber = klerk.impl().eventsManager.visibleSequenceNumber,
)

/** @throws AuthorizationException if the actor is not allowed to read the event log. */
internal fun <C : KlerkContext, V> checkEventLogAuthorization(
    klerk: Klerk<C, V>,
    context: C,
    withoutAuth: ReaderWithoutAuth<C, V>,
) {
    val args = EventLogRuleArgs(context, withoutAuth)
    val authorization = klerk.specification.authorization
    if (authorization.eventLogPositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
        throw AuthorizationException(KlerkErrorCode.EventLogPositiveAuthorizationMissing, "Not allowed to read event log")
    }
    if (authorization.eventLogNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
        throw AuthorizationException(KlerkErrorCode.EventLogNegativeAuthorizationExist, "Not allowed to read event log")
    }
}

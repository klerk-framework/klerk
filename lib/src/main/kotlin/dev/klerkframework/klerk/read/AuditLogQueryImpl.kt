package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.ArgContextReader
import dev.klerkframework.klerk.AuditLogQuery
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.impl
import dev.klerkframework.klerk.storage.AuditEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Built inside a read block by [Reader.auditLog], which is where the sequence number is captured and authorization is
 * checked. Reading the entries themselves is deferred to [get] so that no database query happens under the read lock.
 */
internal class AuditLogQueryImpl<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val modelId: Int?,
    private val after: Instant,
    private val before: Instant,
    private val sequenceNumber: Long?,
    private val upToSequenceNumber: Long,
) : AuditLogQuery {

    override suspend fun get(): List<AuditEntry> {
        ReadBlockGuard.checkNotInsideReadBlock(
            "AuditLogQuery.get()",
            "Create the query inside the read block and call get() after it has ended.",
        )
        return withContext(Dispatchers.IO) {
            klerk.settings.persistence
                .readAuditLog(modelId, after, before, upToSequenceNumber, sequenceNumber)
                .toList()
        }
    }
}

/**
 * The shared part of `Reader.auditLog`: captures the sequence number of the last commit that is visible to this read
 * block, so that a commit already written to storage but not yet applied to memory is excluded.
 */
internal fun <C : KlerkContext, V> auditLogQuery(
    klerk: Klerk<C, V>,
    id: ModelID<out Any>?,
    after: Instant,
    before: Instant,
    sequenceNumber: Long?,
): AuditLogQuery = AuditLogQueryImpl(
    klerk = klerk,
    modelId = id?.value,
    after = after,
    before = before,
    sequenceNumber = sequenceNumber,
    upToSequenceNumber = klerk.impl().eventsManager.visibleSequenceNumber,
)

/** @throws AuthorizationException if the actor is not allowed to read the audit log. */
internal fun <C : KlerkContext, V> checkAuditLogAuthorization(
    klerk: Klerk<C, V>,
    context: C,
    withoutAuth: ReaderWithoutAuth<C, V>,
) {
    val args = ArgContextReader(context, withoutAuth)
    val authorization = klerk.spec.authorization
    if (authorization.eventLogPositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
        throw AuthorizationException(KlerkErrorCode.AuditPositiveAuthorizationMissing, "Not allowed to read audit log")
    }
    if (authorization.eventLogNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
        throw AuthorizationException(KlerkErrorCode.AuditNegativeAuthorizationExist, "Not allowed to read audit log")
    }
}

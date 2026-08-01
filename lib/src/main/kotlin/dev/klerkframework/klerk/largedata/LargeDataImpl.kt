package dev.klerkframework.klerk.largedata

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.LargeDataIdAllocator
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.misc.getCurrentInstant
import dev.klerkframework.klerk.read.ReadBlockGuard
import dev.klerkframework.klerk.read.ReaderWithoutAuth
import dev.klerkframework.klerk.storage.LargeDataDelta
import dev.klerkframework.klerk.storage.ModelCache
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant

/**
 * What is known about one piece of attached data, apart from the value itself.
 *
 * Keeping this in memory is not extra overhead: an id→owner map is needed anyway, both to reject a second model
 * claiming an owned id and to let [KlerkLargeData.get]'s authorization rule reach the owning model. Id allocation
 * probes the same map.
 *
 * @property owner the id of the owning model, or null while the data is unclaimed
 * @property expires when an unclaimed value is reaped. Null once claimed.
 */
internal data class LargeDataEntry(val owner: Int?, val authKey: String?, val expires: Instant?)

internal class LargeDataImpl<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val readWriteLock: ReadWriteLock,
    private val settings: KlerkSettings,
) : KlerkLargeData<C> {

    private val config get() = klerk.config

    // Both maps are rebuilt from storage at startup, like ModelCache. They are read and written from prepare (which
    // deliberately runs outside the serialized command path) as well as from commit, hence Concurrent.
    private val blobs = ConcurrentHashMap<Int, LargeDataEntry>()
    private val strings = ConcurrentHashMap<Int, LargeDataEntry>()

    private val allocator = LargeDataIdAllocator()
    private val lastReap = AtomicReference(Instant.DISTANT_PAST)

    /**
     * Rebuilds the in-memory state from storage. Rows that are unclaimed and already past their expiry are reaped
     * rather than reserved.
     */
    internal fun start() {
        val now = getCurrentInstant()
        config.persistence.deleteExpiredLargeData(now)
        val (blobRows, stringRows) = config.persistence.readAllLargeDataMetadata()
        blobs.clear()
        strings.clear()
        blobRows.forEach { (id, row) -> blobs[id] = LargeDataEntry(row.owner, row.authKey, row.expires) }
        stringRows.forEach { (id, row) -> strings[id] = LargeDataEntry(row.owner, row.authKey, row.expires) }
        lastReap.set(now)
        logger.info { "Attached data ready (${blobs.size} blobs, ${strings.size} strings)" }
    }

    override suspend fun prepare(value: InputStream, context: C, authKey: String?): LargeBlobID {
        authorizeWrite(context, authKey)
        val expires = reserveExpiry()
        val id = allocate(blobs, authKey, expires)
        try {
            config.persistence.insertLargeBlob(id, value, authKey, expires)
        } catch (e: Exception) {
            blobs.remove(id)
            throw e
        }
        return LargeBlobID(id)
    }

    override suspend fun prepare(value: String, context: C, authKey: String?): LargeStringID {
        authorizeWrite(context, authKey)
        val expires = reserveExpiry()
        val id = allocate(strings, authKey, expires)
        try {
            config.persistence.insertLargeString(id, value, authKey, expires)
        } catch (e: Exception) {
            strings.remove(id)
            throw e
        }
        return LargeStringID(id)
    }

    override suspend fun get(id: LargeBlobID, context: C): InputStream {
        val entry = authorizeRead(blobs[id.id], id, context)
        val row = config.persistence.getLargeBlob(id.id) ?: throw NoSuchElementException("No data found for id $id")
        check(entry.owner == row.owner) { "The in-memory state of blob $id does not match the database" }
        return row.value
    }

    override suspend fun get(id: LargeStringID, context: C): String {
        val entry = authorizeRead(strings[id.id], id, context)
        val row = config.persistence.getLargeString(id.id) ?: throw NoSuchElementException("No data found for id $id")
        check(entry.owner == row.owner) { "The in-memory state of string $id does not match the database" }
        return row.value
    }

    /**
     * The expiry an unclaimed value gets.
     *
     * Deliberately based on the real clock rather than `context.time`: the context clock is supplied by the caller and
     * must not be able to extend or shorten the claim window.
     */
    private fun reserveExpiry(): Instant = getCurrentInstant().plus(settings.unclaimedLargeDataLifetime)

    private suspend fun allocate(
        map: ConcurrentHashMap<Int, LargeDataEntry>,
        authKey: String?,
        expires: Instant
    ): Int {
        maybeReap()
        val entry = LargeDataEntry(owner = null, authKey = authKey, expires = expires)
        return allocator.getNextLargeDataID { candidate ->
            map.putIfAbsent(candidate, entry) == null
        }
    }

    /**
     * Removes expired unclaimed values. Cheap enough to do from [prepare], which is slow anyway, but not more often
     * than once per lifetime window.
     */
    private fun maybeReap() {
        val now = getCurrentInstant()
        val previous = lastReap.get()
        if (now < previous.plus(settings.unclaimedLargeDataLifetime)) {
            return
        }
        if (!lastReap.compareAndSet(previous, now)) {
            return
        }
        blobs.entries.removeIf { it.value.isExpired(now) }
        strings.entries.removeIf { it.value.isExpired(now) }
        config.persistence.deleteExpiredLargeData(now)
    }

    // ---------------------------------------------------------------- authorization

    private suspend fun authorizeWrite(context: C, authKey: String?) {
        if (context.actor == SystemIdentity) {
            return
        }
        val args = ArgsForLargeDataWrite(authKey, context, ReaderWithoutAuth<C, V>(klerk))
        // The reader is only sound while the lock is held, so it is used for the rule and nothing else — never across
        // the upload.
        readWriteLock.acquireRead()
        try {
            if (config.authorization.largeDataWritePositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
                throw AuthorizationException(
                    KlerkErrorCode.LargeDataWritePositiveAuthorizationMissing,
                    "Not allowed to prepare attached data"
                )
            }
            if (config.authorization.largeDataWriteNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
                throw AuthorizationException(
                    KlerkErrorCode.LargeDataWriteNegativeAuthorizationExist,
                    "Not allowed to prepare attached data"
                )
            }
        } finally {
            readWriteLock.releaseRead()
        }
    }

    /**
     * Applies the read rules, holding the read lock only for as long as they run — never across the returned stream.
     */
    private suspend fun authorizeRead(entry: LargeDataEntry?, id: Any, context: C): LargeDataEntry {
        ReadBlockGuard.checkNotInsideReadBlock("klerk.largeData.get")
        if (entry == null || entry.isExpired(getCurrentInstant())) {
            throw NoSuchElementException("No data found for id $id")
        }
        // Unclaimed data is not reachable: attached data is always read through the model that owns it.
        val ownerId = entry.owner ?: throw NoSuchElementException(
            "The data with id $id has not been attached to a model yet"
        )
        if (context.actor == SystemIdentity) {
            return entry
        }
        readWriteLock.acquireRead()
        try {
            val owner = ModelCache.getOrNull(ModelID<Any>(ownerId))
                ?: throw NoSuchElementException("Could not find the model owning the data with id $id")
            val args = ArgsForLargeDataRead(owner, entry.authKey, context, ReaderWithoutAuth<C, V>(klerk))
            if (config.authorization.largeDataReadPositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
                throw AuthorizationException(
                    KlerkErrorCode.LargeDataReadPositiveAuthorizationMissing,
                    "Not allowed to read attached data"
                )
            }
            if (config.authorization.largeDataReadNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
                throw AuthorizationException(
                    KlerkErrorCode.LargeDataReadNegativeAuthorizationExist,
                    "Not allowed to read attached data"
                )
            }
        } finally {
            readWriteLock.releaseRead()
        }
        return entry
    }

    // ---------------------------------------------------------------- commit-time diff

    /**
     * Works out what should happen to the attached data as a result of a command, by diffing each affected model's
     * large-data ids before and after.
     *
     * This one mechanism covers every lifecycle transition: ids that appear are claimed, ids that disappear are
     * deleted. Claim-on-create, claim-on-update, delete-on-null, delete-on-replace and delete-on-model-delete all fall
     * out of it. (A rule phrased as "the reference became null, so delete" would miss replacement and leak a value on
     * every change.)
     *
     * @return the changes to apply, or the problems that should fail the command.
     */
    internal fun <T : Any> planFor(delta: ProcessingData<T, C, V>): LargeDataPlan {
        val affected = delta.aggregatedModelState.keys.plus(delta.deletedModels)
        if (affected.isEmpty()) {
            return LargeDataPlan.Ok(LargeDataDelta())
        }

        val now = getCurrentInstant()
        val problems = mutableListOf<Problem>()
        val claimedBlobs = mutableMapOf<Int, Int>()
        val claimedStrings = mutableMapOf<Int, Int>()
        val deletedBlobs = mutableSetOf<Int>()
        val deletedStrings = mutableSetOf<Int>()

        affected.forEach { modelId ->
            val before = ModelCache.getOrNull(ModelID<Any>(modelId.value))
                ?.let { collectLargeDataIds(it.props) } ?: LargeDataIds.empty
            val after = if (delta.deletedModels.contains(modelId)) LargeDataIds.empty else
                delta.aggregatedModelState[modelId]?.let { collectLargeDataIds(it.props) } ?: LargeDataIds.empty

            claim(after.blobs.minus(before.blobs), modelId, blobs, claimedBlobs, now, "blob", problems)
            claim(after.strings.minus(before.strings), modelId, strings, claimedStrings, now, "string", problems)
            deletedBlobs.addAll(before.blobs.minus(after.blobs))
            deletedStrings.addAll(before.strings.minus(after.strings))
        }

        if (problems.isNotEmpty()) {
            return LargeDataPlan.Rejected(problems)
        }
        return LargeDataPlan.Ok(LargeDataDelta(claimedBlobs, claimedStrings, deletedBlobs, deletedStrings))
    }

    private fun claim(
        ids: Set<Int>,
        modelId: ModelID<out Any>,
        known: Map<Int, LargeDataEntry>,
        claims: MutableMap<Int, Int>,
        now: Instant,
        kind: String,
        problems: MutableList<Problem>
    ) {
        ids.forEach { id ->
            val entry = known[id]
            if (entry == null || entry.isExpired(now)) {
                problems.add(
                    StateProblem(
                        "There is no attached $kind with id $id (it may have expired)",
                        "The attached $kind with id $id does not exist or has expired, so $modelId cannot claim it",
                        KlerkErrorCode.LargeDataNotFound
                    )
                )
                return@forEach
            }
            val currentOwner = entry.owner ?: claims[id]
            if (currentOwner != null && currentOwner != modelId.value) {
                problems.add(
                    StateProblem(
                        "The attached $kind with id $id is already owned by another model",
                        "The attached $kind with id $id is owned by model $currentOwner, so $modelId cannot claim it",
                        KlerkErrorCode.LargeDataAlreadyOwned
                    )
                )
                return@forEach
            }
            claims[id] = modelId.value
        }
    }

    /**
     * Applies what [planFor] decided to the in-memory state. Must be called after the data has been persisted, while
     * the write lock is held.
     */
    internal fun applyToMemory(largeData: LargeDataDelta) {
        largeData.claimedBlobs.forEach { (id, owner) -> claimInMemory(blobs, id, owner) }
        largeData.claimedStrings.forEach { (id, owner) -> claimInMemory(strings, id, owner) }
        largeData.deletedBlobs.forEach { blobs.remove(it) }
        largeData.deletedStrings.forEach { strings.remove(it) }
    }

    private fun claimInMemory(map: ConcurrentHashMap<Int, LargeDataEntry>, id: Int, owner: Int) {
        val entry = map[id] ?: return
        map[id] = entry.copy(owner = owner, expires = null)
    }

}

internal fun LargeDataEntry.isExpired(now: Instant): Boolean = expires?.let { it < now } ?: false

internal sealed class LargeDataPlan {
    internal data class Ok(val delta: LargeDataDelta) : LargeDataPlan()
    internal data class Rejected(val problems: List<Problem>) : LargeDataPlan()
}

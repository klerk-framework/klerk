package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.storage.ModelCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.security.SecureRandom

/**
 * Shared by every allocator. Non-blocking and thread-safe; the strong (blocking) instance could stall command processing
 * on a machine short of entropy.
 */
private val idRandom = SecureRandom()

internal interface IdProvider {
    fun <T : Any> getNextModelID(): ModelID<T>
    fun getNextJobID(): JobID
}

/**
 * Allocates ids for attached data (see [dev.klerkframework.klerk.KlerkAttachedData]).
 *
 * There must be exactly one instance, since the mutex is what makes concurrent allocation safe.
 *
 * Unlike [IdFactory.getNextModelID], this cannot rely on being called from the serialized command path — preparing
 * attached data deliberately happens outside it, so two concurrent calls could otherwise pick the same id and one
 * upload would silently overwrite the other. The mutex is held only for a check-and-insert in an in-memory structure,
 * which is also cheaper than probing the database once per attempt.
 */
internal class AttachedDataIdAllocator {

    private val random = idRandom
    private val mutex = Mutex()

    /**
     * Returns a new id. [reserve] is called with a candidate id while the mutex is held. It should insert the id and
     * return true if the id was free, otherwise return false so that another candidate is tried.
     */
    suspend fun getNextAttachedDataID(reserve: (Int) -> Boolean): Int = mutex.withLock {
        while (true) {
            val randomInt = random.nextInt(0, Int.MAX_VALUE)
            if (reserve(randomInt)) {
                return@withLock randomInt
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}

/**
 * [isModelIdRetired] tells whether an id belonged to a deleted model whose event log still exists, so that a new model
 * does not inherit it. [modelIdCandidates] is overridable only so that a test can make collisions likely.
 */
internal class IdFactory(
    val isJobIdAvailable: (Long) -> Boolean,
    val isModelIdRetired: (Int) -> Boolean,
    private val modelIdCandidates: () -> Int = { idRandom.nextInt(0, Int.MAX_VALUE) },
) : IdProvider {

    private val log = KotlinLogging.logger {}
    private val random = idRandom

    override fun <T : Any> getNextModelID(): ModelID<T> {
        // We should switch to UInt so we can use the full range. However, there is a problem: KT-69674 (I haven't tried
        // to work around that problem, it is likely possible). And we cannot use negative numbers as it will introduce
        // minus signs.
        // If we ever switch to Long or ULong, think about:
        // * Perhaps use Long.MAX_VALUE since ULong.MAX_VALUE can't be stored in sqlite. (fixed now according to exposed
        // changelog) * We may want to switch to Long in the future if we need @JvmInline (see KT-69674).
        while (true) {
            val randomInt = modelIdCandidates()
            if (ModelCache.isIdAvailable(randomInt) && !isModelIdRetired(randomInt)) {
                return ModelID(randomInt)
            }
        }
    }

    override fun getNextJobID(): JobID {
        while (true) {
            val candidate = random.nextLong(0, Long.MAX_VALUE)
            if (isJobIdAvailable(candidate)) {
                return JobID(candidate)
            }
        }
    }
}

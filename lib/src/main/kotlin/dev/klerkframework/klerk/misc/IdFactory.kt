package dev.klerkframework.klerk.misc


import dev.klerkframework.klerk.JobManagerInternal
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.storage.ModelCache

import mu.KotlinLogging
import java.security.SecureRandom

internal interface IdProvider {
    fun <T : Any> getNextModelID(): ModelID<T>
    fun getNextJobID(): JobId
}

internal class IdFactory(val isJobIdAvailable: (Int) -> Boolean) : IdProvider {

    private val log = KotlinLogging.logger {}
    private val random = SecureRandom.getInstanceStrong()

    override fun <T : Any> getNextModelID(): ModelID<T> {

        // We should switch to UInt so we can use the full range. However, there is a problem: KT-69674 (I haven't tried
        // to work around that problem, it is likely possible). And we cannot use negative numbers as it will introduce
        // minus signs.
        // If we ever switch to Long or ULong, think about:
        // * Perhaps use Long.MAX_VALUE since ULong.MAX_VALUE can't be stored in sqlite. (fixed now according to exposed changelog)
        // * We may want to switch to Long in the future if we need @JvmInline (see KT-69674).
        val randomInt = random.nextInt(0, Int.MAX_VALUE)
        if (ModelCache.isIdAvailable(randomInt)) {
            return ModelID(randomInt)
        }
        return getNextModelID()
    }

    override fun getNextJobID(): JobId {
        val randomInt = random.nextInt(0, Int.MAX_VALUE)
        if (isJobIdAvailable(randomInt)) {
            return randomInt
        }
        return getNextJobID()
    }

}

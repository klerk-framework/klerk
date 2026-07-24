package dev.klerkframework.klerk.keyvaluestore

import dev.klerkframework.klerk.*
import java.io.InputStream
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal class KeyValueStoreImpl<C : KlerkContext, V>(private val config: Config<C, V>) : KlerkKeyValueStore<C> {

    private val random = SecureRandom.getInstanceStrong()

    override suspend fun put(
        value: String,
        ttl: Duration?
    ): StringKey {
        var id = random.nextLong(0, Int.MAX_VALUE.toLong()).toInt()
        while (config.persistence.getKeyValueString(id) != null) {
            id = random.nextLong(0, Int.MAX_VALUE.toLong()).toInt()
        }
        config.persistence.putKeyValue(id, value, ttl?.let { Clock.System.now().plus(it) })
        return StringKey(id)
    }

    override suspend fun put(
        value: Int,
        ttl: Duration?
    ): IntKey {
        var id = random.nextLong(0, Int.MAX_VALUE.toLong()).toInt()
        while (config.persistence.getKeyValueInt(id) != null) {
            id = random.nextLong(0, Int.MAX_VALUE.toLong()).toInt()
        }
        config.persistence.putKeyValue(id, value, ttl?.let { Clock.System.now().plus(it) })
        return IntKey(id)
    }

    override fun prepareBlob(value: InputStream): BlobToken {
        var id = random.nextLong(0, Int.MAX_VALUE.toLong()).toInt()
        while (config.persistence.getKeyValueBlob(id) != null) {
            id = random.nextLong(0, Int.MAX_VALUE.toLong()).toInt()
        }
        config.persistence.putKeyValue(id, value, Clock.System.now().plus(5.minutes))
        return BlobToken(id)
    }

    override suspend fun put(
        token: BlobToken,
        ttl: Duration?
    ): BlobKey {
        config.persistence.updateBlob(token.id, ttl?.let { Clock.System.now().plus(it) }, true)
        return BlobKey(token.id)
    }

    override suspend fun get(id: StringKey, context: C): String =
        checkTTL(
            config.persistence.getKeyValueString(id.id)
                ?: throw NoSuchElementException("No value found for id $id"), context, id.id
        )

    override suspend fun get(id: IntKey, context: C): Int =
        checkTTL(
            config.persistence.getKeyValueInt(id.id) ?: throw NoSuchElementException("No value found for id $id"),
            context,
            id.id
        )


    override suspend fun get(id: BlobKey, context: C): InputStream {
        val data =
            config.persistence.getKeyValueBlob(id.id) ?: throw NoSuchElementException("No value found for id $id")
        val active = data.third
        if (!active) {
            throw NoSuchElementException("No value found for id ${id.id}")
        }
        return checkTTL(data.first to data.second, context, id.id)
    }

    private fun <T> checkTTL(pair: Pair<T, Instant?>, context: C, id: Int): T {
        if (pair.second != null && pair.second!! < context.time) {
            throw NoSuchElementException("No value found for id $id")
        }
        return pair.first
    }
}

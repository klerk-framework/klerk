package dev.klerkframework.klerk.keyvaluestore

import dev.klerkframework.klerk.BinaryKeyValueID
import dev.klerkframework.klerk.BlobToken
import dev.klerkframework.klerk.Config
import dev.klerkframework.klerk.IntKeyValueID
import dev.klerkframework.klerk.KeyValueID
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkKeyValueStore
import dev.klerkframework.klerk.StringKeyValueID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.InputStream
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal class KeyValueStoreImpl<C: KlerkContext, V>(private val config: Config<C, V>) : KlerkKeyValueStore<C> {

    private val random = SecureRandom.getInstanceStrong()

    override suspend fun put(
        value: String,
        ttl: Duration?
    ): StringKeyValueID {
        var id = random.nextLong(0, UInt.MAX_VALUE.toLong())
        while (config.persistence.getKeyValueString(id) != null) {
            id = random.nextLong(0, UInt.MAX_VALUE.toLong())
        }
        config.persistence.putKeyValue(id, value, ttl?.let { Clock.System.now().plus(it) })
        return StringKeyValueID(id)
    }

    override suspend fun put(
        value: Int,
        ttl: Duration?
    ): IntKeyValueID {
        var id = random.nextLong(0, UInt.MAX_VALUE.toLong())
        while (config.persistence.getKeyValueInt(id) != null) {
            id = random.nextLong(0, UInt.MAX_VALUE.toLong())
        }
        config.persistence.putKeyValue(id, value, ttl?.let { Clock.System.now().plus(it) })
        return IntKeyValueID(id)
    }

    override fun prepareBlob(value: InputStream): BlobToken {
        var id = random.nextLong(0, UInt.MAX_VALUE.toLong())
        while (config.persistence.getKeyValueBlob(id) != null) {
            id = random.nextLong(0, UInt.MAX_VALUE.toLong())
        }
        config.persistence.putKeyValue(id, value, Clock.System.now().plus(5.minutes))
        return BlobToken(id)
    }

    override suspend fun put(
        token: BlobToken,
        ttl: Duration?
    ): BinaryKeyValueID {
        config.persistence.updateBlob(token.id, ttl?.let { Clock.System.now().plus(it)}, true)
        return BinaryKeyValueID(token.id)
    }

    override suspend fun get(id: StringKeyValueID, context: C): String =
        checkTTL(
            config.persistence.getKeyValueString(id.id)
                ?: throw NoSuchElementException("No value found for id ${id.id}"), context, id
        )

    override suspend fun get(id: IntKeyValueID, context: C): Int =
        checkTTL(config.persistence.getKeyValueInt(id.id) ?: throw NoSuchElementException("No value found for id ${id.id}"), context, id)


    override suspend fun get(id: BinaryKeyValueID, context: C): InputStream {
        val data = config.persistence.getKeyValueBlob(id.id) ?: throw NoSuchElementException("No value found for id ${id.id}")
        val active = data.third
        if (!active) {
            throw NoSuchElementException("No value found for id ${id.id}")
        }
        return checkTTL(data.first to data.second, context, id)
    }

    private fun <T> checkTTL(pair: Pair<T, Instant?>, context: C, id: KeyValueID): T {
        if (pair.second != null && pair.second!! < context.time) {
            throw NoSuchElementException("No value found for id ${id.id}")
        }
        return pair.first
    }
}

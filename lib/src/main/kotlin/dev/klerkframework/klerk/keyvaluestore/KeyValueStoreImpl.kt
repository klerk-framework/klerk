package dev.klerkframework.klerk.keyvaluestore

import dev.klerkframework.klerk.BlobToken
import dev.klerkframework.klerk.Config
import dev.klerkframework.klerk.KeyValueID
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkKeyValueStore
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
    ): KeyValueID<String> {
        var id = random.nextLong(0, UInt.MAX_VALUE.toLong()).toUInt()
        while (config.persistence.getKeyValueString(id) != null) {
            id = random.nextLong(0, UInt.MAX_VALUE.toLong()).toUInt()
        }
        config.persistence.putKeyValue(id, value, ttl?.let { Clock.System.now().plus(it) })
        return KeyValueID(id, String::class)
    }

    override suspend fun put(
        value: Int,
        ttl: Duration?
    ): KeyValueID<Int> {
        var id = random.nextLong(0, UInt.MAX_VALUE.toLong()).toUInt()
        while (config.persistence.getKeyValueInt(id) != null) {
            id = random.nextLong(0, UInt.MAX_VALUE.toLong()).toUInt()
        }
        config.persistence.putKeyValue(id, value, ttl?.let { Clock.System.now().plus(it) })
        return KeyValueID(id, Int::class)
    }

    override fun prepareBlob(value: InputStream): BlobToken {
        var id = random.nextLong(0, UInt.MAX_VALUE.toLong()).toUInt()
        while (config.persistence.getKeyValueBlob(id) != null) {
            id = random.nextLong(0, UInt.MAX_VALUE.toLong()).toUInt()
        }
        config.persistence.putKeyValue(id, value, Clock.System.now().plus(5.minutes))
        return BlobToken(id)
    }

    override suspend fun put(
        token: BlobToken,
        ttl: Duration?
    ): KeyValueID<InputStream> {
        config.persistence.updateBlob(token.id, ttl?.let { Clock.System.now().plus(it)}, true)
        return KeyValueID(token.id, InputStream::class)
    }

    override suspend fun <T : Any> get(id: KeyValueID<T>, context: C): T {
        @Suppress("UNCHECKED_CAST")
        return when (id.type) {
            String::class -> checkTTL(config.persistence.getKeyValueString(id.id) ?: throw NoSuchElementException("No value found for id ${id.id}"), context, id)
            Int::class -> checkTTL(config.persistence.getKeyValueInt(id.id) ?: throw NoSuchElementException("No value found for id ${id.id}"), context, id)
            InputStream::class -> {
                val data = config.persistence.getKeyValueBlob(id.id) ?: throw NoSuchElementException("No value found for id ${id.id}")
                val active = data.third
                if (!active) {
                    throw NoSuchElementException("No value found for id ${id.id}")
                }
                checkTTL(data.first to data.second, context, id)
            }
            else -> throw IllegalArgumentException("Key-value store does not support ${id.type.simpleName}")
        } as T
    }

    private fun <T> checkTTL(pair: Pair<T, Instant?>, context: C, id: KeyValueID<*>): T {
        if (pair.second != null && pair.second!! < context.time) {
            throw NoSuchElementException("No value found for id ${id.id}")
        }
        return pair.first
    }
}

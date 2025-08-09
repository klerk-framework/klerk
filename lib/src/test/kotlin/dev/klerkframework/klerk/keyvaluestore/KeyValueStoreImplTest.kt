package dev.klerkframework.klerk.keyvaluestore

import dev.klerkframework.klerk.AuthorCollections
import dev.klerkframework.klerk.BinaryKeyValueID
import dev.klerkframework.klerk.BookCollections
import dev.klerkframework.klerk.Context
import dev.klerkframework.klerk.KeyValueID
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.MyCollections
import dev.klerkframework.klerk.createConfig
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class KeyValueStoreImplTest {

    @Test
    fun putAndGetString() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()
            val key = klerk.keyValueStore.put("test")
            val value = klerk.keyValueStore.get(key, Context.system())
            assertEquals("test", value)
        }
    }

    @Test
    fun putAndGetInt() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()
            val key = klerk.keyValueStore.put(1)
            val value = klerk.keyValueStore.get(key, Context.system())
            assertEquals(1, value)
        }
    }

    @Test
    fun putAndGetBlob() {
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections, RamStorage()))
            klerk.meta.start()
            val token = klerk.keyValueStore.prepareBlob("pelle".toByteArray().inputStream())
            val badKey = BinaryKeyValueID(token.id)
            try {
                klerk.keyValueStore.get(badKey, Context.system())
                fail("Expected exception")  // the key cannot be used until we have used put(token)
            } catch (e: NoSuchElementException) {
                // correct
            }
            val key = klerk.keyValueStore.put(token)
            assertEquals(badKey, key)
            val value = klerk.keyValueStore.get(key, Context.system())
            assertEquals("pelle", value.readAllBytes().decodeToString())
        }
    }

}

package dev.klerkframework.klerk.misc

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntIdCollectionsTest {

    @Test
    fun `IntIdSet behaves like a mutable set`() {
        val set = IntIdSet()
        assertTrue(set.isEmpty())
        assertTrue(set.add(1))
        assertFalse(set.add(1))
        assertTrue(set.add(2))
        assertEquals(2, set.size)
        assertTrue(1 in set)
        assertTrue(2 in set)
        assertFalse(3 in set)
        assertEquals(setOf(1, 2), set.toSet())

        assertTrue(set.remove(1))
        assertFalse(set.remove(1))
        assertEquals(setOf(2), set.toSet())
    }

    @Test
    fun `IntIdSet iterator supports removal`() {
        val set = IntIdSet()
        set.addAll(listOf(1, 2, 3))
        val iterator = set.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() == 2) iterator.remove()
        }
        assertEquals(setOf(1, 3), set.toSet())
    }

    @Test
    fun `IntIdList preserves insertion order, not id order`() {
        val list = IntIdList()
        list.add(30)
        list.add(10)
        list.add(20)
        assertEquals(listOf(30, 10, 20), list.toList())
    }

    @Test
    fun `IntIdList remove-by-value works like the ArrayList it replaces`() {
        val list = IntIdList()
        list.addAll(listOf(1, 2, 3, 4))
        assertTrue(list.remove(2))
        assertFalse(list.remove(2))
        assertEquals(listOf(1, 3, 4), list.toList())
    }

    @Test
    fun `IntIdList grows past its initial capacity`() {
        val list = IntIdList()
        val values = (0 until 500).toList()
        list.addAll(values)
        assertEquals(values, list.toList())
    }

    @Test
    fun `IntIntHashMap agrees with HashMap under random puts and removes`() {
        val map = IntIntHashMap()
        val reference = HashMap<Int, Int>()
        val random = Random(1)
        repeat(200_000) {
            // A small key range forces long probe runs and many collisions.
            val key = random.nextInt(0, 5_000)
            if (random.nextInt(3) == 0) {
                map.remove(key)
                reference.remove(key)
            } else {
                val value = random.nextInt(0, 100)
                map.put(key, value)
                reference[key] = value
            }
        }
        assertEquals(reference.size, map.size)
        for (key in 0 until 5_000) {
            assertEquals(reference[key] ?: IntIntHashMap.NONE, map[key], "key $key")
        }
        map.removeWhereValue(7)
        reference.values.removeIf { it == 7 }
        assertEquals(reference.size, map.size)
        for (key in 0 until 5_000) {
            assertEquals(reference[key] ?: IntIntHashMap.NONE, map[key], "key $key")
        }
    }

    @Test
    fun `IntHashSet agrees with HashSet under random adds and removes`() {
        val set = IntHashSet()
        val reference = HashSet<Int>()
        val random = Random(2)
        repeat(200_000) {
            val key = random.nextInt(0, 5_000)
            if (random.nextInt(3) == 0) {
                assertEquals(reference.remove(key), set.remove(key))
            } else {
                assertEquals(reference.add(key), set.add(key))
            }
        }
        assertEquals(reference, set.toIntArray().toSet())
        assertEquals(reference.size, set.size)
        for (key in 0 until 5_000) {
            assertEquals(key in reference, key in set, "key $key")
        }
    }

    @Test
    fun `IntIdRelations moves between one and many referrers`() {
        val relations = IntIdRelations()
        relations.add(toId = 1, fromId = 10)
        relations.add(toId = 1, fromId = 10)
        assertEquals(setOf(10), relations.referrers(1).toSet())
        relations.add(toId = 1, fromId = 11)
        relations.add(toId = 2, fromId = 11)
        assertEquals(setOf(10, 11), relations.referrers(1).toSet())

        relations.removeReferrer(10)
        assertEquals(setOf(11), relations.referrers(1).toSet())
        relations.add(toId = 1, fromId = 12)
        assertEquals(setOf(11, 12), relations.referrers(1).toSet())

        relations.removeReferrer(11)
        assertEquals(setOf(12), relations.referrers(1).toSet())
        assertEquals(emptySet(), relations.referrers(2).toSet())

        relations.removeReferred(1)
        assertEquals(emptySet(), relations.referrers(1).toSet())
    }
}

package dev.klerkframework.klerk.misc

import org.roaringbitmap.RoaringBitmap

/**
 * A mutable set of model ids, backed by a [RoaringBitmap] instead of boxed `Integer`s.
 *
 * Model ids are random non-negative ints, so the bitmap is compact only for large sets: it has one container per 2^16
 * range, i.e. up to 32768, and a set with far fewer ids than that pays ~60 bytes per id. Used for the id sets that grow
 * with the number of models: [dev.klerkframework.klerk.storage.ModelCache.ids],
 * [dev.klerkframework.klerk.view.ModelViews.allIds] and [dev.klerkframework.klerk.view.ModelView.index]. For small sets
 * use [IntHashSet].
 *
 * Not thread-safe: a read racing a mutation can throw or corrupt the set, not just see a stale value. Every caller
 * mutates it only while holding the write lock, and reads it only while holding the read lock or the command mutex.
 */
internal class IntIdSet : AbstractMutableSet<Int>() {

    private val bitmap = RoaringBitmap()

    override val size: Int get() = bitmap.cardinality

    override fun isEmpty(): Boolean = bitmap.isEmpty

    override fun contains(element: Int): Boolean = bitmap.contains(element)

    override fun add(element: Int): Boolean {
        if (bitmap.contains(element)) return false
        bitmap.add(element)
        return true
    }

    override fun remove(element: Int): Boolean {
        if (!bitmap.contains(element)) return false
        bitmap.remove(element)
        return true
    }

    override fun clear() {
        bitmap.clear()
    }

    /** The ids in ascending order. */
    fun toIntArray(): IntArray = bitmap.toArray()

    override fun iterator(): MutableIterator<Int> = object : MutableIterator<Int> {
        private val delegate = bitmap.intIterator
        private var current = -1

        override fun hasNext(): Boolean = delegate.hasNext()

        override fun next(): Int = delegate.next().also { current = it }

        override fun remove() {
            check(current != -1) { "next() must be called before remove()" }
            bitmap.remove(current)
            current = -1
        }
    }
}

/**
 * A mutable, insertion-ordered list of model ids, backed by a growable `IntArray` instead of a boxed
 * `ArrayList<Integer>`. Used for [dev.klerkframework.klerk.view.ModelViews._all], which must stay ordered by
 * [dev.klerkframework.klerk.Model.createdAt] rather than sorted by id, so it cannot be a bitmap.
 */
internal class IntIdList : AbstractMutableList<Int>() {

    private var array = IntArray(16)

    override var size: Int = 0
        private set

    override fun get(index: Int): Int {
        checkElementIndex(index, size)
        return array[index]
    }

    override fun set(index: Int, element: Int): Int {
        checkElementIndex(index, size)
        val old = array[index]
        array[index] = element
        return old
    }

    override fun add(index: Int, element: Int) {
        checkPositionIndex(index, size)
        ensureCapacity(size + 1)
        System.arraycopy(array, index, array, index + 1, size - index)
        array[index] = element
        size++
    }

    override fun removeAt(index: Int): Int {
        checkElementIndex(index, size)
        val old = array[index]
        System.arraycopy(array, index + 1, array, index, size - index - 1)
        size--
        return old
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > array.size) {
            array = array.copyOf(maxOf(minCapacity, array.size * 2))
        }
    }

    private fun checkElementIndex(index: Int, size: Int) {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
    }

    private fun checkPositionIndex(index: Int, size: Int) {
        if (index < 0 || index > size) throw IndexOutOfBoundsException("index: $index, size: $size")
    }
}

/**
 * Which ids refer to a given id, for [dev.klerkframework.klerk.storage.ModelCache].
 *
 * Most referenced models have a single referrer, so that is kept in a primitive map, and a set is used only from the
 * second referrer on. The sets are hash sets rather than [IntIdSet]s: model ids are random, so a bitmap of a few
 * thousand of them spends a container on nearly every id.
 *
 * Not thread-safe, see [IntIdSet].
 */
internal class IntIdRelations {

    private val single = IntIntHashMap()
    private val multiple = HashMap<Int, IntHashSet>()

    fun add(toId: Int, fromId: Int) {
        multiple[toId]?.let {
            it.add(fromId)
            return
        }
        val existing = single[toId]
        when (existing) {
            IntIntHashMap.NONE -> single.put(toId, fromId)
            fromId -> {}
            else -> {
                single.remove(toId)
                multiple[toId] = IntHashSet().apply {
                    add(existing)
                    add(fromId)
                }
            }
        }
    }

    /** The ids referring to [toId]. */
    fun referrers(toId: Int): IntArray {
        multiple[toId]?.let { return it.toIntArray() }
        val only = single[toId]
        return if (only == IntIntHashMap.NONE) IntArray(0) else intArrayOf(only)
    }

    /** Removes [fromId] as a referrer of every id. Scans all referenced ids. */
    fun removeReferrer(fromId: Int) {
        single.removeWhereValue(fromId)
        val iterator = multiple.entries.iterator()
        while (iterator.hasNext()) {
            val (toId, referrers) = iterator.next()
            if (referrers.remove(fromId) && referrers.size <= 1) {
                iterator.remove()
                referrers.forEach { single.put(toId, it) }
            }
        }
    }

    /** Forgets every referrer of [toId]. */
    fun removeReferred(toId: Int) {
        single.remove(toId)
        multiple.remove(toId)
    }

    fun clear() {
        single.clear()
        multiple.clear()
    }
}

private const val FREE = -1
private const val INITIAL_CAPACITY = 4

/**
 * An open-addressing set of non-negative ints, with no boxing. Linear probing, backward-shift deletion, at most 3/4
 * full.
 */
internal open class IntHashSet {

    private var keys = IntArray(INITIAL_CAPACITY) { FREE }

    var size: Int = 0
        private set

    operator fun contains(key: Int): Boolean = indexOf(key) >= 0

    /** Adds [key], returning whether it was missing. */
    fun add(key: Int): Boolean {
        val before = size
        slotFor(key)
        return size > before
    }

    /** Removes [key], returning whether it was present. */
    fun remove(key: Int): Boolean {
        var hole = indexOf(key)
        if (hole < 0) return false
        val mask = keys.size - 1
        // Moves later entries of the same probe run into the hole, so that a lookup never stops early.
        var next = (hole + 1) and mask
        while (keys[next] != FREE) {
            val home = home(keys[next])
            if (((next - home) and mask) >= ((next - hole) and mask)) {
                keys[hole] = keys[next]
                moved(from = next, to = hole)
                hole = next
            }
            next = (next + 1) and mask
        }
        keys[hole] = FREE
        size--
        return true
    }

    inline fun forEach(action: (Int) -> Unit) {
        for (slot in 0 until capacity) {
            val key = keyAt(slot)
            if (key != FREE) action(key)
        }
    }

    fun toIntArray(): IntArray {
        val result = IntArray(size)
        var i = 0
        forEach { result[i++] = it }
        return result
    }

    open fun clear() {
        keys = IntArray(INITIAL_CAPACITY) { FREE }
        size = 0
    }

    @PublishedApi
    internal val capacity: Int get() = keys.size

    @PublishedApi
    internal fun keyAt(slot: Int): Int = keys[slot]

    /** The slot holding [key], or -1. */
    protected fun indexOf(key: Int): Int {
        var slot = home(key)
        while (true) {
            when (keys[slot]) {
                FREE -> return -1
                key -> return slot
            }
            slot = (slot + 1) and (keys.size - 1)
        }
    }

    /** The slot holding [key], inserting it first if it is missing. May grow the table. */
    protected fun slotFor(key: Int): Int {
        require(key >= 0) { "Only non-negative ints, was $key" }
        if ((size + 1) * 4 > keys.size * 3) grow(keys.size * 2)
        var slot = home(key)
        while (true) {
            when (keys[slot]) {
                FREE -> {
                    keys[slot] = key
                    size++
                    return slot
                }
                key -> return slot
            }
            slot = (slot + 1) and (keys.size - 1)
        }
    }

    /** Called when the entry in slot [from] has moved to slot [to]. */
    protected open fun moved(from: Int, to: Int) {}

    protected open fun grow(capacity: Int) {
        rehash(capacity)
    }

    /** Moves every entry to a new table of [capacity], returning the new slot of each old slot (-1 if it was free). */
    protected fun rehash(capacity: Int): IntArray {
        val oldKeys = keys
        keys = IntArray(capacity) { FREE }
        size = 0
        return IntArray(oldKeys.size) { oldSlot -> if (oldKeys[oldSlot] == FREE) -1 else slotFor(oldKeys[oldSlot]) }
    }

    private fun home(key: Int): Int = (key * -0x61c88647) ushr (32 - Integer.numberOfTrailingZeros(keys.size))
}

/** An open-addressing map from non-negative int to non-negative int, with no boxing. See [IntHashSet]. */
internal class IntIntHashMap : IntHashSet() {

    private var values = IntArray(INITIAL_CAPACITY)

    /** The value for [key], or [NONE]. */
    operator fun get(key: Int): Int {
        val slot = indexOf(key)
        return if (slot < 0) NONE else values[slot]
    }

    fun put(key: Int, value: Int) {
        require(value >= 0) { "Only non-negative values, was $value" }
        val slot = slotFor(key)
        values[slot] = value
    }

    /** Removes every entry whose value is [value]. */
    fun removeWhereValue(value: Int) {
        var matches: IntIdList? = null
        for (slot in 0 until capacity) {
            if (keyAt(slot) != FREE && values[slot] == value) {
                (matches ?: IntIdList().also { matches = it }).add(keyAt(slot))
            }
        }
        matches?.forEach { remove(it) }
    }

    override fun clear() {
        super.clear()
        values = IntArray(INITIAL_CAPACITY)
    }

    override fun moved(from: Int, to: Int) {
        values[to] = values[from]
    }

    override fun grow(capacity: Int) {
        val oldValues = values
        val newSlots = rehash(capacity)
        values = IntArray(capacity)
        for (oldSlot in newSlots.indices) {
            if (newSlots[oldSlot] >= 0) values[newSlots[oldSlot]] = oldValues[oldSlot]
        }
    }

    companion object {
        /** Returned by [get] for a missing key. */
        const val NONE = -1
    }
}

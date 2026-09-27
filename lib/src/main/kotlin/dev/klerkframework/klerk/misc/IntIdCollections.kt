package dev.klerkframework.klerk.misc

import org.roaringbitmap.RoaringBitmap

/**
 * A mutable set of model ids, backed by a [RoaringBitmap] instead of boxed `Integer`s.
 *
 * Model ids are dense-ish, non-negative ints, which is what a bitmap represents well. Used wherever Klerk keeps a
 * membership-only set of ids: [dev.klerkframework.klerk.storage.ModelCache.ids],
 * [dev.klerkframework.klerk.view.ModelViews.allIds] and [dev.klerkframework.klerk.view.ModelView.index].
 *
 * Not thread-safe for concurrent mutation, same as the `HashSet` it replaces: every caller mutates it only while
 * holding the write lock, and reads it (including from other threads) only outside that window.
 */
internal class IntIdSet : AbstractMutableSet<Int>() {

    private val bitmap = RoaringBitmap()

    override val size: Int get() = bitmap.cardinality

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

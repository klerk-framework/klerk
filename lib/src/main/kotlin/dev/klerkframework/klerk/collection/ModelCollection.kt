package dev.klerkframework.klerk.collection

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.decodeBase64String
import dev.klerkframework.klerk.misc.encodeBase64
import dev.klerkframework.klerk.read.Reader
import kotlinx.datetime.Instant

public abstract class ModelCollection<T : Any, C : KlerkContext>(private val parent: ModelCollection<T, C>?) {

    private var idBase: String? = null
    protected var _id: String? = null

    // These are only accessed using a ReadWriteLock which prevents concurrent modification and gives us
    // a happens-before guarantee

    /*
    - mutex så att inte alla gör get samtidigt ?
    - måste T vara Any?
    - gör suspending så vi kan förbereda för att ha datan i rooks? (ny möjlighet har visat sig)
    - fun hasChanged(reader.lastEventId): Boolean så att man kan hålla data
    - fun combine(other: Collection<U>): Collection
    - hålla koll på hur ofta som filtret används. Då kan en manager bättre besluta om vilka listsources som ska cachea.

     */

    public open fun filter(filter: ((Model<T>) -> Boolean)?): ModelCollection<T, C> {
        if (filter == null) {
            return this
        }
        val new = FilteredModelCollection(this, filter)
        return new
    }

    public fun filterStates(included: Set<String>? = null, excluded: Set<String>? = null): ModelCollection<T, C> {
        val new = IncludeStatesModelCollection(this, included, excluded)
        return new
    }

    public fun <R : Comparable<R>> sorted(selector: (Model<T>) -> R, ascending: Boolean = true): ModelCollection<T, C> {
        val new = SortedModelCollection(this, selector, ascending)
        return new
    }

    /**
     * Creates a Sequence of the Collection content.
     * It is usually better to use the methods in Reader (query, list etc.) but if you must use this, be careful
     * to not use it after you have released the Reader since that may lead to ConcurrentModificationException.
     */
    public abstract fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor? = null): Sequence<Model<T>>

    public fun <V> isEmpty(reader: Reader<C, V>): Boolean = withReader(reader, null).toList().isEmpty()

    public open fun getFullId(): CollectionId {
        check(idBase != null && _id != null)
        return CollectionId(idBase!!, _id!!)
    }

    public open fun getId(): String = _id ?: error("Collection is missing ID")
    public open fun getView(): ModelCollections<T, C> = parent?.getView() ?: throw IllegalStateException()
    public fun <V> count(reader: Reader<C, V>): Int = withReader(reader, null).count()

    public open fun <V> contains(value: ModelID<*>, reader: Reader<C, V>): Boolean {
        return withReader(reader, null).any { it.id == value }
    }

    /**
     * Makes Klerk aware of this collection. It will be included in Config.getCollections()
     */
    public fun register(id: String): ModelCollection<T, C> {
        require(!id.contains(".") && !id.contains(" ")) { "Illegal collection ID: $id" }
        this._id = id
        getView().register(this)
        return this
    }

    internal fun setIdBase(idBase: String?) {
        this.idBase = idBase
    }

    public fun <D> readWith(reader: Reader<C, D>): List<Model<T>> = withReader(reader).toList()

}

public class SortedModelCollection<T : Any, R : Comparable<R>, C : KlerkContext>(
    private val previous: ModelCollection<T, C>,
    private val selector: (Model<T>) -> R,
    private val ascending: Boolean
) : ModelCollection<T, C>(previous) {

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> {
        return if (ascending) previous.withReader(reader, cursor)
            .sortedBy(selector) else previous.withReader(reader, cursor).sortedByDescending(selector)
    }

}

public class IncludeStatesModelCollection<T : Any, C : KlerkContext>(
    private val previous: ModelCollection<T, C>,
    private val included: Set<String>?,
    private val excluded: Set<String>?
) : ModelCollection<T, C>(previous) {

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> {
        var s = previous.withReader(reader, cursor)
        if (included != null) {
            s = s.filter { included.contains(it.state) }
        }
        if (excluded != null) {
            s = s.filter { !excluded.contains(it.state) }
        }
        return s
    }

}

public class FilteredModelCollection<T : Any, C : KlerkContext>(
    private val previous: ModelCollection<T, C>,
    private val predicate: (Model<T>) -> Boolean,
) : ModelCollection<T, C>(previous) {

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> =
        previous.withReader(reader, cursor).filter(predicate)

}

public class AllModelCollection<T : Any, C : KlerkContext>(
    private val view: ModelCollections<T, C>,
    private val all: List<Int>
) : ModelCollection<T, C>(null) {

    init {
        _id = "all"
        logger.info { "created ${this}" }
    }

    override fun getView(): ModelCollections<T, C> = view

    override fun <V> withReader(reader: Reader<C, V>, cursor: QueryListCursor?): Sequence<Model<T>> {
        if (cursor == null) {
            return all.asSequence().map { reader.get(ModelID(it)) }
        }
        require(cursor.field == QueryCursorField.CREATED_AT)
        if (cursor.after != null) {
            return ascending(cursor.after, cursor.including, reader)
        }
        if (cursor.before != null) {
            return descending(cursor.before, cursor.including, reader)
        }
        throw IllegalStateException()
    }

    private fun <V> ascending(after: Instant, include: Boolean, reader: Reader<C, V>): Sequence<Model<T>> {
        // find the model created at or the first model created after the cursor
        // we know that the list is sorted by createdAt and all models have unique createdAt
        val startIndex = all.binarySearch { ref ->
            val model = reader.get(ModelID(ref))
            if (model.createdAt < after) {
                return@binarySearch -1
            }
            if (model.createdAt == after) {
                return@binarySearch 0
            }
            val index = all.indexOf(ref)
            if (index == 0) {
                return@binarySearch -1
            }
            val previous = reader.get(ModelID(all[index - 1]))
            return@binarySearch if (previous.createdAt < after) 0 else 1
        }
        if (include) {
            return all.subList(startIndex, all.lastIndex + 1).asSequence().map { reader.get(ModelID(it)) }
        }
        if (startIndex + 1 > all.lastIndex) {
            return emptySequence()
        }
        return all.subList(startIndex + 1, all.lastIndex + 1).asSequence().map { reader.get(ModelID(it)) }
    }

    private fun <V> descending(before: Instant, include: Boolean, reader: Reader<C, V>): Sequence<Model<T>> {
        // find the model created at or the last model created before the cursor
        // we know that the list is sorted by createdAt and all models have unique createdAt
        val startIndex = all.binarySearch { ref ->
            val model = reader.get(ModelID(ref))
            if (model.createdAt > before) {
                return@binarySearch 1
            }
            if (model.createdAt == before) {
                return@binarySearch 0
            }
            val index = all.indexOf(ref)
            if (index == 0) {
                return@binarySearch 0
            }
            if (index == all.lastIndex) {
                return@binarySearch 1
            }
            val next = reader.get(ModelID(all[index + 1]))
            return@binarySearch if (next.createdAt > before) 0 else -1
        }
        if (startIndex < 0) {
            return all.asReversed().asSequence().map { reader.get(ModelID(it)) }
        }
        if (include && startIndex < all.lastIndex) {
            return all.subList(0, startIndex + 1).asReversed().asSequence().map { reader.get(ModelID(it)) }
        }
        return all.subList(0, startIndex).asReversed().asSequence().map { reader.get(ModelID(it)) }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<C, V>): Boolean = all.contains(value.toInt())
}

public data class QueryOptions(
    val maxItems: Int = 50,
    val cursor: QueryListCursor? = null
) {

    init {
        require(maxItems > 0)
    }

}


public data class QueryResponse<T : Any>(
    val items: List<Model<T>>,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
    val cursorFirst: QueryListCursor?,
    val cursorPrevious: QueryListCursor?,
    val cursorNext: QueryListCursor?,
    val cursorLast: QueryListCursor?,
    val options: QueryOptions?
)

public enum class QueryCursorField {
    CREATED_AT
}

public data class QueryListCursor(
    val after: Instant? = null,
    val before: Instant? = null,
    val field: QueryCursorField = QueryCursorField.CREATED_AT
) {

    init {
        require((after != null || before != null) && (after == null || before == null)) { "One of after or before must be set" }
    }

    internal var including: Boolean = false

    public companion object {
        public val first: QueryListCursor = QueryListCursor(after = Instant.DISTANT_PAST)
        public val last: QueryListCursor = QueryListCursor(before = decode64bitMicroseconds(Long.MAX_VALUE))
        public const val DEFAULT_ITEMS_PER_PAGE: Int = 100

        public fun fromString(s: String): QueryListCursor {
            val map = mutableMapOf<String, String>()
            val keyValues = s.decodeBase64String().split(",")
            keyValues.forEach {
                val (key, value) = it.split(":")
                map[key] = value
            }
            return QueryListCursor(
                after = map["a"]?.toLong()?.let { decode64bitMicroseconds(it) },
                before = map["b"]?.toLong()?.let { decode64bitMicroseconds(it) },
                field = QueryCursorField.valueOf(map["f"] ?: throw IllegalArgumentException())
            )
        }
    }

    override fun toString(): String {
        if (after != null) {
            return "a:${after.to64bitMicroseconds()},f:$field".encodeBase64()
        }
        if (before != null) {
            return "b:${before.to64bitMicroseconds()},f:$field".encodeBase64()
        }
        throw IllegalStateException()
    }
}

package dev.klerkframework.klerk.view

import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.misc.decodeBase64UrlSafeString
import dev.klerkframework.klerk.misc.encodeBase64UrlSafe

/** Where [QueryOptions.cursor] sits relative to the page. */
public enum class PageDirection {
    /** The page starts at the cursor. This is what the cursors in a [QueryResponse] are meant for. */
    From,

    /** The page starts immediately after the cursor. */
    After,

    /** The page ends immediately before the cursor. */
    Before,
}

/**
 * Page size and starting point for a paginated [ModelView] read (`Reader.query`).
 *
 * @throws IllegalArgumentException if [maxItems] is not positive
 */
public data class QueryOptions(
    /**
     * The most items the page may hold. Not capped, since a large page is legitimate for application code; a plugin that
     * takes the page size from a client must cap it itself.
     */
    val maxItems: Int = 50,
    /** Where the page sits; null means the start of the view. */
    val cursor: QueryListCursor? = null,
    /** Where [cursor] sits relative to the page. */
    val direction: PageDirection = PageDirection.From,
    /**
     * Read the whole view to fill in [QueryResponse.totalCount] and [QueryResponse.cursorLastPage]. Off by default,
     * since it costs a full pass.
     */
    val countTotal: Boolean = false,
) {

    init {
        require(maxItems > 0) { "maxItems must be positive but was $maxItems" }
    }
}

/**
 * One page of a paginated [ModelView] read, with cursors for the adjacent pages. Every cursor is null when there is
 * no such page, so a pagination control can render a link for exactly the cursors it was given.
 */
public data class QueryResponse<T : Any>(
    /** The models on this page, in the view's order. */
    val items: List<Model<T>>,
    /** The first page. */
    val cursorFirstPage: QueryListCursor?,
    /** The page before this one. */
    val cursorPreviousPage: QueryListCursor?,
    /** The page after this one. */
    val cursorNextPage: QueryListCursor?,
    /** The last page; null unless [QueryOptions.countTotal] was set. */
    val cursorLastPage: QueryListCursor?,
    /** The size of the whole view, or null unless [QueryOptions.countTotal] was set. */
    val totalCount: Int?,
    /** Where [items] start in the view, needed by [cursorAt]. */
    internal val offset: Int = 0,
) {

    /** True if there is a page before this one. */
    public val hasPreviousPage: Boolean get() = cursorPreviousPage != null

    /** True if there is a page after this one. */
    public val hasNextPage: Boolean get() = cursorNextPage != null

    /**
     * A cursor pointing at the item at [index] of [items]. Use it when every row needs its own position rather than
     * the page as a whole — a GraphQL edge cursor, for instance.
     *
     * @throws IndexOutOfBoundsException if [index] is not an index of [items]
     */
    public fun cursorAt(index: Int): QueryListCursor {
        if (index !in items.indices) {
            throw IndexOutOfBoundsException("No item at index $index, the page holds ${items.size} items")
        }
        return QueryListCursor(offset + index, items[index].id.value)
    }
}

/**
 * An opaque position in a [ModelView]. Serializes to and from a URL-safe string via [toString]/[parse]; treat
 * that string as meaningless and don't build one yourself.
 *
 * A cursor is a position, not a snapshot: it resolves to the item it was cut at whenever that item is still in the
 * view, so models created or deleted meanwhile neither skip nor repeat a row. If that item is gone, the raw position
 * is used and a row may shift.
 */
public class QueryListCursor internal constructor(internal val offset: Int, internal val anchor: Int?) {

    init {
        require(offset >= 0)
    }

    override fun toString(): String = (if (anchor == null) "o:$offset" else "o:$offset,a:$anchor").encodeBase64UrlSafe()

    override fun equals(other: Any?): Boolean =
        other is QueryListCursor && other.offset == offset && other.anchor == anchor

    override fun hashCode(): Int = offset * 31 + (anchor ?: 0)

    public companion object {
        /** The start of the view. */
        public val first: QueryListCursor = QueryListCursor(0, null)

        /**
         * Parses a cursor previously serialized with [QueryListCursor.toString].
         * @throws IllegalArgumentException if [value] is not a validly encoded cursor
         */
        public fun parse(value: String): QueryListCursor {
            val fields = try {
                value.decodeBase64UrlSafeString().split(",").associate { field ->
                    val separator = field.indexOf(':')
                    require(separator > 0)
                    field.substring(0, separator) to field.substring(separator + 1)
                }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Not a cursor: '$value'", e)
            }
            val offset = fields["o"]?.toIntOrNull()
            require(offset != null && offset >= 0) { "Not a cursor: '$value'" }
            val anchor = fields["a"]?.let { requireNotNull(it.toIntOrNull()) { "Not a cursor: '$value'" } }
            return QueryListCursor(offset, anchor)
        }

        /** The cursor in [value], or null if it is not one. */
        public fun parseOrNull(value: String): QueryListCursor? = runCatching { parse(value) }.getOrNull()
    }
}

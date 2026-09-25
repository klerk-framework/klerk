package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.AttachedDataReader
import dev.klerkframework.klerk.JobReader
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.PendingRead
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.PageDirection
import dev.klerkframework.klerk.view.QueryListCursor
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.time.Instant

/**
 * The reader behind this one that does not enforce authorization, or null for a [Reader] Klerk did not create.
 *
 * Klerk's own read paths run through it: an index has to reflect every model, not the ones one actor may see.
 */
internal fun <C : KlerkContext, V> ModelReader<C, V>.unauthorized(): ModelReader<C, V>? = when (this) {
    is ReaderWithoutAuth -> this
    is ReaderWithAuth -> withoutAuth
    else -> null
}

/**
 * Used internally, e.g. when executing the functions provided in a statemachine.
 * Note that no logging to the activity log is triggered here.
 */
internal class ReaderWithoutAuth<C : KlerkContext, V>(val klerk: Klerk<C, V>) :
    ModelReader<C, V>,
    ViewReader<C, V> {

    override val views = klerk.specification.views

    override val jobs: JobReader = UnauthorizedJobReader(klerk)

    override val attachedData: AttachedDataReader = UnauthorizedAttachedDataReader(klerk)

    override fun eventLog(id: ModelID<out Any>?, after: Instant, before: Instant): PendingRead<List<EventLogEntry>> =
        eventLogQuery(klerk, id, after, before)

    override fun eventLogEntry(sequenceNumber: Long): PendingRead<EventLogEntry?> =
        eventLogEntryQuery(klerk, sequenceNumber)

    override fun referencingIds(id: ModelID<*>): Set<ModelID<*>> = ModelCache.referencingIds(id)

    override fun <T : Any, U : Any> referencingInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = ModelCache.referencingInCollection(property, id)

    override fun <T : Any, U : Any> referencing(property: KProperty1<T, ModelID<U>?>, id: ModelID<*>): Set<Model<T>> =
        ModelCache.referencing(property, id)

    override fun <T : Any> referencing(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> =
        ModelCache.referencing(clazz, id)

    override fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?,
    ): QueryResponse<T> = queryInternal(collection, options, filter, null)

    /**
     * Cuts one page out of [collection], in the view's own order.
     *
     * One pass over the view's ids. Models outside the page are read only when [authorize] or [filter] has to look at
     * them; both run before the page is cut, so a page is full whenever enough models match. [authorize] is applied
     * before [filter], and may replace the model (masked properties). Returning null drops it.
     */
    internal fun <T : Any> queryInternal(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?,
        authorize: ((Model<T>) -> Model<T>?)?,
    ): QueryResponse<T> {
        val opts = options ?: QueryOptions()
        // Positions are Longs: the cursor comes from the client, and an offset near Int.MAX_VALUE plus a page must not
        // overflow. Any position that ends up in a cursor is within the view, so it fits in an Int again.
        val maxItems = opts.maxItems.toLong()
        val cursorOffset = (opts.cursor?.offset ?: 0).toLong()
        val anchor = opts.cursor?.anchor

        // Where the page starts, relative to the item the cursor points at.
        val delta = when (opts.direction) {
            PageDirection.From -> 0L
            PageDirection.After -> 1L
            PageDirection.Before -> -maxItems
        }
        // How far the cursor's position may move when its anchor is found somewhere else than where it was cut.
        val slack = if (anchor == null) 0L else maxItems
        // Everything the answer can need: the page wherever it ends up, the item after it (proving hasNextPage), and
        // the item one page back (anchoring cursorPreviousPage).
        val windowFrom = maxOf(0L, cursorOffset - slack + delta - maxItems)
        val windowTo = cursorOffset + slack + delta + maxItems

        val window = mutableListOf<Model<T>>()
        val needsModel = filter != null || authorize != null
        var index = 0L
        var exhausted = true
        for (id in collection.memberIds(this)) {
            var model: Model<T>? = null
            if (needsModel) {
                model = get(id)
                if (authorize != null) {
                    model = authorize(model) ?: continue
                }
                if (filter != null && !filter(model)) {
                    continue
                }
            }
            if (index in windowFrom..windowTo) {
                window.add(model ?: get(id))
            }
            index++
            if (!opts.countTotal && index > windowTo) {
                exhausted = false
                break
            }
        }
        // Only meaningful when the pass reached the end, which is always the case when counting.
        val viewSize = if (exhausted) index else null
        val totalCount = if (opts.countTotal) index.toInt() else null

        fun windowAt(position: Long): Model<T>? {
            val i = position - windowFrom
            return if (i < 0 || i >= window.size) null else window[i.toInt()]
        }

        // Where the cursor actually points now. The anchor wins over the stored position, but only within one page of
        // it — beyond that we are no longer looking at the same part of the view.
        val anchorIndex = anchor?.let { wanted ->
            val inWindow = window.indexOfFirst { it.id.value == wanted }
            val nearCursor = (cursorOffset - maxItems)..(cursorOffset + maxItems)
            if (inWindow < 0) null else (windowFrom + inWindow).takeIf { it in nearCursor }
        }
        val resolved = anchorIndex ?: cursorOffset
        val pageStart = maxOf(0L, resolved + delta)

        val pageFrom = pageStart - windowFrom
        val items = if (pageFrom >= window.size) {
            emptyList()
        } else {
            window.subList(pageFrom.toInt(), minOf(pageFrom + maxItems, window.size.toLong()).toInt()).toList()
        }

        val hasNextPage = pageFrom + maxItems < window.size
        val hasPreviousPage = pageStart > 0
        // A cursor kept from before a lot of models were deleted can sit past the end of the view. The page is then
        // empty, which is the honest answer, but stepping back should land on content rather than on more empty pages.
        val previousStart = maxOf(0L, minOf(pageStart, viewSize ?: pageStart) - maxItems)

        return QueryResponse(
            items,
            cursorFirstPage = if (hasPreviousPage) QueryListCursor.first else null,
            cursorPreviousPage = if (hasPreviousPage) {
                QueryListCursor(previousStart.toInt(), windowAt(previousStart)?.id?.value)
            } else {
                null
            },
            cursorNextPage = if (hasNextPage) {
                QueryListCursor((pageStart + maxItems).toInt(), windowAt(pageStart + maxItems)?.id?.value)
            } else {
                null
            },
            cursorLastPage = lastPageCursor(totalCount, maxItems, pageStart),
            totalCount = totalCount,
            offset = pageStart.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    /** Null when the total is unknown or the page at hand already is the last one. */
    private fun lastPageCursor(totalCount: Int?, maxItems: Long, pageStart: Long): QueryListCursor? {
        if (totalCount == null || totalCount == 0) {
            return null
        }
        val lastStart = ((totalCount - 1) / maxItems) * maxItems
        return if (lastStart <= pageStart) null else QueryListCursor(lastStart.toInt(), null)
    }

    // Nothing to skip: this reader does not enforce authorization, so every model in the view is visible.
    override fun <T : Any> ids(collection: ModelView<T, C>): Sequence<ModelID<T>> = collection.memberIds(this)

    override fun <T : Any> count(collection: ModelView<T, C>): Int = collection.internalCount(this)

    override fun <T : Any> isEmpty(collection: ModelView<T, C>): Boolean = collection.internalIsEmpty(this)

    override fun <T : Any> contains(collection: ModelView<T, C>, id: ModelID<T>): Boolean =
        collection.internalContains(id, this)

    override fun <T : Any> sequence(collection: ModelView<T, C>): Sequence<Model<T>> = collection.withReader(this)

    override fun <T : Any> queryOrThrow(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?,
    ): QueryResponse<T> = queryInternal(collection, options, filter, null)

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? = ModelCache.getOrNull(id)

    override fun <T : Any> get(id: ModelID<T>): Model<T> =
        getOrNull(id) ?: throw NoSuchElementException("Could not find model with id=$id")
}

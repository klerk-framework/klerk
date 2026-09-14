package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.PageDirection
import dev.klerkframework.klerk.view.QueryListCursor
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse
import dev.klerkframework.klerk.storage.ModelCache
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
 * Note that no logging to KlerkLog is triggered here.
 */
internal class ReaderWithoutAuth<C : KlerkContext, V>(val klerk: Klerk<C, V>) : ModelReader<C, V>, ViewReader<C, V> {

    override val views = klerk.specification.views

    override val jobs: JobReader = UnauthorizedJobReader(klerk)

    override val attachedData: AttachedDataReader = UnauthorizedAttachedDataReader(klerk)

    override fun eventLog(
        id: ModelID<out Any>?,
        after: Instant,
        before: Instant,
    ): EventLogQuery = eventLogQuery(klerk, id, after, before)

    override fun eventLogEntry(sequenceNumber: Long): EventLogEntryQuery = eventLogEntryQuery(klerk, sequenceNumber)

    override fun referencingIds(id: ModelID<*>): Set<ModelID<*>> = ModelCache.referencingIds(id)

    override fun <T : Any, U : Any> referencingInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = ModelCache.referencingInCollection(property, id)

    override fun <T : Any, U : Any> referencing(
        property: KProperty1<T, ModelID<U>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = ModelCache.referencing(property, id)

    override fun <T : Any> referencing(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> {
        return ModelCache.referencing(clazz, id)
    }

    override fun <T : Any> query(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> = queryInternal(collection, options, filter, null)

    /**
     * Cuts one page out of [collection], in the view's own order.
     *
     * One pass over the view's ids. Models outside the page are read only when [authorize] or [filter] has to look at
     * them; both run before the page is cut, so a page is full whenever enough models match.
     *
     * @param authorize applied before [filter], and may replace the model (masked properties). Returning null drops it.
     */
    internal fun <T : Any> queryInternal(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?,
        authorize: ((Model<T>) -> Model<T>?)?,
    ): QueryResponse<T> {
        val opts = options ?: QueryOptions()
        val maxItems = opts.maxItems
        val cursorOffset = opts.cursor?.offset ?: 0
        val anchor = opts.cursor?.anchor

        // Where the page starts, relative to the item the cursor points at.
        val delta = when (opts.direction) {
            PageDirection.FROM -> 0
            PageDirection.AFTER -> 1
            PageDirection.BEFORE -> -maxItems
        }
        // How far the cursor's position may move when its anchor is found somewhere else than where it was cut.
        val slack = if (anchor == null) 0 else maxItems
        // Everything the answer can need: the page wherever it ends up, the item after it (proving hasNextPage), and
        // the item one page back (anchoring cursorPreviousPage).
        val windowFrom = maxOf(0, cursorOffset - slack + delta - maxItems)
        val windowTo = cursorOffset + slack + delta + maxItems

        val window = mutableListOf<Model<T>>()
        val needsModel = filter != null || authorize != null
        var index = 0
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
        val totalCount = if (opts.countTotal) index else null

        // Where the cursor actually points now. The anchor wins over the stored position, but only within one page of
        // it — beyond that we are no longer looking at the same part of the view.
        val anchorIndex = anchor?.let { wanted ->
            val inWindow = window.indexOfFirst { it.id.value == wanted }
            if (inWindow < 0) null else (windowFrom + inWindow).takeIf { it in (cursorOffset - maxItems)..(cursorOffset + maxItems) }
        }
        val resolved = anchorIndex ?: cursorOffset
        val pageStart = maxOf(0, resolved + delta)

        val pageFrom = pageStart - windowFrom
        val items = if (pageFrom >= window.size) emptyList() else
            window.subList(pageFrom, minOf(pageFrom + maxItems, window.size)).toList()

        val hasNextPage = pageFrom + maxItems < window.size
        val hasPreviousPage = pageStart > 0
        // A cursor kept from before a lot of models were deleted can sit past the end of the view. The page is then
        // empty, which is the honest answer, but stepping back should land on content rather than on more empty pages.
        val previousStart = maxOf(0, minOf(pageStart, viewSize ?: pageStart) - maxItems)

        return QueryResponse(
            items,
            cursorFirstPage = if (hasPreviousPage) QueryListCursor.first else null,
            cursorPreviousPage = if (hasPreviousPage)
                QueryListCursor(previousStart, window.getOrNull(previousStart - windowFrom)?.id?.value) else null,
            cursorNextPage = if (hasNextPage)
                QueryListCursor(pageStart + maxItems, window.getOrNull(pageFrom + maxItems)?.id?.value) else null,
            cursorLastPage = lastPageCursor(totalCount, maxItems, pageStart),
            totalCount = totalCount,
            offset = pageStart,
        )
    }

    /** Null when the total is unknown or the page at hand already is the last one. */
    private fun lastPageCursor(totalCount: Int?, maxItems: Int, pageStart: Int): QueryListCursor? {
        if (totalCount == null || totalCount == 0) {
            return null
        }
        val lastStart = ((totalCount - 1) / maxItems) * maxItems
        return if (lastStart <= pageStart) null else QueryListCursor(lastStart, null)
    }

    // Nothing to skip: this reader does not enforce authorization, so every model in the view is visible.
    override fun <T : Any> sequence(collection: ModelView<T, C>): Sequence<Model<T>> = collection.withReader(this)

    override fun <T : Any> queryOrThrow(
        collection: ModelView<T, C>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> = queryInternal(collection, options, filter, null)

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        return ModelCache.getOrNull(id)
    }

    override fun <T : Any> get(id: ModelID<T>): Model<T> {
        return getOrNull(id) ?: throw NoSuchElementException("Could not find model with id=$id")
    }

}

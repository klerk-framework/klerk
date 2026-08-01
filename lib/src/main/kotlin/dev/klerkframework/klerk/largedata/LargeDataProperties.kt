package dev.klerkframework.klerk.largedata

import dev.klerkframework.klerk.LargeBlobID
import dev.klerkframework.klerk.LargeStringID
import dev.klerkframework.klerk.logger
import kotlin.reflect.full.memberProperties

/**
 * The attached-data ids a model refers to.
 *
 * Note that these are sets, so a model that holds the same id in two properties yields it once. That is what makes
 * `Book(cover = blobX, thumbnail = blobX)` behave correctly when one of the two is cleared: the id is still in the
 * set afterwards, so it is not dropped.
 */
internal data class LargeDataIds(val blobs: Set<Int>, val strings: Set<Int>) {
    companion object {
        val empty: LargeDataIds = LargeDataIds(emptySet(), emptySet())
    }
}

/**
 * Finds the [LargeBlobID] and [LargeStringID] values in a model's props, including those inside a `List` or `Set`.
 */
internal fun collectLargeDataIds(props: Any): LargeDataIds {
    val blobs = mutableSetOf<Int>()
    val strings = mutableSetOf<Int>()
    props::class.memberProperties.forEach { property ->
        try {
            when (val value = property.getter.call(props)) {
                null -> Unit
                is LargeBlobID -> blobs.add(value.id)
                is LargeStringID -> strings.add(value.id)
                is Collection<*> -> value.forEach {
                    when (it) {
                        is LargeBlobID -> blobs.add(it.id)
                        is LargeStringID -> strings.add(it.id)
                        else -> Unit
                    }
                }

                else -> Unit
            }
        } catch (e: Exception) {
            logger.error(e) { "Could not read property ${property.name} while looking for attached data" }
        }
    }
    return LargeDataIds(blobs, strings)
}

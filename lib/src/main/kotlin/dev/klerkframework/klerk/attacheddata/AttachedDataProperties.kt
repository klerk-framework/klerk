package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.logger
import kotlin.reflect.full.memberProperties

/**
 * Finds the [AttachedBlobID] and [AttachedStringID] values in a model's props, including those inside a `List` or
 * `Set`. Blobs and strings share one id space, so they need not be told apart here.
 *
 * Note that the result is a set, so a model that holds the same id in two properties yields it once. That is what
 * makes `Book(cover = blobX, thumbnail = blobX)` behave correctly when one of the two is cleared: the id is still in
 * the set afterwards, so it is not dropped.
 */
internal fun collectAttachedDataIds(props: Any): Set<Int> {
    val ids = mutableSetOf<Int>()
    props::class.memberProperties.forEach { property ->
        try {
            when (val value = property.getter.call(props)) {
                null -> Unit
                is AttachedBlobID -> ids.add(value.id)
                is AttachedStringID -> ids.add(value.id)
                is Collection<*> -> value.forEach {
                    when (it) {
                        is AttachedBlobID -> ids.add(it.id)
                        is AttachedStringID -> ids.add(it.id)
                        else -> Unit
                    }
                }

                else -> Unit
            }
        } catch (e: Exception) {
            logger.error(e) { "Could not read property ${property.name} while looking for attached data" }
        }
    }
    return ids
}

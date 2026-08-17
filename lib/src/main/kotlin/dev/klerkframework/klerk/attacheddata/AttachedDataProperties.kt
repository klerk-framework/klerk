package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.datatypes.BlobContainer
import dev.klerkframework.klerk.logger
import kotlin.reflect.full.memberProperties

/**
 * One attached-data reference found in a model's props, together with what the property declares about it.
 *
 * @property declaration the [BlobContainer] the reference was found in, or null for a bare id. It is what a command
 * checks the value against before letting the model claim it.
 */
internal data class AttachedDataReference(
    val id: Int,
    val declaration: BlobContainer? = null,
)

/**
 * Finds the [AttachedBlobID] and [AttachedStringID] values in a model's props, including those inside a `List` or
 * `Set`, and those wrapped in a [BlobContainer]. Blobs and strings share one id space, so they need not be told apart
 * here.
 *
 * Note that the result is keyed by id, so a model that holds the same id in two properties yields it once. That is
 * what makes `Book(cover = blobX, thumbnail = blobX)` behave correctly when one of the two is cleared: the id is
 * still in the set afterwards, so it is not dropped.
 */
internal fun collectAttachedData(props: Any): Map<Int, AttachedDataReference> {
    val found = mutableMapOf<Int, AttachedDataReference>()

    fun add(value: Any?) {
        when (value) {
            is AttachedBlobID -> found.putIfAbsent(value.id, AttachedDataReference(value.id))
            is AttachedStringID -> found.putIfAbsent(value.id, AttachedDataReference(value.id))
            is BlobContainer -> found[value.id.id] = AttachedDataReference(value.id.id, value)
            else -> Unit
        }
    }

    props::class.memberProperties.forEach { property ->
        try {
            when (val value = property.getter.call(props)) {
                null -> Unit
                is Collection<*> -> value.forEach { add(it) }
                else -> add(value)
            }
        } catch (e: Exception) {
            logger.error(e) { "Could not read property ${property.name} while looking for attached data" }
        }
    }
    return found
}

/** The ids alone, for the places that only need to know which values a model refers to. */
internal fun collectAttachedDataIds(props: Any): Set<Int> = collectAttachedData(props).keys

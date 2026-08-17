package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.datatypes.BlobContainer
import dev.klerkframework.klerk.logger
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
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

/**
 * Builds the container [kClass] around a particular blob, so that what it declares can be applied to that value.
 *
 * A container must therefore be constructible from an id alone — which is what a model property does anyway — and the
 * error says so plainly, because the alternative is a puzzling failure inside a job.
 *
 * @throws IllegalArgumentException if there is no such constructor, or if it threw.
 */
internal fun instantiateDeclaration(kClass: KClass<out BlobContainer>, id: AttachedBlobID): BlobContainer {
    val constructor = kClass.constructors.singleOrNull { it.parameters.size == 1 }
        ?: throw IllegalArgumentException(
            "${kClass.qualifiedName ?: kClass} cannot be used as a blob declaration: a BlobContainer must have " +
                    "exactly one constructor taking an AttachedBlobID, as 'class MyImage(id: AttachedBlobID) : " +
                    "BlobContainer(id)' does. An anonymous or inner class cannot be one."
        )
    return try {
        constructor.call(id) as BlobContainer
    } catch (e: Exception) {
        throw IllegalArgumentException("Could not build the blob declaration ${kClass.qualifiedName ?: kClass}", e)
    }
}

/**
 * The same, from the name a job cursor carries. The class is gone if it was renamed since the value was prepared, in
 * which case the job fails rather than silently letting an unprocessed value through.
 */
@Suppress("UNCHECKED_CAST")
internal fun instantiateDeclaration(className: String, id: AttachedBlobID): BlobContainer {
    val kClass = try {
        Class.forName(className).kotlin
    } catch (e: ClassNotFoundException) {
        throw IllegalArgumentException("There is no longer a blob declaration called '$className'", e)
    }
    require(kClass.isSubclassOf(BlobContainer::class)) { "$className is not a BlobContainer" }
    return instantiateDeclaration(kClass as KClass<out BlobContainer>, id)
}

/** The ids alone, for the places that only need to know which values a model refers to. */
internal fun collectAttachedDataIds(props: Any): Set<Int> = collectAttachedData(props).keys

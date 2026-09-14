package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.datatypes.AttachedDataContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.Shape
import kotlin.reflect.KClass

/**
 * One attached-data reference found in a model's props, together with what the property declares about it.
 *
 * @property declaration the [AttachedDataContainer] the reference was found in, or null for a bare id. It is what a
 * command checks the value against before letting the model claim it.
 */
internal data class AttachedDataReference(
    val id: Int,
    val declaration: AttachedDataContainer<*>? = null,
)

/**
 * Finds the [AttachedBlobID] and [AttachedStringID] values in a model's props, including those inside a `List`, a
 * `Set` or a nested object, and those wrapped in an [AttachedDataContainer]. Blobs and strings share one id space, so
 * they need not be told apart here.
 *
 * Note that the result is keyed by id, so a model that holds the same id in two properties yields it once. That is
 * what makes `Book(cover = blobX, thumbnail = blobX)` behave correctly when one of the two is cleared: the id is
 * still in the set afterwards, so it is not dropped.
 */
internal fun collectAttachedData(props: Any): Map<Int, AttachedDataReference> {
    val found = mutableMapOf<Int, AttachedDataReference>()
    ObjectSchema.of(props::class).forEachLeaf(props) { leaf ->
        when (val value = leaf.value) {
            is AttachedBlobID -> found.putIfAbsent(value.value, AttachedDataReference(value.value))
            is AttachedStringID -> found.putIfAbsent(value.value, AttachedDataReference(value.value))
            is AttachedDataContainer<*> -> found[value.rawId] = AttachedDataReference(value.rawId, value)
            else -> Unit
        }
    }
    return found
}

/**
 * Builds the container [kClass] around a particular attached value, so that what it declares can be applied to that
 * value.
 *
 * A container must therefore be constructible from an id alone — which is what a model property does anyway — and the
 * error says so plainly, because the alternative is a puzzling failure inside a job.
 *
 * @throws IllegalArgumentException if there is no such constructor, or if it threw.
 */
internal fun <ID, C : AttachedDataContainer<ID>> instantiateDeclaration(kClass: KClass<out C>, id: ID): C {
    val container = try {
        Shape.Container.of(kClass)
    } catch (e: IllegalConfigurationException) {
        throw IllegalArgumentException(
            "${kClass.qualifiedName ?: kClass} cannot be used as an attached-data declaration: it must have " +
                    "exactly one constructor taking an id, and it must be public, as 'class MyImage(id: " +
                    "AttachedBlobID) : AttachedBlobContainer(id)' does. An anonymous or inner class cannot be one.", e
        )
    }
    @Suppress("UNCHECKED_CAST")
    return try {
        container.create(id as Any) as C
    } catch (e: Exception) {
        throw IllegalArgumentException("Could not build the declaration ${kClass.qualifiedName ?: kClass}", e)
    }
}

/**
 * The same, from the name a job cursor carries, looked up among the [declarations] the specification uses. The class
 * is gone if it was renamed since the value was prepared, in which case the job fails rather than silently letting an
 * unprocessed value through.
 *
 * Blob-only: only a blob ever has pre-attach steps to resume, so only a blob ever has a job cursor to rebuild from.
 */
internal fun instantiateDeclaration(
    className: String,
    id: AttachedBlobID,
    declarations: Map<String, KClass<out AttachedBlobContainer>>,
): AttachedBlobContainer {
    val kClass = declarations[className]
        ?: throw IllegalArgumentException("There is no longer a blob declaration called '$className'")
    return instantiateDeclaration(kClass, id)
}

/** The ids alone, for the places that only need to know which values a model refers to. */
internal fun collectAttachedDataIds(props: Any): Set<Int> = collectAttachedData(props).keys

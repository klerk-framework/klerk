package dev.klerkframework.klerk

import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.serialization.Serializable

/**
 * Identifies a single state within a model's state machine, e.g. `s.Book.Published`.
 */
public data class StateID(val modelName: String, val stateName: String) {
    override fun toString(): String = "s.$modelName.$stateName"

    /** The id without its `s.` prefix, e.g. `Book.Published`. */
    public fun withoutPrefix(): String = toString().substring(2)
}

/**
 * Identifies a [ModelView][dev.klerkframework.klerk.view.ModelView] within a
 * [ModelViews][dev.klerkframework.klerk.view.ModelViews] container, e.g. `v.Book.all`.
 */
public data class ViewID(val modelName: String, val shortId: String) {
    override fun toString(): String = "v.$modelName.$shortId"

    public companion object {
        /**
         * @throws IllegalArgumentException if [value] is not of the form `v.<modelName>.<shortId>`
         */
        public fun parse(value: String): ViewID {
            val parts = value.split(".")
            require(parts.size == 3) { "ViewID must contain three parts separated by dots" }
            require(parts.first() == "v") { "ViewID must start with 'v.'" }
            return ViewID(parts[1], parts[2])
        }

        /** The id in [value], or null if it is not one. */
        public fun parseOrNull(value: String): ViewID? = runCatching { parse(value) }.getOrNull()
    }
}

/**
 * An identifier of a model of type [T].
 *
 * Model IDs are represented internally using Int but only the positive part, so the maximum amount of simultaneous
 * models is about 2 billion (we should find a way to use UInt).
 *
 * Implementation details: We first used UInt, but it seems that there is a problem when making this @JvmInline and
 * value class in combination with ULong and UInt (see KT-69674).
 *
 * The `@Serializable` annotation lets a job cursor hold a [ModelID] without the job author doing anything.
 */
@Serializable(with = ModelIDSerializer::class)
@JvmInline
public value class ModelID<T : Any>(public val value: Int) {

    override fun toString(): String = value.toString()
}

/**
 * A reference to a large blob attached to a model (see [KlerkAttachedData]).
 *
 * Obtain one from [KlerkAttachedData.prepare] and store it in a model property. The data is owned exclusively by the
 * first model that references it in a committed command, and is deleted when no property of that model refers to it
 * any more.
 *
 * Blobs and strings share one id space, so an id identifies a piece of attached data on its own — see
 * [AttachedDataKind].
 *
 * Implementation details: see the note on [ModelID] regarding @JvmInline and serialization.
 */
@Serializable(with = AttachedBlobIDSerializer::class)
@JvmInline
public value class AttachedBlobID(public val value: Int) {
    override fun toString(): String = value.toString()

    /** The same reference, with the kind forgotten — see [AttachedDataID]. */
    public fun untyped(): AttachedDataID = AttachedDataID(value)
}

/**
 * A reference to a large string attached to a model (see [KlerkAttachedData]).
 *
 * Obtain one from [KlerkAttachedData.prepare] and store it in a model property. The data is owned exclusively by the
 * first model that references it in a committed command, and is deleted when no property of that model refers to it
 * any more.
 *
 * Blobs and strings share one id space, so an id identifies a piece of attached data on its own — see
 * [AttachedDataKind].
 *
 * Implementation details: see the note on [ModelID] regarding @JvmInline and serialization.
 */
@Serializable(with = AttachedStringIDSerializer::class)
@JvmInline
public value class AttachedStringID(public val value: Int) {
    override fun toString(): String = value.toString()

    /** The same reference, with the kind forgotten — see [AttachedDataID]. */
    public fun untyped(): AttachedDataID = AttachedDataID(value)
}

/**
 * A reference to attached data whose kind is not known yet — what an HTTP route such as `/attached/{id}/{hash}`
 * holds.
 *
 * Blobs and strings share one id space, so this identifies a value on its own. Ask
 * [KlerkAttachedData.getMetadata] what it is: [AttachedDataMetadata.kind] says which kind it turned out to be, and
 * [asBlob]/[asString] then give the typed id needed to read the value.
 *
 * ```kotlin
 * val meta = klerk.attachedData.getMetadata(id, context)
 * val stream = when (meta.kind) {
 *     AttachedDataKind.Blob -> klerk.attachedData.get(id.asBlob(), context)
 *     AttachedDataKind.String -> klerk.attachedData.getStream(id.asString(), context)
 * }
 * ```
 */
@JvmInline
public value class AttachedDataID(public val value: Int) {
    override fun toString(): String = value.toString()

    /** This reference as a blob id. Reading a value that is a string through it throws. */
    public fun asBlob(): AttachedBlobID = AttachedBlobID(value)

    /** This reference as a string id. Reading a value that is a blob through it throws. */
    public fun asString(): AttachedStringID = AttachedStringID(value)

    public companion object {
        /** @throws IllegalArgumentException if [value] is not an id */
        public fun parse(value: String): AttachedDataID =
            AttachedDataID(requireNotNull(value.toIntOrNull()) { "Not an attached data id: '$value'" })

        /** The id in [value], or null if it is not one. For parsing a path parameter. */
        public fun parseOrNull(value: String?): AttachedDataID? = value?.toIntOrNull()?.let { AttachedDataID(it) }
    }
}

package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataKind
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedDataReader
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.impl

/**
 * The `attachedData` accessor of a [ReaderWithAuth]: the same rules `klerk.attachedData.getMetadata` applies, but
 * evaluated without taking the read lock, since the surrounding read block already holds it.
 */
internal class AuthorizingAttachedDataReader<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val context: C,
) : AttachedDataReader {

    private var finished = false

    override fun getMetadata(id: AttachedDataID): AttachedDataMetadata = read(id, expected = null)

    override fun getMetadataOrNull(id: AttachedDataID): AttachedDataMetadata? = try {
        read(id, expected = null)
    } catch (e: NoSuchElementException) {
        null
    } catch (e: AuthorizationException) {
        null
    }

    override fun getMetadata(id: AttachedBlobID): AttachedDataMetadata = read(id.untyped(), AttachedDataKind.Blob)

    override fun getMetadata(id: AttachedStringID): AttachedDataMetadata = read(id.untyped(), AttachedDataKind.String)

    /** Mirrors `ReaderWithAuth.finishRead`: a reader smuggled out of its block must not keep reading. */
    fun finish() {
        finished = true
    }

    private fun read(id: AttachedDataID, expected: AttachedDataKind?): AttachedDataMetadata {
        check(!finished) { "The reader cannot be used after its read has finished" }
        return klerk.impl().attachedDataImpl.metadataWithLockHeld(id, expected, context)
    }
}

/**
 * The `attachedData` accessor of a [ReaderWithoutAuth]: no rules are evaluated, matching how that reader treats
 * models.
 */
internal class UnauthorizedAttachedDataReader<C : KlerkContext, V>(private val klerk: Klerk<C, V>) :
    AttachedDataReader {

    override fun getMetadata(id: AttachedDataID): AttachedDataMetadata = read(id, expected = null)

    override fun getMetadataOrNull(id: AttachedDataID): AttachedDataMetadata? = try {
        read(id, expected = null)
    } catch (e: NoSuchElementException) {
        null
    }

    override fun getMetadata(id: AttachedBlobID): AttachedDataMetadata = read(id.untyped(), AttachedDataKind.Blob)

    override fun getMetadata(id: AttachedStringID): AttachedDataMetadata = read(id.untyped(), AttachedDataKind.String)

    private fun read(id: AttachedDataID, expected: AttachedDataKind?): AttachedDataMetadata =
        klerk.impl().attachedDataImpl.metadataWithLockHeldWithoutAuth(id, expected)
}

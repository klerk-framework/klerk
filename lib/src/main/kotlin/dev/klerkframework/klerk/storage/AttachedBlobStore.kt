package dev.klerkframework.klerk.storage

import mu.KotlinLogging
import java.io.InputStream
import java.nio.file.*
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

/**
 * Where the bytes of attached blobs are kept.
 *
 * Attached strings always live in the database; only blobs can grow large enough for the choice to matter. The choice
 * decides what a database backup contains, so it is required as soon as the specification declares an
 * [dev.klerkframework.klerk.AttachedBlobID] anywhere.
 *
 * **Pick the store before you have data in it.** Klerk does not move existing blobs, and refuses to start if the
 * configured store does not have the bytes it expects.
 */
public sealed interface AttachedBlobStore {

    /**
     * Blob bytes are a column of the attached-data row, written and deleted in the same transaction as everything
     * else. Simple and fully transactional, but bounded by what the database can hold in one value (about 1 GB
     * depending on the database).
     */
    public data object Database : AttachedBlobStore

    /**
     * Blob bytes live outside the database. Implement this to store them somewhere Klerk does not know about — a
     * filesystem ([FileBlobStore]), an object store, a NAS.
     *
     * Two consequences follow from the bytes not being in the transaction, and both are handled by Klerk rather than
     * by implementations: bytes are written before the row is committed and deleted after it, so a crash can leave
     * bytes nothing refers to. Those orphans are swept at startup via [listIds]. An implementation therefore only has
     * to be correct for one value at a time.
     */
    public interface External : AttachedBlobStore {

        /**
         * Stores [value] under [id], reading the stream to its end. The bytes must be durable when this returns, since
         * the row that refers to them is committed next.
         *
         * @throws java.io.IOException if the write fails. Klerk then abandons the id.
         */
        public fun put(id: Int, value: InputStream)

        /** The bytes stored under [id], or null if this store has none. */
        public fun get(id: Int): InputStream?

        /** Removes the bytes stored under [id]. Does nothing if there are none. */
        public fun delete(id: Int)

        /**
         * Takes ownership of an already written file instead of copying it, if this store can do so cheaply (a rename
         * within one filesystem, say). Lets a completed upload become a stored blob without moving its bytes twice.
         *
         * @return true if [source] is now stored under [id] and no longer exists at its old location, false if the
         * caller should fall back to [put]. Never throws for the merely-unsupported case.
         */
        public fun adopt(id: Int, source: Path): Boolean = false

        /**
         * Every id this store holds bytes for, or null if it cannot enumerate them cheaply.
         *
         * Called once at startup, for two checks: bytes without a row are orphans and are deleted, and a row whose
         * bytes are missing means the store was swapped under an existing database, which Klerk refuses to start on.
         * Returning null skips both.
         */
        public fun listIds(): Set<Int>? = null
    }
}

/**
 * An [AttachedBlobStore.External] that keeps each blob as a file under [root], in a directory named by the id modulo
 * 256 so that no single directory grows unmanageable.
 *
 * A database backup no longer contains the blobs — back up [root] as well.
 *
 * Put [root] on the same filesystem as anything that will [adopt] into it (an upload's staging area, typically):
 * across filesystems the rename fails and Klerk copies instead, which costs throughput but nothing else.
 */
public class FileBlobStore(private val root: Path) : AttachedBlobStore.External {

    init {
        Files.createDirectories(root)
        require(Files.isWritable(root)) { "The blob store directory $root is not writable" }
    }

    override fun put(id: Int, value: InputStream) {
        val target = pathFor(id)
        Files.createDirectories(target.parent)
        // CREATE_NEW: ids are never reused while their data exists, so an existing file means something is wrong and
        // silently overwriting it would destroy a blob that is still referenced.
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            value.copyTo(out)
            out.flush()
        }
        fsync(target)
    }

    override fun get(id: Int): InputStream? = try {
        Files.newInputStream(pathFor(id))
    } catch (e: NoSuchFileException) {
        null
    }

    override fun delete(id: Int) {
        Files.deleteIfExists(pathFor(id))
    }

    override fun adopt(id: Int, source: Path): Boolean {
        val target = pathFor(id)
        Files.createDirectories(target.parent)
        return try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            fsync(target)
            true
        } catch (e: AtomicMoveNotSupportedException) {
            // Different filesystems. The caller copies instead.
            false
        }
    }

    override fun listIds(): Set<Int> {
        val ids = mutableSetOf<Int>()
        Files.newDirectoryStream(root).use { buckets ->
            buckets.filter { it.isDirectory() }.forEach { bucket ->
                Files.newDirectoryStream(bucket).use { files ->
                    files.forEach { file ->
                        val id = file.name.toIntOrNull()
                        if (id == null) {
                            logger.warn { "Ignoring unexpected file in the blob store: $file" }
                        } else {
                            ids.add(id)
                        }
                    }
                }
            }
        }
        return ids
    }

    private fun pathFor(id: Int): Path = root.resolve((id % 256).toString()).resolve(id.toString())

    /** The row referring to these bytes is committed next, so the bytes have to survive a crash first. */
    private fun fsync(path: Path) {
        java.io.RandomAccessFile(path.toFile(), "rw").use { it.channel.force(true) }
    }
}

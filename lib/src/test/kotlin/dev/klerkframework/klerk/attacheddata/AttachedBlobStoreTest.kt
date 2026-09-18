package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorPicture
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.Book
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.CreateAuthorParams
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.DeleteAuthor
import dev.klerkframework.klerk.FirstName
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.KlerkSettings
import dev.klerkframework.klerk.LastName
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.PhoneNumber
import dev.klerkframework.klerk.SQLiteInMemory
import dev.klerkframework.klerk.SecretPasscode
import dev.klerkframework.klerk.SpecificationBuilder
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.authorStateMachine
import dev.klerkframework.klerk.bookStateMachine
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.createConfig
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.generousAuthRules
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.FileBlobStore
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The rules around choosing where blob bytes live: the choice is required, it cannot be changed once there is data,
 * and bytes without a row are cleaned up.
 */
class AttachedBlobStoreTest {

    private fun tempDir(): Path = Files.createTempDirectory("klerk-blobs-test")

    private suspend fun start(storage: Persistence, store: AttachedBlobStore): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, storage, blobStore = store)
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private suspend fun createAuthorWithPicture(klerk: Klerk<Ctx, Views>, picture: AttachedBlobID): ModelID<Author> {
        val result = klerk.handle(
            Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Astrid"),
                    lastName = LastName("Lindgren"),
                    phone = PhoneNumber("+4699999"),
                    secretToken = SecretPasscode(1),
                    picture = picture?.let { AuthorPicture(it) },
                ),
            ),
            Ctx.system(),
        )
        return requireNotNull(result.getOrThrow().primaryModel)
    }

    @Test
    fun `A specification that declares a blob must say where the bytes go`() {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val specification = SpecificationBuilder<Ctx, Views>(collections).build {
            managedModels {
                model(Book::class, bookStateMachine(collections), collections.books)
                model(Author::class, authorStateMachine(collections), collections.authors)
            }
            apply(generousAuthRules())
            systemContextProvider { Ctx(SystemIdentity) }
        }
        val e = assertFailsWith<IllegalConfigurationException> {
            Klerk.create(specification, KlerkSettings(persistence = RamStorage()))
        }
        assertEquals(KlerkErrorCode.MissingAttachedBlobStore, e.code)
        assertTrue(e.message!!.contains("Author.picture"), e.message!!)
    }

    @Test
    fun `Klerk refuses to start when the blob store does not have the data the database refers to`() = runBlocking {
        val storage = RamStorage()
        val klerk = start(storage, AttachedBlobStore.Database)
        val id = klerk.attachedData.prepare("a portrait".byteInputStream(), AuthorPicture::class, Ctx.system())
        createAuthorWithPicture(klerk, id)
        klerk.meta.stop()

        // the same database, but now with the bytes expected somewhere they have never been
        val e = assertFailsWith<IllegalConfigurationException> { start(storage, FileBlobStore(tempDir())) }
        assertEquals(KlerkErrorCode.AttachedBlobStoreMissingData, e.code)
        Unit
    }

    @Test
    fun `Bytes that no row refers to are deleted at startup`() = runBlocking {
        val dir = tempDir()
        val store = FileBlobStore(dir)
        // what a crash between writing the bytes and committing the row leaves behind
        store.put(4711, "orphaned".byteInputStream())
        assertTrue(store.listIds()!!.contains(4711))

        val klerk = start(RamStorage(), store)
        assertNull(store.get(4711))
        klerk.meta.stop()
    }

    @Test
    fun `A blob larger than the database would like round-trips through the file store`() = runBlocking {
        val klerk = start(RamStorage(), FileBlobStore(tempDir()))
        val content = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }

        val id = klerk.attachedData.prepare(content.inputStream(), AuthorPicture::class, Ctx.system())
        createAuthorWithPicture(klerk, id)

        assertEquals(content.size.toLong(), klerk.attachedData.getMetadata(id, Ctx.system()).size)
        assertTrue(klerk.attachedData.get(id, Ctx.system()).readAllBytes().contentEquals(content))
        klerk.meta.stop()
    }

    @Test
    fun `A lease keeps data alive longer than the default minute`() = runBlocking {
        val klerk = Klerk.create(
            createConfig(Views(BookViews(), AuthorViews(BookViews().all))),
            // an unclaimed value normally dies almost immediately here
            testSettings().copy(defaultAttachedDataLease = 1.milliseconds),
        )
        klerk.meta.start(installShutdownHook = false)

        val survives = "survives".byteInputStream()
        val leased = klerk.attachedData.prepare(survives, AuthorPicture::class, Ctx.system(), lease = 1.hours)
        val unleased = klerk.attachedData.prepare("does not".byteInputStream(), AuthorPicture::class, Ctx.system())
        Thread.sleep(20)

        // claiming forces the reaper's hand: the leased value is still there, the other one is gone
        createAuthorWithPicture(klerk, leased)
        val failure = klerk.handle(
            Command(
                CreateAuthor,
                CreateAuthorParams(
                    firstName = FirstName("Selma"),
                    lastName = LastName("Lagerlöf"),
                    phone = PhoneNumber("+4611111"),
                    secretToken = SecretPasscode(2),
                    picture = AuthorPicture(unleased),
                ),
            ),
            Ctx.system(),
        )
        assertTrue(failure is CommandResult.Failure, "The unleased value should have expired, but got $failure")
        klerk.meta.stop()
    }

    @Test
    fun `The metadata says what the bytes are, not what the uploader claimed`() = runBlocking {
        val storage = RamStorage()
        val klerk = start(storage, AttachedBlobStore.Database)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

        val id = klerk.attachedData.prepare(
            png.inputStream(),
            AuthorPicture::class,
            Ctx.system(),
            custom = mapOf("filename" to "totally-a-document.pdf"),
        )
        createAuthorWithPicture(klerk, id)

        val meta = klerk.attachedData.getMetadata(id, Ctx.system())
        assertEquals("image/png", meta.contentType)
        // the claim is kept, separately, and never confused with the finding
        assertEquals("totally-a-document.pdf", meta.custom["filename"])
        klerk.meta.stop()

        // and it survives a restart, since it is stored rather than re-derived
        val restarted = start(storage, AttachedBlobStore.Database)
        assertEquals("image/png", restarted.attachedData.getMetadata(id, Ctx.system()).contentType)
        restarted.meta.stop()
    }

    @Test
    fun `The content type survives a round-trip through SQL, not only through memory`() = runBlocking {
        val klerk = start(SQLiteInMemory.create(), AttachedBlobStore.Database)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

        val custom = mapOf("origin" to "camera")
        val id = klerk.attachedData.prepare(png.inputStream(), AuthorPicture::class, Ctx.system(), custom = custom)
        createAuthorWithPicture(klerk, id)

        // read back through the row rather than from the in-memory entry
        val row = requireNotNull(klerk.settings.persistence.readAllAttachedDataMetadata()[id.value])
        assertEquals("image/png", row.metadata.contentType)
        assertEquals(mapOf("origin" to "camera"), row.metadata.custom)
        klerk.meta.stop()
    }

    @Test
    fun `An application cannot pass off its own metadata as a Klerk finding`() = runBlocking {
        val klerk = start(RamStorage(), AttachedBlobStore.Database)
        assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare(
                "<html>evil</html>".byteInputStream(),
                AuthorPicture::class,
                Ctx.system(),
                custom = mapOf("__contentType" to "image/png"),
            )
        }
        klerk.meta.stop()
    }

    @Test
    fun `A lease longer than the maximum is refused`() = runBlocking {
        val klerk = start(RamStorage(), AttachedBlobStore.Database)
        assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare(
                "too long".byteInputStream(),
                AuthorPicture::class,
                Ctx.system(),
                lease = 48.hours,
            )
        }
        klerk.meta.stop()
    }

    @Test
    fun `A file is adopted rather than copied when the store can take it over`() = runBlocking {
        val dir = tempDir()
        val store = FileBlobStore(dir)
        val klerk = start(RamStorage(), store)

        // the staging file of a completed upload, on the same filesystem as the blob store
        val staged = Files.createTempFile(dir.parent, "staged", "")
        Files.write(staged, "adopt me".toByteArray())

        val id = klerk.attachedData.prepareFromFile(staged, AuthorPicture::class, Ctx.system())
        createAuthorWithPicture(klerk, id)

        assertFalse(Files.exists(staged), "the file should have been moved, not copied")
        assertEquals("adopt me", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        val meta = klerk.attachedData.getMetadata(id, Ctx.system())
        assertEquals(8, meta.size)
        assertEquals(sha256Hex("adopt me"), meta.hash, "the digest must be right even though the bytes were not copied")
        klerk.meta.stop()
    }

    @Test
    fun `A file is copied when the store keeps its bytes in the database`() = runBlocking {
        val klerk = start(RamStorage(), AttachedBlobStore.Database)
        val staged = Files.createTempFile("staged", "")
        Files.write(staged, "copy me".toByteArray())

        val id = klerk.attachedData.prepareFromFile(staged, AuthorPicture::class, Ctx.system())
        createAuthorWithPicture(klerk, id)

        assertTrue(Files.exists(staged), "a store that cannot adopt must leave the file alone")
        assertEquals("copy me", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        klerk.meta.stop()
    }

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `Deleting the owning model deletes the bytes`() = runBlocking {
        val dir = tempDir()
        val store = FileBlobStore(dir)
        val klerk = start(RamStorage(), store)

        val id = klerk.attachedData.prepare("bytes".byteInputStream(), AuthorPicture::class, Ctx.system())
        val authorID = createAuthorWithPicture(klerk, id)
        assertTrue(store.listIds()!!.contains(id.value))

        klerk.handle(
            Command(DeleteAuthor, authorID),
            Ctx.system(),
        ).getOrThrow()

        assertNull(store.get(id.value))
        klerk.meta.stop()
    }
}

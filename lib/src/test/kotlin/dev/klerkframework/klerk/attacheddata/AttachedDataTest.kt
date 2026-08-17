package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.FileBlobStore
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.async
import java.nio.file.Files
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

open class AttachedDataTest {

    /** Where blob bytes go. Overridden by [AttachedDataOnFileStoreTest] to run the whole suite against files. */
    protected open val blobStore: AttachedBlobStore = AttachedBlobStore.Database

    private suspend fun start(
        storage: Persistence = RamStorage(),
        settings: KlerkSettings = KlerkSettings()
    ): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = Klerk.create(createConfig(collections, storage, blobStore = blobStore), settings)
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private suspend fun createAuthorWithPicture(
        klerk: Klerk<Ctx, Views>,
        picture: AttachedBlobID?,
        lastName: String = "Lindgren",
        context: Ctx = Ctx.system(),
    ): ModelID<Author> {
        val result = klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName("Astrid"),
                    lastName = LastName(lastName),
                    phone = PhoneNumber("+4699999"),
                    secretToken = SecretPasscode(1),
                    picture = picture?.let { AuthorPicture(it) },
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
        return requireNotNull(result.orThrow().primaryModel)
    }

    private suspend fun setPicture(
        klerk: Klerk<Ctx, Views>,
        authorID: ModelID<Author>,
        picture: AttachedBlobID?,
        context: Ctx = Ctx.system(),
    ): CommandResult<Author, Ctx, Views> {
        val author = klerk.read(context) { get(authorID) }
        return klerk.handle(
            Command(event = UpdateAuthor, model = authorID, params = author.props.copy(picture = picture?.let { AuthorPicture(it) })),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
    }

    private fun blob(content: String) = content.toByteArray().inputStream()

    /** 24 bytes that PaintingImage will accept. */
    private fun png() =
        (byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)).inputStream()

    /** Attaches a blob to a property that declares itself Public. */
    private suspend fun hangPainting(klerk: Klerk<Ctx, Views>, image: AttachedBlobID): ModelID<Painting> {
        val result = klerk.handle(
            Command(
                event = CreatePainting,
                model = null,
                params = CreatePaintingParams(PaintingTitle("Sunflowers"), PaintingImage(image)),
            ),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        )
        return requireNotNull(result.orThrow().primaryModel)
    }

    private fun Klerk<Ctx, Views>.blobExists(id: AttachedBlobID): Boolean =
        runCatching { runBlocking { attachedData.get(id, Ctx.system()) } }.isSuccess

    @Test
    fun `Attaches a blob when the model is created`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("a portrait"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, id)

        val stored = klerk.read(Ctx.system()) { get(authorID).props.picture?.id }
        assertEquals(id, stored)
        assertEquals("a portrait", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `Attaches a blob when the model is updated`() = runBlocking {
        val klerk = start()
        val authorID = createAuthorWithPicture(klerk, null)
        assertNull(klerk.read(Ctx.system()) { get(authorID).props.picture?.id })

        val id = klerk.attachedData.prepare(blob("later"), Ctx.system())
        setPicture(klerk, authorID, id).orThrow()

        assertEquals(id, klerk.read(Ctx.system()) { get(authorID).props.picture?.id })
        klerk.meta.stop()
    }

    @Test
    fun `Attaches a string`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare("""{"some": "json"}""", Ctx.system())
        val bookID = createBookWithNotes(klerk, id)

        assertEquals(id, klerk.read(Ctx.system()) { get(bookID).props.notes })
        assertEquals("""{"some": "json"}""", klerk.attachedData.get(id, Ctx.system()))
        klerk.meta.stop()
    }

    @Test
    fun `A string can be streamed instead of brought into memory`() = runBlocking {
        val klerk = start()
        val content = "räksmörgås".repeat(10_000)
        val id = prepareString(klerk, content)

        assertEquals(content, String(klerk.attachedData.getStream(id, Ctx.system()).readAllBytes(), Charsets.UTF_8))
        klerk.meta.stop()
    }

    @Test
    fun `An id may only be used through the type it was prepared as`() = runBlocking {
        // blobs and strings share one id space, so the kind is what keeps them apart
        val klerk = start()
        val blobID = klerk.attachedData.prepare(blob("bytes"), Ctx.system())
        createAuthorWithPicture(klerk, blobID)
        val stringID = prepareString(klerk, "text")

        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.get(AttachedStringID(blobID.id), Ctx.system())
        }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.getMetadata(AttachedStringID(blobID.id), Ctx.system())
        }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.get(AttachedBlobID(stringID.id), Ctx.system())
        }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.getMetadata(AttachedBlobID(stringID.id), Ctx.system())
        }
        klerk.meta.stop()
    }

    @Test
    fun `The kind is not disclosed to an actor who may not read the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("members only"), Ctx.system())
        createAuthorWithPicture(klerk, id)

        // the wrong kind would also fail, but authorization is what decides, and it comes first
        assertFailsWith<AuthorizationException> {
            klerk.attachedData.getMetadata(AttachedStringID(id.id), Ctx.unauthenticated())
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata reports the kind`() = runBlocking {
        val klerk = start()
        val blobID = klerk.attachedData.prepare(blob("bytes"), Ctx.system())
        createAuthorWithPicture(klerk, blobID)
        val stringID = prepareString(klerk, "text")

        assertEquals(AttachedDataKind.Blob, klerk.attachedData.getMetadata(blobID, Ctx.system()).kind)
        assertEquals(AttachedDataKind.String, klerk.attachedData.getMetadata(stringID, Ctx.system()).kind)
        klerk.meta.stop()
    }

    @Test
    fun `A write rule can reject an upload because of its kind`() = runBlocking {
        val klerk = start()
        val context = Ctx.unauthenticated()

        // the rule in TestSetup denies unauthenticated actors strings, but not blobs
        assertNotNull(klerk.attachedData.prepare(blob("fine"), context))
        assertFailsWith<AuthorizationException> { klerk.attachedData.prepare("not fine", context) }
        klerk.meta.stop()
    }

    @Test
    fun `A second model cannot claim data owned by another model`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("mine"), Ctx.system())
        val firstOwner = createAuthorWithPicture(klerk, id)

        val result = createAuthorWithPictureExpectingFailure(klerk, id)
        val problem = result.problems.single()
        assertEquals(KlerkErrorCode.AttachedDataAlreadyOwned, problem.code)
        assertTrue(
            (problem as StateProblem).internalDescription.contains(firstOwner.value.toString()),
            "The problem should name the current owner but was: ${problem.internalDescription}"
        )
        // the first owner still has its data
        assertEquals(id, klerk.read(Ctx.system()) { get(firstOwner).props.picture?.id })
        assertTrue(klerk.blobExists(id))
        klerk.meta.stop()
    }

    @Test
    fun `Replacing a reference deletes the old data`() = runBlocking {
        val klerk = start()
        val old = klerk.attachedData.prepare(blob("old"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, old)

        val new = klerk.attachedData.prepare(blob("new"), Ctx.system())
        setPicture(klerk, authorID, new).orThrow()

        assertEquals("new", String(klerk.attachedData.get(new, Ctx.system()).readAllBytes()))
        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(old, Ctx.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `Setting a reference to null deletes the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("bye"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, id)

        setPicture(klerk, authorID, null).orThrow()

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Ctx.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `Setting one of two properties holding the same id to null does not delete the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("shared within the model"), Ctx.system())
        val bookID = createBookWithCoverAndThumbnail(klerk, cover = id, thumbnail = id)

        // drop the thumbnail; the cover still points at the same data
        val book = klerk.read(Ctx.system()) { get(bookID) }
        klerk.handle(
            Command(event = UpdateBook, model = bookID, params = book.props.copy(thumbnail = null)),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertEquals("shared within the model", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `Deleting a model deletes everything it owned`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("gone with the model"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, id)

        klerk.handle(
            Command(event = DeleteAuthor, model = authorID, params = null),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Ctx.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `A command that fails after a property changed leaves the data intact`() = runBlocking {
        val klerk = start()
        val old = klerk.attachedData.prepare(blob("survivor"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, old)

        // 'James Clavell' is rejected by the Author validator, so the whole command fails
        val author = klerk.read(Ctx.system()) { get(authorID) }
        val newPicture = klerk.attachedData.prepare(blob("never attached"), Ctx.system())
        val result = klerk.handle(
            Command(
                event = UpdateAuthor,
                model = authorID,
                params = author.props.copy(
                    firstName = FirstName("James"),
                    lastName = LastName("Clavell"),
                    picture = newPicture?.let { AuthorPicture(it) }
                )
            ),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        )
        assertTrue(result is CommandResult.Failure)

        assertEquals("survivor", String(klerk.attachedData.get(old, Ctx.system()).readAllBytes()))
        assertEquals(old, klerk.read(Ctx.system()) { get(authorID).props.picture?.id })
        klerk.meta.stop()
    }

    @Test
    fun `Unclaimed data disappears when it expires`() = runBlocking {
        val klerk = start(settings = KlerkSettings(unclaimedAttachedDataLifetime = 1.milliseconds))
        val id = klerk.attachedData.prepare(blob("too slow"), Ctx.system())
        Thread.sleep(30)

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Ctx.system()) }

        val result = createAuthorWithPictureExpectingFailure(klerk, id)
        assertEquals(KlerkErrorCode.AttachedDataNotFound, result.problems.single().code)
        klerk.meta.stop()
    }

    @Test
    fun `A context clock in the future does not extend the claim window`() = runBlocking {
        val klerk = start(settings = KlerkSettings(unclaimedAttachedDataLifetime = 1.milliseconds))
        val distantFuture = Ctx(SystemIdentity, time = Clock.System.now().plus(365.days))
        val id = klerk.attachedData.prepare(blob("no time travel"), distantFuture)
        Thread.sleep(30)

        val result = createAuthorWithPictureExpectingFailure(klerk, id, context = distantFuture)
        assertEquals(KlerkErrorCode.AttachedDataNotFound, result.problems.single().code)
        klerk.meta.stop()
    }

    @Test
    fun `A context clock in the past does not shorten the claim window`() = runBlocking {
        val klerk = start(settings = KlerkSettings(unclaimedAttachedDataLifetime = 10.minutes))
        val distantPast = Ctx(SystemIdentity, time = Clock.System.now().minus(365.days))
        val id = klerk.attachedData.prepare(blob("still here"), distantPast)

        createAuthorWithPicture(klerk, id, context = distantPast)
        assertEquals("still here", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `get inside a read block throws`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("locked"), Ctx.system())
        createAuthorWithPicture(klerk, id)

        val fromNonSuspendingRead = assertFailsWith<IllegalStateException> {
            klerk.read(Ctx.system()) {
                runBlocking { klerk.attachedData.get(id, Ctx.system()) }
            }
        }
        assertTrue(fromNonSuspendingRead.message!!.contains("must not be called inside a read block"))

        assertFailsWith<IllegalStateException> {
            klerk.readSuspend(Ctx.system()) { klerk.attachedData.get(id, Ctx.system()) }
        }
        klerk.meta.stop()
    }

    @Test
    fun `Concurrent prepare calls never collide`() = runBlocking {
        val klerk = start()
        val prepared = (1..100).map { i ->
            async { klerk.attachedData.prepare("value $i", Ctx.system()) to i }
        }.awaitAll()

        assertEquals(100, prepared.map { it.first }.toSet().size, "Two concurrent prepare calls got the same id")

        // attach them all so that the values can be read back — a collision would have overwritten one of them
        createBookWithChapters(klerk, prepared.map { it.first })
        prepared.forEach { (id, i) ->
            assertEquals("value $i", klerk.attachedData.get(id, Ctx.system()))
        }
        klerk.meta.stop()
    }

    @Test
    fun `Unclaimed data cannot be read`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("not attached yet"), Ctx.system())
        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Ctx.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `The read rule can reach the owning model`() = runBlocking {
        val klerk = start()
        val readable = klerk.attachedData.prepare(blob("public"), Ctx.system())
        val secret = klerk.attachedData.prepare(blob("secret"), Ctx.system())
        createAuthorWithPicture(klerk, readable, lastName = "Lindgren")
        createAuthorWithPicture(klerk, secret, lastName = "Secretive")

        val context = Ctx.authenticationIdentity()
        assertEquals("public", String(klerk.attachedData.get(readable, context).readAllBytes()))
        assertFailsWith<AuthorizationException> { klerk.attachedData.get(secret, context) }
        klerk.meta.stop()
    }

    @Test
    fun `A negative read rule denies`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("members only"), Ctx.system())
        createAuthorWithPicture(klerk, id)

        assertFailsWith<AuthorizationException> { klerk.attachedData.get(id, Ctx.unauthenticated()) }
        klerk.meta.stop()
    }

    @Test
    fun `Data is private unless something else is asked for`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("members only"), Ctx.system())
        createAuthorWithPicture(klerk, id)

        assertEquals(AttachedDataVisibility.Private, klerk.attachedData.getMetadata(id, Ctx.system()).visibility)
        assertFailsWith<AuthorizationException> { klerk.attachedData.get(id, Ctx.unauthenticated()) }
        klerk.meta.stop()
    }

    @Test
    fun `Public data bypasses both positive and negative read rules`() = runBlocking {
        val klerk = start()
        // PaintingImage declares Public, so attaching the blob publishes it and no read rule is consulted — not even
        // the negative one that denies unauthenticated actors everything.
        val id = klerk.attachedData.prepare(png(), Ctx.system())
        hangPainting(klerk, id)

        val unauthenticated = Ctx.unauthenticated()
        assertEquals(24, klerk.attachedData.get(id, unauthenticated).readAllBytes().size)
        assertEquals(AttachedDataVisibility.Public, klerk.attachedData.getMetadata(id, unauthenticated).visibility)
        klerk.meta.stop()
    }

    @Test
    fun `A write rule can reject a public string`() = runBlocking {
        val klerk = start()
        // A string has no container, so it is the one kind whose visibility is still chosen at prepare — and
        // therefore the one kind a write rule can still decide about.
        val context = Ctx.authenticationIdentity()

        assertNotNull(klerk.attachedData.prepare("mine", context, AttachedDataVisibility.Private))
        assertFailsWith<AuthorizationException> {
            klerk.attachedData.prepare("everyone's", context, AttachedDataVisibility.Public)
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata of private data is authorized like the value`() = runBlocking {
        val klerk = start()
        val readable = klerk.attachedData.prepare(blob("readable"), Ctx.system())
        val secret = klerk.attachedData.prepare(blob("secret"), Ctx.system())
        createAuthorWithPicture(klerk, readable, lastName = "Lindgren")
        createAuthorWithPicture(klerk, secret, lastName = "Secretive")

        val context = Ctx.authenticationIdentity()
        assertEquals(8L, klerk.attachedData.getMetadata(readable, context).size)
        assertFailsWith<AuthorizationException> { klerk.attachedData.getMetadata(secret, context) }
        klerk.meta.stop()
    }

    @Test
    fun `The hash and the size describe the content`() = runBlocking {
        val klerk = start()
        // echo -n hello | sha256sum
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        val blobID = klerk.attachedData.prepare(blob("hello"), Ctx.system())
        val stringID = klerk.attachedData.prepare("hello", Ctx.system())
        createAuthorWithPicture(klerk, blobID)
        createBookWithChapters(klerk, listOf(stringID))

        val blobMeta = klerk.attachedData.getMetadata(blobID, Ctx.system())
        assertEquals(expected, blobMeta.hash)
        assertEquals(5L, blobMeta.size)
        val stringMeta = klerk.attachedData.getMetadata(stringID, Ctx.system())
        assertEquals(expected, stringMeta.hash)
        assertEquals(5L, stringMeta.size)
        klerk.meta.stop()
    }

    @Test
    fun `The hash and the size cover a blob larger than one buffer`() = runBlocking {
        val klerk = start()
        val content = "abcdefghij".repeat(10_000)   // 100 kB, i.e. many reads
        val id = klerk.attachedData.prepare(content.toByteArray().inputStream(), Ctx.system())
        createAuthorWithPicture(klerk, id)

        val meta = klerk.attachedData.getMetadata(id, Ctx.system())
        assertEquals(content.length.toLong(), meta.size)
        assertEquals(klerk.attachedData.getMetadata(prepareString(klerk, content), Ctx.system()).hash, meta.hash)
        klerk.meta.stop()
    }

    @Test
    fun `The metadata is not available before the data is claimed`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("not attached yet"), Ctx.system())
        assertFailsWith<NoSuchElementException> { klerk.attachedData.getMetadata(id, Ctx.system()) }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.getMetadata(
                AttachedBlobID(4711),
                Ctx.system()
            )
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata goes away with the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("temporary"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, id)
        assertNotNull(klerk.attachedData.getMetadata(id, Ctx.system()))

        setPicture(klerk, authorID, null).orThrow()
        assertFailsWith<NoSuchElementException> { klerk.attachedData.getMetadata(id, Ctx.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `Custom metadata is stored as given`() = runBlocking {
        val klerk = start()
        val custom = mapOf("contentType" to "image/webp", "width" to "1200")
        val id = klerk.attachedData.prepare(blob("an image"), Ctx.system(), metadata = custom)
        createAuthorWithPicture(klerk, id)

        assertEquals(custom, klerk.attachedData.getMetadata(id, Ctx.system()).custom)
        klerk.meta.stop()
    }

    @Test
    fun `Custom metadata that would bloat the cache is rejected`() = runBlocking {
        val klerk = start()
        assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare(blob("x"), Ctx.system(), metadata = mapOf("big" to "y".repeat(1000)))
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata survives a restart`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val klerk = start(storage)
        val custom = mapOf("claimedBy" to "the uploader")
        val id = klerk.attachedData.prepare(png(), Ctx.system(), metadata = custom)
        // attached to a property that declares Public, so that the visibility is worth checking after a restart
        hangPainting(klerk, id)
        val before = klerk.attachedData.getMetadata(id, Ctx.system())
        klerk.meta.stop()

        val restarted = start(storage)
        assertEquals(before, restarted.attachedData.getMetadata(id, Ctx.system()))
        assertEquals(AttachedDataVisibility.Public, before.visibility)
        assertEquals(custom, before.custom)
        restarted.meta.stop()
    }

    @Test
    fun `getMetadata inside a read block throws`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("locked"), Ctx.system())
        createAuthorWithPicture(klerk, id)

        assertFailsWith<IllegalStateException> {
            klerk.readSuspend(Ctx.system()) { klerk.attachedData.getMetadata(id, Ctx.system()) }
        }
        klerk.meta.stop()
    }

    /** Attaches a string to a book so that its metadata becomes readable. */
    private suspend fun prepareString(klerk: Klerk<Ctx, Views>, content: String): AttachedStringID {
        val id = klerk.attachedData.prepare(content, Ctx.system())
        createBookWithChapters(klerk, listOf(id))
        return id
    }

    @Test
    fun `A list of ids is claimed and dropped like a single one`() = runBlocking {
        val klerk = start()
        val first = klerk.attachedData.prepare("chapter one", Ctx.system())
        val second = klerk.attachedData.prepare("chapter two", Ctx.system())
        val bookID = createBookWithChapters(klerk, listOf(first, second))

        assertEquals("chapter one", klerk.attachedData.get(first, Ctx.system()))

        val book = klerk.read(Ctx.system()) { get(bookID) }
        klerk.handle(
            Command(event = UpdateBook, model = bookID, params = book.props.copy(chapters = listOf(second))),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(first, Ctx.system()) }
        assertEquals("chapter two", klerk.attachedData.get(second, Ctx.system()))
        klerk.meta.stop()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `The ids are serialized as plain numbers`() = runBlocking {
        // see the note on ModelID in Types.kt: a value class can leak its field name into the serialized form
        val klerk = start()
        val picture = klerk.attachedData.prepare(blob("x"), Ctx.system())
        val chapter = klerk.attachedData.prepare("y", Ctx.system())
        val authorID = createAuthorWithPicture(klerk, picture)
        val bookID = createBookWithChapters(klerk, listOf(chapter))

        val authorJson = klerk.config.toJson(klerk.read(Ctx.system()) { get(authorID) }.props)
        assertTrue(authorJson.contains("\"picture\":${picture.id}"), "Unexpected JSON: $authorJson")
        val bookJson = klerk.config.toJson(klerk.read(Ctx.system()) { get(bookID) }.props)
        assertTrue(bookJson.contains("\"chapters\":[${chapter.id}]"), "Unexpected JSON: $bookJson")
        klerk.meta.stop()
    }

    @Test
    fun `Attached data survives a restart`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val klerk = start(storage)
        val claimed = klerk.attachedData.prepare(blob("kept"), Ctx.system())
        val unclaimed = klerk.attachedData.prepare(blob("dropped"), Ctx.system())
        val authorID = createAuthorWithPicture(klerk, claimed)
        val chapter = klerk.attachedData.prepare("a chapter", Ctx.system())
        val bookID = createBookWithChapters(klerk, listOf(chapter))
        klerk.meta.stop()

        val restarted = start(storage)
        // this also checks that the value classes survive Gson and the database — both the nullable (boxed) property
        // and the one inside a List
        assertEquals(claimed, restarted.read(Ctx.system()) { get(authorID).props.picture?.id })
        assertEquals(listOf(chapter), restarted.read(Ctx.system()) { get(bookID).props.chapters })
        assertEquals("a chapter", restarted.attachedData.get(chapter, Ctx.system()))
        assertEquals("kept", String(restarted.attachedData.get(claimed, Ctx.system()).readAllBytes()))
        // the unclaimed one is still within its window, so it is reserved rather than reaped
        assertFailsWith<NoSuchElementException> { restarted.attachedData.get(unclaimed, Ctx.system()) }
        restarted.meta.stop()
    }

    private suspend fun createBook(
        klerk: Klerk<Ctx, Views>,
        params: (CreateBookParams) -> CreateBookParams
    ): ModelID<Book> {
        val author = createAuthorWithPicture(klerk, null, lastName = "Author of the book")
        val base = CreateBookParams(
            title = BookTitle("A book"),
            author = author,
            averageScore = AverageScore(0f),
            readingTime = ReadingTime(2.hours),
        )
        val result = klerk.handle(
            Command(event = CreateBook, model = null, params = params(base)),
            Ctx.system(),
            ProcessingOptions(CommandToken.simple()),
        )
        return requireNotNull(result.orThrow().primaryModel)
    }

    private suspend fun createBookWithNotes(klerk: Klerk<Ctx, Views>, notes: AttachedStringID) =
        createBook(klerk) { it.copy(notes = notes) }

    private suspend fun createBookWithCoverAndThumbnail(
        klerk: Klerk<Ctx, Views>,
        cover: AttachedBlobID,
        thumbnail: AttachedBlobID
    ) = createBook(klerk) { it.copy(cover = cover?.let { c -> BookCover(c) }, thumbnail = thumbnail?.let { t -> BookThumbnail(t) }) }

    private suspend fun createBookWithChapters(
        klerk: Klerk<Ctx, Views>,
        chapters: List<AttachedStringID>
    ) = createBook(klerk) { it.copy(chapters = chapters) }

    private suspend fun createAuthorWithPictureExpectingFailure(
        klerk: Klerk<Ctx, Views>,
        picture: AttachedBlobID,
        context: Ctx = Ctx.system(),
    ): CommandResult.Failure<Author, Ctx, Views> {
        val result = klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName("Selma"),
                    lastName = LastName("Lagerlöf"),
                    phone = PhoneNumber("+4611111"),
                    secretToken = SecretPasscode(2),
                    picture = picture?.let { AuthorPicture(it) },
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        return result
    }

}

/**
 * The entire attached-data suite again, with blob bytes on disk instead of in the database. The two stores must be
 * indistinguishable from the outside — that is the whole point of the SPI.
 */
class AttachedDataOnFileStoreTest : AttachedDataTest() {
    override val blobStore: AttachedBlobStore = FileBlobStore(Files.createTempDirectory("klerk-blobs"))
}

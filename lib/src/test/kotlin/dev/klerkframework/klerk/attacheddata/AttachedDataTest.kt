package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class AttachedDataTest {

    private suspend fun start(
        storage: Persistence = RamStorage(),
        settings: KlerkSettings = KlerkSettings()
    ): Klerk<Context, MyCollections> {
        val bookViews = BookViews()
        val collections = MyCollections(bookViews, AuthorViews(bookViews.all))
        val klerk = Klerk.create(createConfig(collections, storage), settings)
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private suspend fun createAuthorWithPicture(
        klerk: Klerk<Context, MyCollections>,
        picture: AttachedBlobID?,
        lastName: String = "Lindgren",
        context: Context = Context.system(),
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
                    picture = picture,
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
        return requireNotNull(result.orThrow().primaryModel)
    }

    private suspend fun setPicture(
        klerk: Klerk<Context, MyCollections>,
        authorID: ModelID<Author>,
        picture: AttachedBlobID?,
        context: Context = Context.system(),
    ): CommandResult<Author, Context, MyCollections> {
        val author = klerk.read(context) { get(authorID) }
        return klerk.handle(
            Command(event = UpdateAuthor, model = authorID, params = author.props.copy(picture = picture)),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
    }

    private fun blob(content: String) = content.toByteArray().inputStream()

    private fun Klerk<Context, MyCollections>.blobExists(id: AttachedBlobID): Boolean =
        runCatching { runBlocking { attachedData.get(id, Context.system()) } }.isSuccess

    @Test
    fun `Attaches a blob when the model is created`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("a portrait"), Context.system())
        val authorID = createAuthorWithPicture(klerk, id)

        val stored = klerk.read(Context.system()) { get(authorID).props.picture }
        assertEquals(id, stored)
        assertEquals("a portrait", String(klerk.attachedData.get(id, Context.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `Attaches a blob when the model is updated`() = runBlocking {
        val klerk = start()
        val authorID = createAuthorWithPicture(klerk, null)
        assertNull(klerk.read(Context.system()) { get(authorID).props.picture })

        val id = klerk.attachedData.prepare(blob("later"), Context.system())
        setPicture(klerk, authorID, id).orThrow()

        assertEquals(id, klerk.read(Context.system()) { get(authorID).props.picture })
        klerk.meta.stop()
    }

    @Test
    fun `Attaches a string`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare("""{"some": "json"}""", Context.system())
        val bookID = createBookWithNotes(klerk, id)

        assertEquals(id, klerk.read(Context.system()) { get(bookID).props.notes })
        assertEquals("""{"some": "json"}""", klerk.attachedData.get(id, Context.system()))
        klerk.meta.stop()
    }

    @Test
    fun `A string can be streamed instead of brought into memory`() = runBlocking {
        val klerk = start()
        val content = "räksmörgås".repeat(10_000)
        val id = prepareString(klerk, content)

        assertEquals(content, String(klerk.attachedData.getStream(id, Context.system()).readAllBytes(), Charsets.UTF_8))
        klerk.meta.stop()
    }

    @Test
    fun `An id may only be used through the type it was prepared as`() = runBlocking {
        // blobs and strings share one id space, so the kind is what keeps them apart
        val klerk = start()
        val blobID = klerk.attachedData.prepare(blob("bytes"), Context.system())
        createAuthorWithPicture(klerk, blobID)
        val stringID = prepareString(klerk, "text")

        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.get(AttachedStringID(blobID.id), Context.system())
        }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.getMetadata(AttachedStringID(blobID.id), Context.system())
        }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.get(AttachedBlobID(stringID.id), Context.system())
        }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.getMetadata(AttachedBlobID(stringID.id), Context.system())
        }
        klerk.meta.stop()
    }

    @Test
    fun `The kind is not disclosed to an actor who may not read the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("members only"), Context.system())
        createAuthorWithPicture(klerk, id)

        // the wrong kind would also fail, but authorization is what decides, and it comes first
        assertFailsWith<AuthorizationException> {
            klerk.attachedData.getMetadata(AttachedStringID(id.id), Context.unauthenticated())
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata reports the kind`() = runBlocking {
        val klerk = start()
        val blobID = klerk.attachedData.prepare(blob("bytes"), Context.system())
        createAuthorWithPicture(klerk, blobID)
        val stringID = prepareString(klerk, "text")

        assertEquals(AttachedDataKind.Blob, klerk.attachedData.getMetadata(blobID, Context.system()).kind)
        assertEquals(AttachedDataKind.String, klerk.attachedData.getMetadata(stringID, Context.system()).kind)
        klerk.meta.stop()
    }

    @Test
    fun `A write rule can reject an upload because of its kind`() = runBlocking {
        val klerk = start()
        val context = Context.unauthenticated()

        // the rule in TestSetup denies unauthenticated actors strings, but not blobs
        assertNotNull(klerk.attachedData.prepare(blob("fine"), context))
        assertFailsWith<AuthorizationException> { klerk.attachedData.prepare("not fine", context) }
        klerk.meta.stop()
    }

    @Test
    fun `A second model cannot claim data owned by another model`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("mine"), Context.system())
        val firstOwner = createAuthorWithPicture(klerk, id)

        val result = createAuthorWithPictureExpectingFailure(klerk, id)
        val problem = result.problems.single()
        assertEquals(KlerkErrorCode.AttachedDataAlreadyOwned, problem.code)
        assertTrue(
            (problem as StateProblem).internalDescription.contains(firstOwner.value.toString()),
            "The problem should name the current owner but was: ${problem.internalDescription}"
        )
        // the first owner still has its data
        assertEquals(id, klerk.read(Context.system()) { get(firstOwner).props.picture })
        assertTrue(klerk.blobExists(id))
        klerk.meta.stop()
    }

    @Test
    fun `Replacing a reference deletes the old data`() = runBlocking {
        val klerk = start()
        val old = klerk.attachedData.prepare(blob("old"), Context.system())
        val authorID = createAuthorWithPicture(klerk, old)

        val new = klerk.attachedData.prepare(blob("new"), Context.system())
        setPicture(klerk, authorID, new).orThrow()

        assertEquals("new", String(klerk.attachedData.get(new, Context.system()).readAllBytes()))
        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(old, Context.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `Setting a reference to null deletes the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("bye"), Context.system())
        val authorID = createAuthorWithPicture(klerk, id)

        setPicture(klerk, authorID, null).orThrow()

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Context.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `Setting one of two properties holding the same id to null does not delete the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("shared within the model"), Context.system())
        val bookID = createBookWithCoverAndThumbnail(klerk, cover = id, thumbnail = id)

        // drop the thumbnail; the cover still points at the same data
        val book = klerk.read(Context.system()) { get(bookID) }
        klerk.handle(
            Command(event = UpdateBook, model = bookID, params = book.props.copy(thumbnail = null)),
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertEquals("shared within the model", String(klerk.attachedData.get(id, Context.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `Deleting a model deletes everything it owned`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("gone with the model"), Context.system())
        val authorID = createAuthorWithPicture(klerk, id)

        klerk.handle(
            Command(event = DeleteAuthor, model = authorID, params = null),
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Context.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `A command that fails after a property changed leaves the data intact`() = runBlocking {
        val klerk = start()
        val old = klerk.attachedData.prepare(blob("survivor"), Context.system())
        val authorID = createAuthorWithPicture(klerk, old)

        // 'James Clavell' is rejected by the Author validator, so the whole command fails
        val author = klerk.read(Context.system()) { get(authorID) }
        val newPicture = klerk.attachedData.prepare(blob("never attached"), Context.system())
        val result = klerk.handle(
            Command(
                event = UpdateAuthor,
                model = authorID,
                params = author.props.copy(
                    firstName = FirstName("James"),
                    lastName = LastName("Clavell"),
                    picture = newPicture
                )
            ),
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        )
        assertTrue(result is CommandResult.Failure)

        assertEquals("survivor", String(klerk.attachedData.get(old, Context.system()).readAllBytes()))
        assertEquals(old, klerk.read(Context.system()) { get(authorID).props.picture })
        klerk.meta.stop()
    }

    @Test
    fun `Unclaimed data disappears when it expires`() = runBlocking {
        val klerk = start(settings = KlerkSettings(unclaimedAttachedDataLifetime = 1.milliseconds))
        val id = klerk.attachedData.prepare(blob("too slow"), Context.system())
        Thread.sleep(30)

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Context.system()) }

        val result = createAuthorWithPictureExpectingFailure(klerk, id)
        assertEquals(KlerkErrorCode.AttachedDataNotFound, result.problems.single().code)
        klerk.meta.stop()
    }

    @Test
    fun `A context clock in the future does not extend the claim window`() = runBlocking {
        val klerk = start(settings = KlerkSettings(unclaimedAttachedDataLifetime = 1.milliseconds))
        val distantFuture = Context(SystemIdentity, time = Clock.System.now().plus(365.days))
        val id = klerk.attachedData.prepare(blob("no time travel"), distantFuture)
        Thread.sleep(30)

        val result = createAuthorWithPictureExpectingFailure(klerk, id, context = distantFuture)
        assertEquals(KlerkErrorCode.AttachedDataNotFound, result.problems.single().code)
        klerk.meta.stop()
    }

    @Test
    fun `A context clock in the past does not shorten the claim window`() = runBlocking {
        val klerk = start(settings = KlerkSettings(unclaimedAttachedDataLifetime = 10.minutes))
        val distantPast = Context(SystemIdentity, time = Clock.System.now().minus(365.days))
        val id = klerk.attachedData.prepare(blob("still here"), distantPast)

        createAuthorWithPicture(klerk, id, context = distantPast)
        assertEquals("still here", String(klerk.attachedData.get(id, Context.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `get inside a read block throws`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("locked"), Context.system())
        createAuthorWithPicture(klerk, id)

        val fromNonSuspendingRead = assertFailsWith<IllegalStateException> {
            klerk.read(Context.system()) {
                runBlocking { klerk.attachedData.get(id, Context.system()) }
            }
        }
        assertTrue(fromNonSuspendingRead.message!!.contains("must not be called inside a read block"))

        assertFailsWith<IllegalStateException> {
            klerk.readSuspend(Context.system()) { klerk.attachedData.get(id, Context.system()) }
        }
        klerk.meta.stop()
    }

    @Test
    fun `Concurrent prepare calls never collide`() = runBlocking {
        val klerk = start()
        val prepared = (1..100).map { i ->
            async { klerk.attachedData.prepare("value $i", Context.system()) to i }
        }.awaitAll()

        assertEquals(100, prepared.map { it.first }.toSet().size, "Two concurrent prepare calls got the same id")

        // attach them all so that the values can be read back — a collision would have overwritten one of them
        createBookWithChapters(klerk, prepared.map { it.first })
        prepared.forEach { (id, i) ->
            assertEquals("value $i", klerk.attachedData.get(id, Context.system()))
        }
        klerk.meta.stop()
    }

    @Test
    fun `Unclaimed data cannot be read`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("not attached yet"), Context.system())
        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(id, Context.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `The read rule can reach the owning model`() = runBlocking {
        val klerk = start()
        val readable = klerk.attachedData.prepare(blob("public"), Context.system())
        val secret = klerk.attachedData.prepare(blob("secret"), Context.system())
        createAuthorWithPicture(klerk, readable, lastName = "Lindgren")
        createAuthorWithPicture(klerk, secret, lastName = "Secretive")

        val context = Context.authenticationIdentity()
        assertEquals("public", String(klerk.attachedData.get(readable, context).readAllBytes()))
        assertFailsWith<AuthorizationException> { klerk.attachedData.get(secret, context) }
        klerk.meta.stop()
    }

    @Test
    fun `A negative read rule denies`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("members only"), Context.system())
        createAuthorWithPicture(klerk, id)

        assertFailsWith<AuthorizationException> { klerk.attachedData.get(id, Context.unauthenticated()) }
        klerk.meta.stop()
    }

    @Test
    fun `Data is private unless something else is asked for`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("members only"), Context.system())
        createAuthorWithPicture(klerk, id)

        assertEquals(AttachedDataVisibility.Private, klerk.attachedData.getMetadata(id, Context.system()).visibility)
        assertFailsWith<AuthorizationException> { klerk.attachedData.get(id, Context.unauthenticated()) }
        klerk.meta.stop()
    }

    @Test
    fun `Public data bypasses both positive and negative read rules`() = runBlocking {
        val klerk = start()
        // "Secretive" makes the positive rule withhold its opinion, and the actor is the one the negative rule denies
        val id = klerk.attachedData.prepare(blob("for everyone"), Context.system(), AttachedDataVisibility.Public)
        createAuthorWithPicture(klerk, id, lastName = "Secretive")

        val unauthenticated = Context.unauthenticated()
        assertEquals("for everyone", String(klerk.attachedData.get(id, unauthenticated).readAllBytes()))
        assertEquals(AttachedDataVisibility.Public, klerk.attachedData.getMetadata(id, unauthenticated).visibility)
        klerk.meta.stop()
    }

    @Test
    fun `A write rule can reject a public upload`() = runBlocking {
        val klerk = start()
        val context = Context.unauthenticated()

        // the rule in TestSetup lets anyone upload, but not anyone publish
        assertNotNull(klerk.attachedData.prepare(blob("mine"), context, AttachedDataVisibility.Private))
        assertFailsWith<AuthorizationException> {
            klerk.attachedData.prepare(blob("everyone's"), context, AttachedDataVisibility.Public)
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata of private data is authorized like the value`() = runBlocking {
        val klerk = start()
        val readable = klerk.attachedData.prepare(blob("readable"), Context.system())
        val secret = klerk.attachedData.prepare(blob("secret"), Context.system())
        createAuthorWithPicture(klerk, readable, lastName = "Lindgren")
        createAuthorWithPicture(klerk, secret, lastName = "Secretive")

        val context = Context.authenticationIdentity()
        assertEquals(8L, klerk.attachedData.getMetadata(readable, context).size)
        assertFailsWith<AuthorizationException> { klerk.attachedData.getMetadata(secret, context) }
        klerk.meta.stop()
    }

    @Test
    fun `The hash and the size describe the content`() = runBlocking {
        val klerk = start()
        // echo -n hello | sha256sum
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        val blobID = klerk.attachedData.prepare(blob("hello"), Context.system())
        val stringID = klerk.attachedData.prepare("hello", Context.system())
        createAuthorWithPicture(klerk, blobID)
        createBookWithChapters(klerk, listOf(stringID))

        val blobMeta = klerk.attachedData.getMetadata(blobID, Context.system())
        assertEquals(expected, blobMeta.hash)
        assertEquals(5L, blobMeta.size)
        val stringMeta = klerk.attachedData.getMetadata(stringID, Context.system())
        assertEquals(expected, stringMeta.hash)
        assertEquals(5L, stringMeta.size)
        klerk.meta.stop()
    }

    @Test
    fun `The hash and the size cover a blob larger than one buffer`() = runBlocking {
        val klerk = start()
        val content = "abcdefghij".repeat(10_000)   // 100 kB, i.e. many reads
        val id = klerk.attachedData.prepare(content.toByteArray().inputStream(), Context.system())
        createAuthorWithPicture(klerk, id)

        val meta = klerk.attachedData.getMetadata(id, Context.system())
        assertEquals(content.length.toLong(), meta.size)
        assertEquals(klerk.attachedData.getMetadata(prepareString(klerk, content), Context.system()).hash, meta.hash)
        klerk.meta.stop()
    }

    @Test
    fun `The metadata is not available before the data is claimed`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("not attached yet"), Context.system())
        assertFailsWith<NoSuchElementException> { klerk.attachedData.getMetadata(id, Context.system()) }
        assertFailsWith<NoSuchElementException> {
            klerk.attachedData.getMetadata(
                AttachedBlobID(4711),
                Context.system()
            )
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata goes away with the data`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("temporary"), Context.system())
        val authorID = createAuthorWithPicture(klerk, id)
        assertNotNull(klerk.attachedData.getMetadata(id, Context.system()))

        setPicture(klerk, authorID, null).orThrow()
        assertFailsWith<NoSuchElementException> { klerk.attachedData.getMetadata(id, Context.system()) }
        klerk.meta.stop()
    }

    @Test
    fun `Custom metadata is stored as given`() = runBlocking {
        val klerk = start()
        val custom = mapOf("contentType" to "image/webp", "width" to "1200")
        val id = klerk.attachedData.prepare(blob("an image"), Context.system(), metadata = custom)
        createAuthorWithPicture(klerk, id)

        assertEquals(custom, klerk.attachedData.getMetadata(id, Context.system()).custom)
        klerk.meta.stop()
    }

    @Test
    fun `Custom metadata that would bloat the cache is rejected`() = runBlocking {
        val klerk = start()
        assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare(blob("x"), Context.system(), metadata = mapOf("big" to "y".repeat(1000)))
        }
        klerk.meta.stop()
    }

    @Test
    fun `The metadata survives a restart`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val klerk = start(storage)
        val custom = mapOf("contentType" to "image/png")
        val id = klerk.attachedData.prepare(blob("kept"), Context.system(), AttachedDataVisibility.Public, custom)
        createAuthorWithPicture(klerk, id)
        val before = klerk.attachedData.getMetadata(id, Context.system())
        klerk.meta.stop()

        val restarted = start(storage)
        assertEquals(before, restarted.attachedData.getMetadata(id, Context.system()))
        assertEquals(AttachedDataVisibility.Public, before.visibility)
        assertEquals(custom, before.custom)
        restarted.meta.stop()
    }

    @Test
    fun `getMetadata inside a read block throws`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(blob("locked"), Context.system())
        createAuthorWithPicture(klerk, id)

        assertFailsWith<IllegalStateException> {
            klerk.readSuspend(Context.system()) { klerk.attachedData.getMetadata(id, Context.system()) }
        }
        klerk.meta.stop()
    }

    /** Attaches a string to a book so that its metadata becomes readable. */
    private suspend fun prepareString(klerk: Klerk<Context, MyCollections>, content: String): AttachedStringID {
        val id = klerk.attachedData.prepare(content, Context.system())
        createBookWithChapters(klerk, listOf(id))
        return id
    }

    @Test
    fun `A list of ids is claimed and dropped like a single one`() = runBlocking {
        val klerk = start()
        val first = klerk.attachedData.prepare("chapter one", Context.system())
        val second = klerk.attachedData.prepare("chapter two", Context.system())
        val bookID = createBookWithChapters(klerk, listOf(first, second))

        assertEquals("chapter one", klerk.attachedData.get(first, Context.system()))

        val book = klerk.read(Context.system()) { get(bookID) }
        klerk.handle(
            Command(event = UpdateBook, model = bookID, params = book.props.copy(chapters = listOf(second))),
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()

        assertFailsWith<NoSuchElementException> { klerk.attachedData.get(first, Context.system()) }
        assertEquals("chapter two", klerk.attachedData.get(second, Context.system()))
        klerk.meta.stop()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `The ids are serialized as plain numbers`() = runBlocking {
        // see the note on ModelID in Types.kt: a value class can leak its field name into the serialized form
        val klerk = start()
        val picture = klerk.attachedData.prepare(blob("x"), Context.system())
        val chapter = klerk.attachedData.prepare("y", Context.system())
        val authorID = createAuthorWithPicture(klerk, picture)
        val bookID = createBookWithChapters(klerk, listOf(chapter))

        val authorJson = klerk.config.toJson(klerk.read(Context.system()) { get(authorID) }.props)
        assertTrue(authorJson.contains("\"picture\":${picture.id}"), "Unexpected JSON: $authorJson")
        val bookJson = klerk.config.toJson(klerk.read(Context.system()) { get(bookID) }.props)
        assertTrue(bookJson.contains("\"chapters\":[${chapter.id}]"), "Unexpected JSON: $bookJson")
        klerk.meta.stop()
    }

    @Test
    fun `Attached data survives a restart`() = runBlocking {
        val storage = SQLiteInMemory.create()
        val klerk = start(storage)
        val claimed = klerk.attachedData.prepare(blob("kept"), Context.system())
        val unclaimed = klerk.attachedData.prepare(blob("dropped"), Context.system())
        val authorID = createAuthorWithPicture(klerk, claimed)
        val chapter = klerk.attachedData.prepare("a chapter", Context.system())
        val bookID = createBookWithChapters(klerk, listOf(chapter))
        klerk.meta.stop()

        val restarted = start(storage)
        // this also checks that the value classes survive Gson and the database — both the nullable (boxed) property
        // and the one inside a List
        assertEquals(claimed, restarted.read(Context.system()) { get(authorID).props.picture })
        assertEquals(listOf(chapter), restarted.read(Context.system()) { get(bookID).props.chapters })
        assertEquals("a chapter", restarted.attachedData.get(chapter, Context.system()))
        assertEquals("kept", String(restarted.attachedData.get(claimed, Context.system()).readAllBytes()))
        // the unclaimed one is still within its window, so it is reserved rather than reaped
        assertFailsWith<NoSuchElementException> { restarted.attachedData.get(unclaimed, Context.system()) }
        restarted.meta.stop()
    }

    private suspend fun createBook(
        klerk: Klerk<Context, MyCollections>,
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
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        )
        return requireNotNull(result.orThrow().primaryModel)
    }

    private suspend fun createBookWithNotes(klerk: Klerk<Context, MyCollections>, notes: AttachedStringID) =
        createBook(klerk) { it.copy(notes = notes) }

    private suspend fun createBookWithCoverAndThumbnail(
        klerk: Klerk<Context, MyCollections>,
        cover: AttachedBlobID,
        thumbnail: AttachedBlobID
    ) = createBook(klerk) { it.copy(cover = cover, thumbnail = thumbnail) }

    private suspend fun createBookWithChapters(
        klerk: Klerk<Context, MyCollections>,
        chapters: List<AttachedStringID>
    ) = createBook(klerk) { it.copy(chapters = chapters) }

    private suspend fun createAuthorWithPictureExpectingFailure(
        klerk: Klerk<Context, MyCollections>,
        picture: AttachedBlobID,
        context: Context = Context.system(),
    ): CommandResult.Failure<Author, Context, MyCollections> {
        val result = klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName("Selma"),
                    lastName = LastName("Lagerlöf"),
                    phone = PhoneNumber("+4611111"),
                    secretToken = SecretPasscode(2),
                    picture = picture,
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        return result
    }

}

package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.AttachedStringContainer
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * What a string property declares is enforced the same way a blob property's is: in the command pipeline, against
 * what Klerk found the bytes to be. This is [AttachedBlobContainerTest]'s string-kind counterpart — see that class
 * for the blob-only checks (preAttachSteps, jobs) that have no equivalent here.
 */
class AttachedStringContainerTest {

    private suspend fun start(storage: Persistence = RamStorage()): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = Klerk.create(createConfig(collections, storage, blobStore = AttachedBlobStore.Database))
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private suspend fun write(
        klerk: Klerk<Ctx, Views>,
        body: AttachedStringID,
        context: Ctx = Ctx.system(),
    ): CommandResult<Note, Ctx, Views> = klerk.handle(
        Command(
            event = CreateNote,
            model = null,
            params = CreateNoteParams(NoteTitle("Reminder"), NoteBody(body)),
        ),
        context,
        ProcessingOptions(CommandToken.simple()),
    )

    @Test
    fun `a bare AttachedStringID is refused, with the container to write instead`() {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val config = ConfigBuilder<Ctx, Views>(collections).build {
            managedModels {
                model(Scribble::class, scribbleStateMachine(), collections.scribbles)
            }
            apply(generousAuthRules())
            persistence(RamStorage())
            systemContextProvider { systemIdentity -> Ctx(systemIdentity) }
        }

        val e = assertFailsWith<IllegalConfigurationException> { Klerk.create(config) }
        assertEquals(KlerkErrorCode.StringMustBeDeclaredInAContainer, e.code)
        assertTrue(e.message!!.contains("Scribble.text"), e.message!!)
        assertTrue(e.message!!.contains("AttachedStringContainer"), "the message should say what to write instead")
    }

    @Test
    fun `an accepted string is attached`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare("buy milk", NoteBody::class, Ctx.system())

        val note = requireNotNull(write(klerk, id).orThrow().primaryModel)

        assertEquals(id, klerk.read(Ctx.system()) { get(note) }.props.body.id)
        klerk.meta.stop()
    }

    @Test
    fun `a string of the wrong type is refused`() = runBlocking {
        val klerk = start()
        // an SVG string, which Klerk recognises as image/svg+xml, not text/plain
        val id = klerk.attachedData.prepare("<svg></svg>", NoteBody::class, Ctx.system())

        val result = write(klerk, id)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        val problem = result.problems.single()
        assertEquals(KlerkErrorCode.AttachedDataNotAcceptable, problem.code)
        assertTrue(problem.endUserTranslatedMessage.contains("image/svg+xml"), problem.endUserTranslatedMessage)
        klerk.meta.stop()
    }

    @Test
    fun `a string that is too large is refused`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare("this note is much too long to keep", NoteBody::class, Ctx.system())

        val result = write(klerk, id)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        assertEquals(KlerkErrorCode.AttachedDataNotAcceptable, result.problems.single().code)
        klerk.meta.stop()
    }

    @Test
    fun `the declaration cannot be built from an id alone`() = runBlocking {
        val klerk = start()

        val e = assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare("buy milk", NeedsMoreThanAnIdString::class, Ctx.system())
        }

        assertTrue(e.message!!.contains("exactly one constructor"), e.message!!)
        klerk.meta.stop()
    }
}

/** A container Klerk cannot build on its own, since it wants something the id does not tell it. */
class NeedsMoreThanAnIdString(id: AttachedStringID, val extra: String) : AttachedStringContainer(id)

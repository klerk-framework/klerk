package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedDataVisibility
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BlobRejectedException
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.CreateInventory
import dev.klerkframework.klerk.CreateInventoryParams
import dev.klerkframework.klerk.CreatePainting
import dev.klerkframework.klerk.CreatePaintingParams
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.Doodle
import dev.klerkframework.klerk.IllegalConfigurationException
import dev.klerkframework.klerk.Inventory
import dev.klerkframework.klerk.InventoryCsv
import dev.klerkframework.klerk.InventoryName
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.Painting
import dev.klerkframework.klerk.PaintingImage
import dev.klerkframework.klerk.PaintingTitle
import dev.klerkframework.klerk.Sketch
import dev.klerkframework.klerk.SpecificationBuilder
import dev.klerkframework.klerk.StateProblem
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.createKlerk
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.datatypes.BlobPreAttachStep
import dev.klerkframework.klerk.datatypes.BlobPreAttachStepArgs
import dev.klerkframework.klerk.datatypes.BlobPreAttachStepResult
import dev.klerkframework.klerk.datatypes.noPreAttachProcessing
import dev.klerkframework.klerk.doodleStateMachine
import dev.klerkframework.klerk.generousAuthRules
import dev.klerkframework.klerk.job.JobProgress
import dev.klerkframework.klerk.job.JobStatus
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.sketchStateMachine
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.FileBlobStore
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.testSettings
import dev.klerkframework.klerk.testing.runUntilIdle
import dev.klerkframework.klerk.testing.step
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * What a blob property declares is enforced where it counts: in the command pipeline, against what Klerk found the
 * bytes to be. A caller that never went through a form — a job, an API, a test — is held to the same declaration.
 */
class AttachedBlobContainerTest {

    private suspend fun start(storage: Persistence = RamStorage()): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, storage, blobStore = AttachedBlobStore.Database)
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private fun png(size: Int = 24): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(size - 8)

    private suspend fun hang(
        klerk: Klerk<Ctx, Views>,
        image: AttachedBlobID,
        context: Ctx = Ctx.system(),
    ): CommandResult<Painting> = klerk.handle(
        Command(
            CreatePainting,
            CreatePaintingParams(PaintingTitle("Sunflowers"), PaintingImage(image)),
        ),
        context,
    )

    @Test
    fun `a bare AttachedBlobID is refused, with the container to write instead`() {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val specification = SpecificationBuilder<Ctx, Views>(collections).build {
            managedModels {
                model(Sketch::class, sketchStateMachine(), collections.sketches)
            }
            apply(generousAuthRules())
            systemContextProvider { Ctx(SystemIdentity) }
        }

        val e = assertFailsWith<IllegalConfigurationException> { Klerk.create(specification, testSettings()) }
        assertEquals(KlerkErrorCode.BlobMustBeDeclaredInAContainer, e.code)
        assertTrue(e.message!!.contains("Sketch.drawing"), e.message!!)
        assertTrue(e.message!!.contains("AttachedBlobContainer"), "the message should say what to write instead")
    }

    @Test
    fun `an accepted file is attached`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())

        val painting = requireNotNull(hang(klerk, id).getOrThrow().primaryModel)

        assertEquals(id, klerk.read(Ctx.system()) { get(painting) }.props.image.id)
        klerk.meta.stop()
    }

    @Test
    fun `a file of the wrong type is refused, whatever it was called`() = runBlocking {
        val klerk = start()
        // an HTML file, uploaded with every claim in the world that it is a PNG
        val id = klerk.attachedData.prepare(
            "<html><script>alert(1)</script></html>".byteInputStream(),
            PaintingImage::class,
            Ctx.system(),
            custom = mapOf("filename" to "innocent.png", "clientContentType" to "image/png"),
        )

        val result = hang(klerk, id)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        val problem = result.problems.single()
        assertEquals(KlerkErrorCode.AttachedDataNotAcceptable, problem.code)
        assertTrue(problem.endUserTranslatedMessage.contains("text/html"), problem.endUserTranslatedMessage)
        klerk.meta.stop()
    }

    @Test
    fun `a file that is too large is refused`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(png(2000).inputStream(), PaintingImage::class, Ctx.system())

        val result = hang(klerk, id)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        assertEquals(KlerkErrorCode.AttachedDataNotAcceptable, result.problems.single().code)
        klerk.meta.stop()
    }

    @Test
    fun `a file whose type cannot be recognised is refused when the property wants images`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(
            byteArrayOf(0x07, 0x03, 0x42, 0x11).inputStream(),
            PaintingImage::class,
            Ctx.system(),
        )

        val result = hang(klerk, id)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        assertTrue(
            result.problems.single().endUserTranslatedMessage.contains("could not be recognised"),
            result.problems.single().endUserTranslatedMessage,
        )
        klerk.meta.stop()
    }

    private suspend fun count(klerk: Klerk<Ctx, Views>, rows: AttachedBlobID): CommandResult<Inventory> = klerk.handle(
        Command(
            CreateInventory,
            CreateInventoryParams(InventoryName("Warehouse"), InventoryCsv(rows)),
        ),
        Ctx.system(),
    )

    @Test
    fun `a value that has been through its steps can be attached`() = runBlocking {
        val klerk = start()
        val good = klerk.attachedData.prepare(
            "name,quantity\nrose,3\n".byteInputStream(),
            InventoryCsv::class,
            Ctx.system(),
        )

        klerk.attachedData.awaitProcessing(good)
        val inventory = requireNotNull(count(klerk, good).getOrThrow().primaryModel)

        assertEquals(good, klerk.read(Ctx.system()) { get(inventory) }.props.rows.id)
        klerk.meta.stop()
    }

    @Test
    fun `a value whose steps have not run yet cannot be attached`() = runBlocking {
        val klerk = start()
        val good = klerk.attachedData.prepare(
            "name,quantity\nrose,3\n".byteInputStream(),
            InventoryCsv::class,
            Ctx.system(),
        )

        // the job that runs the steps has not been given a chance to (execution is Manual here), so the command
        // refuses the value and says which step it is still waiting for
        val result = count(klerk, good)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        val problem = result.problems.single()
        assertEquals(KlerkErrorCode.AttachedDataNotProcessed, problem.code)
        val internal = (problem as StateProblem).internalDescription
        assertTrue(internal.contains("checkTheHeader"), internal)
        klerk.meta.stop()
    }

    @Test
    fun `a container that declares no steps needs no job`() = runBlocking {
        val klerk = start()
        val before = klerk.jobs.all(Ctx.system()).size

        klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())

        assertEquals(before, klerk.jobs.all(Ctx.system()).size, "nothing has to run, so nothing was scheduled")
        klerk.meta.stop()
    }

    @Test
    fun `a step can refuse a file that the metadata checks would have allowed`() = runBlocking {
        val klerk = start()
        // a perfectly good text file, of the accepted type and a reasonable size — but not this CSV
        val wrong = klerk.attachedData.prepare(
            "quantity,name\n3,rose\n".byteInputStream(),
            InventoryCsv::class,
            Ctx.system(),
        )

        val refusal = assertFailsWith<BlobRejectedException> { klerk.attachedData.awaitProcessing(wrong) }

        assertTrue(refusal.reason.contains("quantity,name"), refusal.reason)
        // the value is gone, and the job that refused it says why
        assertFailsWith<NoSuchElementException> { klerk.attachedData.getMetadata(wrong, Ctx.system()) }
        val job = klerk.jobs.all(Ctx.system()).first { it.name.value == PROCESS_ATTACHED_DATA }
        assertEquals(JobStatus.DeadLettered, job.status)
        assertTrue(job.reason!!.contains("quantity,name"), job.reason!!)
        klerk.meta.stop()
    }

    @Test
    fun `a step can rewrite the bytes, and what is stored is what it produced`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(
            "name,quantity\r\nrose,3\r\n".byteInputStream(),
            InventoryCsv::class,
            Ctx.system(),
        )

        klerk.attachedData.awaitProcessing(id)
        val inventory = requireNotNull(count(klerk, id).getOrThrow().primaryModel)

        assertEquals("name,quantity\nrose,3\n", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        // the digest describes what is actually stored, not what arrived
        val meta = klerk.attachedData.getMetadata(id, Ctx.system())
        assertEquals(21, meta.size)
        assertEquals(listOf("checkTheHeader", "normaliseLineEndings"), meta.completedSteps)
        assertNotNull(inventory)
        klerk.meta.stop()
    }

    @Test
    fun `each step is a step of the job, and none of them runs twice`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(
            "name,quantity\r\nrose,3\r\n".byteInputStream(),
            InventoryCsv::class,
            Ctx.system(),
        )

        // one step of the job per declared step, with a checkpoint in between rather than one long step
        assertTrue(klerk.jobs.step(), "the first declared step")
        val midway = klerk.jobs.all(Ctx.system()).single { it.name.value == PROCESS_ATTACHED_DATA }
        assertEquals(JobProgress(1, 2), midway.progress)
        klerk.attachedData.awaitProcessing(id)

        count(klerk, id).getOrThrow()
        val meta = klerk.attachedData.getMetadata(id, Ctx.system())
        assertEquals(listOf("checkTheHeader", "normaliseLineEndings"), meta.completedSteps, "each step ran once")
        assertEquals("name,quantity\nrose,3\n", String(klerk.attachedData.get(id, Ctx.system()).readAllBytes()))
        klerk.meta.stop()
    }

    @Test
    fun `a declaration that no property uses is refused`() = runBlocking {
        val klerk = start()
        val e = assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare("anything".byteInputStream(), UnusedDocument::class, Ctx.system())
        }
        assertTrue(e.message!!.contains("UnusedDocument"), e.message!!)
        klerk.meta.stop()
    }

    @Test
    fun `a step that fails is retried, and the steps before it are not run again`() = runBlocking {
        counted = 0
        flaky = 1
        val clock = MutableClock(Clock.System.now())
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, RamStorage(), clock = clock)
        klerk.meta.start(installShutdownHook = false)
        val id = klerk.attachedData.prepare("anything".byteInputStream(), FlakyDocument::class, Ctx.system())

        klerk.jobs.runUntilIdle() // the first step passes, the second throws and goes into backoff
        val job = klerk.jobs.all(Ctx.system()).single { it.name.value == PROCESS_ATTACHED_DATA }
        assertEquals(JobStatus.Backoff, job.status)
        clock.advance(1.minutes)
        klerk.jobs.runUntilIdle()

        assertEquals(1, counted, "the step before the flaky one ran once, not once per attempt")
        assertEquals(
            JobStatus.Succeeded,
            klerk.jobs.get(job.id, Ctx.system()).status,
            "the retry got through the step that had failed",
        )
        assertNotNull(id)
        klerk.meta.stop()
    }

    @Test
    fun `a half-processed value resumes after a restart`() = runBlocking {
        counted = 0
        flaky = 0
        val storage = RamStorage()
        val klerk = start(storage)
        klerk.attachedData.prepare("anything".byteInputStream(), FlakyDocument::class, Ctx.system())
        assertTrue(klerk.jobs.step(), "one step, then the node goes down")
        assertEquals(1, counted)
        klerk.meta.stop()

        val restarted = start(storage)
        // the job comes back from its cursor, and rebuilds the declaration from the class name it carries
        restarted.jobs.runUntilIdle()

        assertEquals(1, counted, "the step that had already run is not run again")
        val job = restarted.jobs.all(Ctx.system()).single { it.name.value == PROCESS_ATTACHED_DATA }
        assertEquals(JobStatus.Succeeded, job.status)
        restarted.meta.stop()
    }

    @Test
    fun `a declaration that cannot be built is refused before any byte is stored`() = runBlocking {
        val klerk = start()

        val e = assertFailsWith<IllegalArgumentException> {
            klerk.attachedData.prepare(png().inputStream(), NeedsMoreThanAnId::class, Ctx.system())
        }

        assertTrue(e.message!!.contains("AttachedBlobID"), e.message!!)
        klerk.meta.stop()
    }

    /**
     * Counts how often the value is fetched from storage, which is what "a step costs nothing unless declared" means.
     */
    private class CountingBlobStore(root: java.nio.file.Path) : AttachedBlobStore.External {
        private val delegate = FileBlobStore(root)
        var fetches: Int = 0

        override fun put(id: Int, value: java.io.InputStream) = delegate.put(id, value)
        override fun get(id: Int): java.io.InputStream? = delegate.get(id).also { fetches++ }
        override fun delete(id: Int) = delegate.delete(id)
        override fun listIds(): Set<Int>? = delegate.listIds()
    }

    @Test
    fun `attaching never reads the bytes`() = runBlocking {
        val store = CountingBlobStore(Files.createTempDirectory("klerk-steps"))
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = createKlerk(collections, RamStorage(), blobStore = store)
        klerk.meta.start(installShutdownHook = false)

        val image = klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())
        val before = store.fetches
        hang(klerk, image).getOrThrow()

        // PaintingImage declares no steps, so nothing has to be read — and even a container that declares them is
        // checked against what was recorded when they ran, never by reading the value inside command processing.
        assertEquals(before, store.fetches, "attaching must not have read the value")
        klerk.meta.stop()
    }

    @Test
    fun `the property decides the visibility, not whoever uploaded the bytes`() = runBlocking {
        val klerk = start()
        // prepared without saying anything about visibility, as an upload always is. Nothing can be read about it
        // yet — unclaimed data has no owner, so there is nothing for a rule to decide on.
        val id = klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())

        hang(klerk, id).getOrThrow()

        // PaintingImage declares Public, so attaching it published it
        assertEquals(AttachedDataVisibility.Public, klerk.attachedData.getMetadata(id, Ctx.system()).visibility)
        klerk.meta.stop()
    }

    @Test
    fun `a published blob is readable by anyone, which is what publishing means`() = runBlocking {
        val klerk = start()
        val id = klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())
        hang(klerk, id).getOrThrow()

        // the read rules in this specification deny unauthenticated actors, but they are not consulted for public data
        assertEquals(24, klerk.attachedData.get(id, Ctx.unauthenticated()).readAllBytes().size)
        klerk.meta.stop()
    }

    @Test
    fun `the declaration survives a round-trip through storage`() = runBlocking {
        val storage = RamStorage()
        val klerk = start(storage)
        val id = klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())
        val painting = requireNotNull(hang(klerk, id).getOrThrow().primaryModel)
        klerk.meta.stop()

        val restarted = start(storage)
        val image = restarted.read(Ctx.system()) { get(painting) }.props.image
        assertEquals(id, image.id)
        assertEquals(setOf("image/png", "image/jpeg"), image.accept, "the declaration is code, and comes back with it")
        assertEquals(
            AttachedDataVisibility.Public,
            restarted.attachedData.getMetadata(id, Ctx.system()).visibility,
        )
        assertNull(
            restarted.jobs.all(Ctx.system()).firstOrNull { it.name.value == PROCESS_ATTACHED_DATA },
            "a container that only declares noPreAttachProcessing never had a job",
        )
        restarted.meta.stop()
    }

    @Test
    fun `a container that declares no step at all is refused by the specification`() {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val specification = SpecificationBuilder<Ctx, Views>(collections).build {
            managedModels {
                model(Doodle::class, doodleStateMachine(), collections.doodles)
            }
            apply(generousAuthRules())
            systemContextProvider { Ctx(SystemIdentity) }
        }

        val e = assertFailsWith<IllegalConfigurationException> { Klerk.create(specification, testSettings()) }
        assertEquals(KlerkErrorCode.MissingPreAttachStep, e.code)
        assertTrue(e.message!!.contains("Doodle.drawing"), e.message!!)
        assertTrue(e.message!!.contains("noPreAttachProcessing"), "the message should say what to write instead")
    }

    @Test
    fun `noPreAttachProcessing means no job, and the value can be attached at once`() = runBlocking {
        val klerk = start()

        val id = klerk.attachedData.prepare(png().inputStream(), PaintingImage::class, Ctx.system())

        // nothing to wait for, and nothing to run: the value is ready as soon as it is written
        klerk.attachedData.awaitProcessing(id)
        assertNull(klerk.jobs.all(Ctx.system()).firstOrNull { it.name.value == PROCESS_ATTACHED_DATA })
        hang(klerk, id).getOrThrow()
        assertTrue(klerk.attachedData.getMetadata(id, Ctx.system()).completedSteps.isEmpty())
        klerk.meta.stop()
    }

    @Test
    fun `noPreAttachProcessing cannot be combined with a real step`() {
        val e = assertFailsWith<IllegalArgumentException> { Confused(AttachedBlobID(1)).stepNames }
        assertTrue(e.message!!.contains("noPreAttachProcessing"), e.message!!)
    }

    @Test
    fun `a step declared twice runs twice`() = runBlocking {
        counted = 0
        val klerk = start()
        val id = klerk.attachedData.prepare("anything".byteInputStream(), CountedTwice::class, Ctx.system())

        klerk.attachedData.awaitProcessing(id)

        assertEquals(2, counted, "the second countIt is a step of its own, not one that has already run")
        klerk.meta.stop()
    }

    @Test
    fun `steps of another declaration do not count, even with the same names`() = runBlocking {
        val klerk = start()
        // InventoryCsv's checkTheHeader would reject this, but LenientCsv's lets it through
        val id = klerk.attachedData.prepare(
            "wrong,header\nrose,3\n".byteInputStream(),
            LenientCsv::class,
            Ctx.system(),
        )
        klerk.attachedData.awaitProcessing(id)

        val result = count(klerk, id)

        assertTrue(result is CommandResult.Failure, "Expected the command to fail but it was $result")
        val problem = result.problems.single()
        assertEquals(KlerkErrorCode.AttachedDataNotProcessed, problem.code)
        val internal = (problem as StateProblem).internalDescription
        assertTrue(internal.contains("LenientCsv"), internal)
        klerk.meta.stop()
    }
}

/** Declares the same step twice, so it runs twice. */
class CountedTwice(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val acceptUnrecognised: Boolean = true
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::countIt, ::countIt)
}

/** Steps named like InventoryCsv's, but that let anything through. */
class LenientCsv(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept: Set<String> = setOf("text/plain")
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::checkTheHeader, ::normaliseLineEndings)
}

private suspend fun checkTheHeader(args: BlobPreAttachStepArgs): BlobPreAttachStepResult = BlobPreAttachStepResult.Pass

private suspend fun normaliseLineEndings(args: BlobPreAttachStepArgs): BlobPreAttachStepResult =
    BlobPreAttachStepResult.Pass

/** Says both that there is something to do and that there is not. */
class Confused(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::noPreAttachProcessing, ::countIt)
}

/** How many times [countIt] has run, and how many times [failOnce] still has to fail. */
private var counted = 0
private var flaky = 0

/** A container Klerk cannot build on its own, since it wants something the id does not tell it. */
class NeedsMoreThanAnId(id: AttachedBlobID, val extra: String) : AttachedBlobContainer(id) {
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::noPreAttachProcessing)
}

/** A container whose second step fails the first time it is asked, standing in for a scanner that is briefly down. */
class FlakyDocument(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val acceptUnrecognised: Boolean = true
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::countIt, ::failOnce)
}

/** A container with steps that no model property or event parameter uses. */
class UnusedDocument(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val acceptUnrecognised: Boolean = true
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::countIt)
}

suspend fun countIt(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
    counted++
    return BlobPreAttachStepResult.Pass
}

suspend fun failOnce(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
    if (flaky > 0) {
        flaky--
        throw IllegalStateException("the scanner is not answering")
    }
    return BlobPreAttachStepResult.Pass
}

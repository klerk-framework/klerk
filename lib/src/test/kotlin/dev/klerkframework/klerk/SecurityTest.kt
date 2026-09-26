package dev.klerkframework.klerk

import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.NegativeAuthorization.Pass
import dev.klerkframework.klerk.PositiveAuthorization.Allow
import dev.klerkframework.klerk.PositiveAuthorization.NoOpinion
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.job.JobAgent
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobOperation
import dev.klerkframework.klerk.job.JobResult
import dev.klerkframework.klerk.job.JobStatus
import dev.klerkframework.klerk.job.JobStepArgs
import dev.klerkframework.klerk.job.JobType
import dev.klerkframework.klerk.testing.runUntilIdle
import dev.klerkframework.klerk.view.asSequence
import dev.klerkframework.klerk.view.asSequenceOrThrow
import dev.klerkframework.klerk.view.contains
import dev.klerkframework.klerk.view.count
import dev.klerkframework.klerk.view.ids
import dev.klerkframework.klerk.view.isEmpty
import dev.klerkframework.klerk.view.isNotEmpty
import dev.klerkframework.klerk.view.query
import dev.klerkframework.klerk.view.queryOrThrow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The security guarantees in docs/security.md and docs/authorization.md, one category at a time: deny by default, deny
 * beats allow, the system bypasses every rule, and nothing hands out data the rules have not approved.
 */
class SecurityTest {

    // ---------------------------------------------------------------- deny by default

    @Test
    fun `a category without rules denies everything in it`() = runBlocking<Unit> {
        val klerk = start { }
        val rowling = createAuthorJKRowling(klerk)
        val user = Ctx.authenticationIdentity()

        assertCode(KlerkErrorCode.ReadPositiveAuthorizationMissing) { klerk.read(user) { get(rowling) } }
        assertNull(klerk.read(user) { getOrNull(rowling) })
        assertTrue(klerk.read(user) { views.authors.all.asSequence().toList() }.isEmpty())
        assertTrue(klerk.read(user) { views.authors.all.query().items }.isEmpty())

        val problem = klerk.handle(Command(AnEventWithoutParameters), user).problems().single()
        assertIs<AuthorizationProblem>(problem)
        assertEquals(KlerkErrorCode.CommandPositiveAuthorizationMissing, problem.code)

        assertCode(KlerkErrorCode.EventLogPositiveAuthorizationMissing) { klerk.read(user) { eventLog() } }
        assertCode(KlerkErrorCode.EventLogPositiveAuthorizationMissing) { klerk.read(user) { eventLogEntry(1) } }
        assertCode(KlerkErrorCode.ActivityLogPositiveAuthorizationMissing) { klerk.activityLog.entries(user) }
        assertCode(KlerkErrorCode.ActivityLogPositiveAuthorizationMissing) { klerk.activityLog.subscribe(user).first() }
        assertCode(KlerkErrorCode.ActivityLogPositiveAuthorizationMissing) {
            klerk.activityLog.subscribeToReads(user).first()
        }

        assertCode(KlerkErrorCode.AttachedDataWritePositiveAuthorizationMissing) {
            klerk.attachedData.prepare(blob(), AuthorPicture::class, user)
        }
        val picture = createAuthorWithPicture(klerk)
        assertCode(KlerkErrorCode.AttachedDataReadPositiveAuthorizationMissing) {
            klerk.attachedData.get(picture, user)
        }
        assertCode(KlerkErrorCode.AttachedDataReadPositiveAuthorizationMissing) {
            klerk.attachedData.getMetadata(picture, user)
        }

        val job = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), user)
        assertCode(KlerkErrorCode.JobReadPositiveAuthorizationMissing) { klerk.jobs.get(job, user) }
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) { klerk.jobs.cancel(job, user) }
        assertTrue(klerk.jobs.all(user).isEmpty())

        // the system is not subject to authorization
        val system = Ctx.system()
        assertEquals("Rowling", klerk.read(system) { get(rowling) }.props.lastName.value)
        assertTrue(klerk.handle(Command(AnEventWithoutParameters), system) is CommandResult.Success)
        assertTrue(klerk.read(system) { eventLog() }.get().isNotEmpty())
        assertTrue(klerk.activityLog.entries(system).isNotEmpty())
        assertEquals("picture", String(klerk.attachedData.get(picture, system).readAllBytes()))
        assertEquals(job, klerk.jobs.get(job, system).id)
    }

    @Test
    fun `a readable model with no readProperties rules has every property masked`() = runBlocking<Unit> {
        val klerk = start { readModels { positive(::everybodyCanReadModels) } }
        val rowling = createAuthorJKRowling(klerk)

        val author = klerk.read(Ctx.authenticationIdentity()) { get(rowling) }
        assertNull(author.props.firstName.valueOrNullIfNotAuthorized)
        assertCode(KlerkErrorCode.UnauthorizedPropertyRead) { author.props.firstName.value }
        assertFalse(author.props.firstName.toString().contains("J.K"))
        assertFalse(author.toString().contains("Rowling"))
    }

    @Test
    fun `an actor that is a model is printed without its properties`() = runBlocking<Unit> {
        val klerk = start { }
        val rowlingId = createAuthorJKRowling(klerk)
        val rowling = klerk.read(Ctx.system()) { get(rowlingId) }
        val actor = ModelIdentity(rowling)

        assertEquals("Author ${rowling.id}", actor.toString())
        val heading = dev.klerkframework.klerk.log.LogReadModel(rowling, Ctx(actor)).heading
        assertFalse(heading.contains("Rowling"), heading)
        assertTrue(heading.contains(actor.toString()), heading)
    }

    @Test
    fun `allowEverythingInsecurely allows every category`() = runBlocking<Unit> {
        val klerk = start { allowEverythingInsecurely() }
        val rowling = createAuthorJKRowling(klerk)
        val user = Ctx.authenticationIdentity()

        assertEquals("Rowling", klerk.read(user) { get(rowling) }.props.lastName.value)
        assertTrue(klerk.handle(Command(AnEventWithoutParameters), user) is CommandResult.Success)
        assertTrue(klerk.read(user) { eventLog() }.get().isNotEmpty())
        assertTrue(klerk.activityLog.entries(user).isNotEmpty())
        val picture = createAuthorWithPicture(klerk, preparedBy = user)
        assertEquals("picture", String(klerk.attachedData.get(picture, user).readAllBytes()))
        val job = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), Ctx.system())
        assertEquals(job, klerk.jobs.get(job, user).id)
        klerk.jobs.cancel(job, user)
    }

    // ---------------------------------------------------------------- negative rules win

    @Test
    fun `a negative rule wins over a positive rule in every category`() = runBlocking<Unit> {
        val klerk = start {
            readModels {
                positive(::everybodyCanReadModels)
                negative(::nobodyCanReadModels)
            }
            commands {
                positive(::everybodyCanDoEverything)
                negative(::nobodyCanDoAnything)
            }
            eventLog {
                positive(::everybodyCanReadTheEventLog)
                negative(::nobodyCanReadTheEventLog)
            }
            readAttachedData {
                positive(::everybodyCanReadAttachedData)
                negative(::nobodyCanReadAttachedData)
            }
            writeAttachedData {
                positive(::everybodyCanWriteAttachedData)
                negative(::nobodyCanWriteAttachedData)
            }
            readJobs {
                positive(::everybodyCanSeeJobs)
                negative(::nobodyCanSeeJobs)
            }
            controlJobs {
                positive(::everybodyCanControlJobs)
                negative(::nobodyCanControlJobs)
            }
            activityLog {
                positive(::everybodyCanReadTheActivityLog)
                negative(::nobodyCanReadTheActivityLog)
            }
        }
        val rowling = createAuthorJKRowling(klerk)
        val user = Ctx.authenticationIdentity()

        assertCode(KlerkErrorCode.ReadNegativeAuthorizationExist) { klerk.read(user) { get(rowling) } }
        assertCode(KlerkErrorCode.ReadNegativeAuthorizationExist) {
            klerk.read(user) { views.authors.all.asSequenceOrThrow().toList() }
        }
        assertCode(KlerkErrorCode.ReadNegativeAuthorizationExist) {
            klerk.read(user) { views.authors.all.queryOrThrow() }
        }

        val problem = klerk.handle(Command(AnEventWithoutParameters), user).problems().single()
        assertEquals(KlerkErrorCode.CommandNegativeAuthorizationExist, problem.code)
        assertEquals(RuleDescription(::nobodyCanDoAnything, RuleType.Authorization), problem.violatedRule)

        assertCode(KlerkErrorCode.EventLogNegativeAuthorizationExist) { klerk.read(user) { eventLog() } }
        assertCode(KlerkErrorCode.ActivityLogNegativeAuthorizationExist) { klerk.activityLog.entries(user) }

        assertCode(KlerkErrorCode.AttachedDataWriteNegativeAuthorizationExist) {
            klerk.attachedData.prepare(blob(), AuthorPicture::class, user)
        }
        val picture = createAuthorWithPicture(klerk)
        assertCode(KlerkErrorCode.AttachedDataReadNegativeAuthorizationExist) { klerk.attachedData.get(picture, user) }

        val job = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), user)
        assertCode(KlerkErrorCode.JobReadNegativeAuthorizationExist) { klerk.jobs.get(job, user) }
        assertCode(KlerkErrorCode.JobControlNegativeAuthorizationExist) { klerk.jobs.cancel(job, user) }
        assertTrue(klerk.jobs.all(user).isEmpty())
    }

    // ---------------------------------------------------------------- models

    @Test
    fun `unreadable models are left out of every read that returns several models`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val rowling = createAuthorJKRowling(klerk)
        val astrid = createAuthorAstrid(klerk)
        val book = createBookHarryPotter1(klerk, astrid)
        val user = Ctx.authenticationIdentity()

        klerk.read(user) {
            assertEquals(setOf(astrid), views.authors.all.asSequence().map { it.id }.toSet())
            assertEquals(setOf(astrid), views.authors.all.query().items.map { it.id }.toSet())
            assertTrue(views.books.all.asSequence().none())
            assertTrue(referencing(Book::class, astrid).isEmpty())
            assertTrue(referencing(Book::author, astrid).isEmpty())
            assertEquals(setOf(book), referencingIds(astrid), "only ids, which are not authorized")
            assertNull(getOrNull(rowling))
            assertCode(KlerkErrorCode.ReadNegativeAuthorizationExist) { get(rowling) }

            val all = views.authors.all
            assertEquals(1, all.count())
            assertEquals(listOf(astrid), all.ids().toList())
            assertTrue(astrid in all)
            assertFalse(rowling in all)
            assertTrue(views.books.all.isEmpty())
            assertFalse(views.books.all.isNotEmpty())
        }
        klerk.read(Ctx.system()) {
            assertEquals(2, views.authors.all.count())
            assertTrue(rowling in views.authors.all)
            assertTrue(views.books.all.isNotEmpty())
        }
    }

    @Test
    fun `a view filter never sees a model or a property the actor may not read`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val rowling = createAuthorJKRowling(klerk)
        val astrid = createAuthorAstrid(klerk)
        val user = Ctx.authenticationIdentity()

        val seenByQuery = mutableListOf<Model<Author>>()
        klerk.read(user) { views.authors.all.query { seenByQuery.add(it) } }
        assertEquals(listOf(astrid), seenByQuery.map { it.id })
        assertNull(seenByQuery.single().props.address.street.valueOrNullIfNotAuthorized)

        // Rowling cannot be read at all, so queryOrThrow throws before its filter sees her.
        val seenByQueryOrThrow = mutableListOf<Model<Author>>()
        assertCode(KlerkErrorCode.ReadNegativeAuthorizationExist) {
            klerk.read(user) { views.authors.all.queryOrThrow { seenByQueryOrThrow.add(it) } }
        }
        assertFalse(seenByQueryOrThrow.any { it.id == rowling })
        assertTrue(seenByQueryOrThrow.all { it.props.address.street.valueOrNullIfNotAuthorized == null })
    }

    @Test
    fun `a command result only holds the models the actor may read`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val user = Ctx.authenticationIdentity()

        val readable = klerk.handle(Command(CreateAuthor, createAstridParameters), user).getOrThrow()
        val astrid = assertNotNull(readable.authorizedPrimaryModel)
        assertEquals("Astrid", astrid.props.firstName.value)
        assertNull(astrid.props.address.street.valueOrNullIfNotAuthorized)

        val rowling = createAuthorJKRowling(klerk)
        val result = klerk.handle(Command(ImproveAuthor, rowling), user).getOrThrow()
        assertEquals(rowling, result.primaryModel)
        assertNull(result.authorizedPrimaryModel)
        assertTrue(result.authorizedModels.isEmpty())
    }

    @Test
    fun `possible events only include what the actor is authorized to do`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val astrid = createAuthorAstrid(klerk)

        val events = klerk.read(Ctx.authenticationIdentity()) { possibleEvents(astrid) }
        assertTrue(events.isNotEmpty())
        assertFalse(DeleteAuthor in events, "the actor may not delete authors")
        assertTrue(ImproveAuthor in events)

        val voidEvents = klerk.read(Ctx.authenticationIdentity()) { possibleVoidEvents(Author::class) }
        assertFalse(AnEventWithoutParameters in voidEvents, "the actor may not create authors this way")
        assertTrue(CreateAuthor in voidEvents)

        assertTrue(DeleteAuthor in klerk.read(Ctx.system()) { possibleEvents(astrid) })
    }

    @Test
    fun `possible events of a model the actor may not read are not disclosed`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val rowling = createAuthorJKRowling(klerk)

        assertCode(KlerkErrorCode.ReadNegativeAuthorizationExist) {
            klerk.read(Ctx.authenticationIdentity()) { possibleEvents(rowling) }
        }
    }

    @Test
    fun `generalCommands rules are folded into command authorization and answer isGenerallyPossible`() =
        runBlocking<Unit> {
            val klerk = start {
                readModels { positive(::everybodyCanReadModels) }
                commands { positive(::everybodyCanDoEverything) }
                generalCommands { negative(::nobodyMayDeleteAuthorsGenerally) }
            }
            val astrid = createAuthorAstrid(klerk)
            val user = Ctx.authenticationIdentity()

            // Enforced for real commands, even though no commands{} rule mentions DeleteAuthor at all.
            val problem = klerk.handle(Command(DeleteAuthor, astrid), user).problems().single()
            assertEquals(KlerkErrorCode.CommandNegativeAuthorizationExist, problem.code)

            // Answerable with no model instance at all.
            assertFalse(klerk.read(user) { isGenerallyPossible(DeleteAuthor.id) })
            assertTrue(klerk.read(user) { isGenerallyPossible(ImproveAuthor.id) })

            // possibleEvents (which needs an instance) reflects it too, since generalCommands rules are folded
            // into the same eventNegativeRules the full authorization check uses.
            assertFalse(DeleteAuthor in klerk.read(user) { possibleEvents(astrid) })
        }

    // ---------------------------------------------------------------- commands

    @Test
    fun `the system bypasses the command rules, but not validation`() = runBlocking<Unit> {
        val klerk = start { commands { negative(::nobodyCanDoAnything) } }
        val system = Ctx.system()

        assertIs<CommandResult.Success<*>>(klerk.handle(Command(AnEventWithoutParameters), system))
        assertTrue(AnEventWithoutParameters in klerk.read(system) { possibleVoidEvents(Author::class) })

        // cannotHaveAnAwfulName
        val awful = createAstridParameters.copy(firstName = FirstName("Mike"), lastName = LastName("Litoris"))
        val problems = klerk.handle(Command(CreateAuthor, awful), system).problems()
        assertTrue(problems.none { it is AuthorizationProblem }, "got $problems")
    }

    @Test
    fun `a command that is both invalid and unauthorized is reported as invalid`() = runBlocking<Unit> {
        val klerk = start { readModels { positive(::everybodyCanReadModels) } }

        // CreateAuthor rejects unauthenticated actors in a validation rule, and there are no command rules at all
        val problems = klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.unauthenticated()).problems()
        assertTrue(problems.none { it is AuthorizationProblem }, "got $problems")
        assertTrue(klerk.read(Ctx.system()) { views.authors.all.asSequence().toList() }.isEmpty())
    }

    @Test
    fun `a rejected command changes nothing`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val astrid = createAuthorAstrid(klerk)
        val before = klerk.read(Ctx.system()) { get(astrid) }

        val problems = klerk.handle(Command(DeleteAuthor, astrid), Ctx.authenticationIdentity()).problems()
        assertIs<AuthorizationProblem>(problems.single())

        assertEquals(before, klerk.read(Ctx.system()) { get(astrid) })
        assertTrue(klerk.read(Ctx.system()) { eventLog(astrid) }.get().none { it.eventReference == DeleteAuthor.id })
    }

    @Test
    fun `a failed command on a model the actor may not read looks like the model does not exist`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val rowling = createAuthorJKRowling(klerk)
        val user = Ctx.authenticationIdentity()

        val missing = klerk.handle(Command(DeleteAuthor, ModelID<Author>(rowling.value + 1)), user).problems()
        val unauthorized = klerk.handle(Command(DeleteAuthor, rowling), user).problems()
        val wrongState = klerk.handle(Command(DeleteAuthorAndBooks, rowling), user).problems()

        for (problems in listOf(missing, unauthorized, wrongState)) {
            val problem = assertIs<NotFoundProblem>(problems.single())
            assertFalse(problem.toString().contains("Established"), "got $problem")
        }

        // acting on it is still possible when the rules allow it
        assertIs<CommandResult.Success<*>>(klerk.handle(Command(ImproveAuthor, rowling), user))
    }

    @Test
    fun `a command token is only applied once, also when used concurrently`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val astrid = createAuthorAstrid(klerk)
        repeat(20) {
            val options = ProcessingOptions(CommandToken.simple())
            val results = (1..4).map {
                async(Dispatchers.Default) { klerk.handle(Command(ImproveAuthor, astrid), Ctx.system(), options) }
            }.awaitAll()
            assertEquals(1, results.count { it is CommandResult.Success }, "got $results")
        }
    }

    @Test
    fun `a job acting as its scheduler cannot do more than the scheduler`() = runBlocking<Unit> {
        val klerk = start(configureJobs = { register(ActAsScheduler) }) {
            readJobs { positive(::usersCanSeeTheirOwnJobs) }
        }

        val byUser = klerk.jobs.schedule(ActAsScheduler.declare(SchedulerCursor()), Ctx.authenticationIdentity())
        val bySystem = klerk.jobs.schedule(ActAsScheduler.declare(SchedulerCursor()), Ctx.system())
        klerk.jobs.runUntilIdle()

        assertEquals(false, ActAsScheduler.succeeded[byUser])
        assertEquals(true, ActAsScheduler.succeeded[bySystem])
    }

    // ---------------------------------------------------------------- jobs

    @Test
    fun `an actor can only see and control their own jobs`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val alice = Ctx(ModelReferenceIdentity(ModelID<User>(1)))
        val bob = Ctx(ModelReferenceIdentity(ModelID<User>(2)))

        val alicesJob = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi", stepsLeft = 10)), alice)
        val bobsJob = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi", stepsLeft = 10)), bob)

        assertEquals(listOf(alicesJob), klerk.jobs.all(alice).map { it.id })
        assertEquals(listOf(alicesJob), klerk.read(alice) { jobs.all() }.map { it.id })
        assertNull(klerk.read(alice) { jobs.getOrNull(bobsJob) })
        assertCode(KlerkErrorCode.JobReadPositiveAuthorizationMissing) { klerk.read(alice) { jobs.get(bobsJob) } }
        assertCode(KlerkErrorCode.JobReadPositiveAuthorizationMissing) { klerk.jobs.get(bobsJob, alice) }
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) { klerk.jobs.cancel(bobsJob, alice) }
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) { klerk.jobs.resume(bobsJob, alice) }
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) { klerk.jobs.delete(bobsJob, alice) }

        // Anonymous visitors cannot be told apart, so none of them owns a job.
        val anonymous = Ctx.unauthenticated()
        val anonymousJob = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi", stepsLeft = 10)), anonymous)
        assertTrue(klerk.jobs.all(Ctx.unauthenticated()).isEmpty())
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) {
            klerk.jobs.cancel(anonymousJob, Ctx.unauthenticated())
        }
        assertEquals(JobStatus.Ready, klerk.jobs.get(bobsJob, bob).status, "Bob's job must be untouched")

        klerk.jobs.cancel(alicesJob, alice)
        assertEquals(JobStatus.Cancelling, klerk.jobs.get(alicesJob, alice).status)
    }

    @Test
    fun `seeing a job does not allow controlling it`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val alice = Ctx(ModelReferenceIdentity(ModelID<User>(1)))
        val job = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi", stepsLeft = 10)), alice)

        val info = klerk.jobs.get(job, alice)
        assertEquals(JobAgent.System, info.agent)

        // usersCanCancelTheirOwnJobs only allows Cancel
        assertTrue(klerk.jobs.isAllowed(job, JobOperation.Cancel, alice))
        assertFalse(klerk.jobs.isAllowed(job, JobOperation.Resume, alice))
        assertFalse(klerk.jobs.isAllowed(job, JobOperation.Delete, alice))
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) { klerk.jobs.resume(job, alice) }
        assertCode(KlerkErrorCode.JobControlPositiveAuthorizationMissing) { klerk.jobs.delete(job, alice) }

        assertTrue(klerk.jobs.isAllowed(job, JobOperation.Resume, Ctx.system()))
    }

    @Test
    fun `a job subscription only emits the jobs the actor may see`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val alice = Ctx(ModelReferenceIdentity(ModelID<User>(1)))
        val bob = Ctx(ModelReferenceIdentity(ModelID<User>(2)))

        val firstSeenByAlice = async(start = CoroutineStart.UNDISPATCHED) {
            klerk.jobs.subscribe(null, alice).first()
        }
        klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), bob)
        val alicesJob = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), alice)

        assertEquals(alicesJob, withTimeout(10.seconds) { firstSeenByAlice.await() }.id)
    }

    // ---------------------------------------------------------------- read blocks

    @Test
    fun `a reader cannot be used after its read block`() = runBlocking<Unit> {
        val klerk = start { standardRules() }
        val astrid = createAuthorAstrid(klerk)
        val smuggled = klerk.read(Ctx.authenticationIdentity()) { this }

        assertFailsWith<IllegalStateException> { smuggled.get(astrid) }
        assertFailsWith<IllegalStateException> { smuggled.jobs.all() }
    }

    // ---------------------------------------------------------------- specification

    @Test
    fun `an authorization rule must be a named function`() {
        val bookViews = BookViews()
        val views = Views(bookViews, AuthorViews(bookViews.all))
        val exception = assertFailsWith<IllegalConfigurationException> {
            runBlocking {
                val klerk = Klerk.create(spec(views) { readModels { positive({ _ -> Allow }) } }, settings())
                klerk.meta.start(installShutdownHook = false)
            }
        }
        assertEquals(KlerkErrorCode.RuleMustBeNamed, exception.code)
    }

    // ---------------------------------------------------------------- setup

    private suspend fun start(
        configureJobs: dev.klerkframework.klerk.job.JobsBlock<Ctx, Views>.() -> Unit = {},
        authorization: SpecificationBuilder.AuthorizationRulesBlock<Ctx, Views>.() -> Unit,
    ): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val views = Views(bookViews, AuthorViews(bookViews.all))
        val klerk = Klerk.create(spec(views, configureJobs, authorization), settings())
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private fun spec(
        views: Views,
        configureJobs: dev.klerkframework.klerk.job.JobsBlock<Ctx, Views>.() -> Unit = {},
        authorization: SpecificationBuilder.AuthorizationRulesBlock<Ctx, Views>.() -> Unit,
    ): Specification<Ctx, Views> = SpecificationBuilder<Ctx, Views>(views).build {
        eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
        managedModels {
            model(Book::class, bookStateMachine(views), views.books)
            model(Author::class, authorStateMachine(views), views.authors)
        }
        jobs {
            register(MyJob)
            register(MyJob2)
            configureJobs()
        }
        authorization(authorization)
        systemContextProvider(::myContextProvider)
        jobContextProvider(::myJobContextProvider)
    }

    private fun settings() = testSettings()

    private fun blob() = "picture".byteInputStream()

    private suspend fun createAuthorWithPicture(
        klerk: Klerk<Ctx, Views>,
        preparedBy: Ctx = Ctx.system(),
    ): AttachedBlobID {
        val picture = klerk.attachedData.prepare(blob(), AuthorPicture::class, preparedBy)
        klerk.handle(
            Command(CreateAuthor, createAstridParameters.copy(picture = AuthorPicture(picture))),
            Ctx.system(),
        ).getOrThrow()
        return picture
    }

    private fun CommandResult<*>.problems(): List<Problem> = assertIs<CommandResult.Failure<*>>(this).problems

    private inline fun assertCode(expected: KlerkErrorCode, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<KlerkException> { block() }.code)
    }
}

/**
 * The rules most tests use: anyone may read anything except Rowling and every street, anyone may do anything except
 * delete an author or create one without parameters, and everybody sees their own jobs.
 */
private fun SpecificationBuilder.AuthorizationRulesBlock<Ctx, Views>.standardRules() {
    readModels {
        positive(::everybodyCanReadModels)
        negative(::nobodyCanReadRowlingOrHarryPotter)
    }
    readProperties {
        positive(::everybodyCanReadProperties)
        negative(::nobodyCanReadStreets)
    }
    commands {
        positive(::everybodyCanDoEverything)
        negative(::nobodyCanDeleteAuthors, ::nobodyCanCreateAuthorsWithoutParameters)
    }
    eventLog { positive(::everybodyCanReadTheEventLog) }
    readJobs { positive(::usersCanSeeTheirOwnJobs) }
    controlJobs { positive(::usersCanCancelTheirOwnJobs) }
}

private fun everybodyCanReadModels(args: ModelReadRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanReadModels(args: ModelReadRuleArgs<Ctx, Views>) = Deny
private fun nobodyCanReadRowlingOrHarryPotter(args: ModelReadRuleArgs<Ctx, Views>): NegativeAuthorization {
    val props = args.model.props
    val rowling = props is Author && props.lastName.value == "Rowling"
    val harryPotter = props is Book && props.title.value.startsWith("Harry Potter")
    return if (rowling || harryPotter) Deny else Pass
}

private fun everybodyCanReadProperties(args: PropertyReadRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanReadStreets(args: PropertyReadRuleArgs<Ctx, Views>) = if (args.property is Street) Deny else Pass

private fun everybodyCanDoEverything(args: CommandRuleArgs<*, Ctx, Views>) = Allow
private fun nobodyCanDoAnything(args: CommandRuleArgs<*, Ctx, Views>) = Deny
private fun nobodyCanDeleteAuthors(args: CommandRuleArgs<*, Ctx, Views>) =
    if (args.command.event == DeleteAuthor) Deny else Pass
private fun nobodyCanCreateAuthorsWithoutParameters(args: CommandRuleArgs<*, Ctx, Views>) =
    if (args.command.event == AnEventWithoutParameters) Deny else Pass
private fun nobodyMayDeleteAuthorsGenerally(args: EventRuleArgs<Ctx, Views>) =
    if (args.event == DeleteAuthor) Deny else Pass

private fun everybodyCanReadTheEventLog(args: EventLogRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanReadTheEventLog(args: EventLogRuleArgs<Ctx, Views>) = Deny
private fun everybodyCanReadTheActivityLog(args: ActivityLogRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanReadTheActivityLog(args: ActivityLogRuleArgs<Ctx, Views>) = Deny

private fun everybodyCanReadAttachedData(args: AttachedDataReadRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanReadAttachedData(args: AttachedDataReadRuleArgs<Ctx, Views>) = Deny
private fun everybodyCanWriteAttachedData(args: AttachedDataWriteRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanWriteAttachedData(args: AttachedDataWriteRuleArgs<Ctx, Views>) = Deny

private fun everybodyCanSeeJobs(args: JobReadRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanSeeJobs(args: JobReadRuleArgs<Ctx, Views>) = Deny
private fun usersCanSeeTheirOwnJobs(args: JobReadRuleArgs<Ctx, Views>) = if (args.isOwnedByActor()) Allow else NoOpinion
private fun everybodyCanControlJobs(args: JobControlRuleArgs<Ctx, Views>) = Allow
private fun nobodyCanControlJobs(args: JobControlRuleArgs<Ctx, Views>) = Deny
private fun usersCanCancelTheirOwnJobs(args: JobControlRuleArgs<Ctx, Views>) =
    if (args.operation == JobOperation.Cancel && args.isOwnedByActor()) Allow else NoOpinion

@Serializable
data class SchedulerCursor(val attempted: Boolean = false)

/** Tries one command as whoever scheduled it, and reports whether it went through. */
object ActAsScheduler : JobType.Local<SchedulerCursor, Ctx, Views>() {
    override val name = JobName("act-as-scheduler")
    override val agent: JobAgent = JobAgent.Scheduler

    val succeeded = mutableMapOf<JobID, Boolean>()

    override suspend fun step(
        args: JobStepArgs.Local<SchedulerCursor, Ctx, Views>,
    ): JobResult<SchedulerCursor, Ctx, Views> {
        if (!args.cursor.attempted) {
            return JobResult.Yield(
                cursor = SchedulerCursor(attempted = true),
                command = Command(AnEventWithoutParameters),
            )
        }
        succeeded[args.job.id] = args.previousResult is CommandResult.Success
        return JobResult.Success()
    }
}

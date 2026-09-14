package dev.klerkframework.klerk

import dev.klerkframework.klerk.AlwaysFalseDecisions.Something
import dev.klerkframework.klerk.AuthorStates.*
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.NegativeAuthorization.Pass
import dev.klerkframework.klerk.PropertyCollectionValidity.Invalid
import dev.klerkframework.klerk.PropertyCollectionValidity.Valid
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.misc.AlgorithmBuilder
import dev.klerkframework.klerk.misc.Decision
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.misc.ShouldSendNotificationAlgorithm
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.ModelCacheSettings
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.storage.SqlPersistence
import dev.klerkframework.klerk.validation.PropertyValidation
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.sql.DriverManager
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import dev.klerkframework.klerk.view.*

var onEnterAmateurStateActionCallback: (() -> Unit)? = null
var onEnterImprovingStateActionCallback: (() -> Unit)? = null

/**
 * The specification the tests share. Everything deployment-specific — storage, clock, blob store, how jobs are run —
 * is in [testSettings] instead.
 *
 * @param configureJobs applied last inside the `jobs` block, so a test can register its own job types or declare crons.
 * @param configureAuthorization applied last inside the `authorization` block, so a test can add rules of its own.
 */
fun createConfig(
    collections: Views,
    configureJobs: JobsBlock<Ctx, Views>.() -> Unit = {},
    configureAuthorization: SpecificationBuilder.AuthorizationRulesBlock<Ctx, Views>.() -> Unit = {},
): Specification<Ctx, Views> {
    return SpecificationBuilder<Ctx, Views>(collections).build {
        jobContextProvider(::myJobContextProvider)
        jobs {
            register(MyJob)
            register(MyJob2)
            configureJobs()
        }
        managedModels {
            model(Book::class, bookStateMachine(collections), collections.books)
            model(Author::class, authorStateMachine(collections), collections.authors)
            model(Painting::class, paintingStateMachine(), collections.paintings)
            model(Inventory::class, inventoryStateMachine(), collections.inventories)
            model(Note::class, noteStateMachine(), collections.notes)
        }
        authorization {
            readModels {
                positive {
                    rule(::`Everybody can read`)
                }
                negative {
                    rule(::pelleCannotReadOnMornings)
                    rule(::unauthenticatedCannotReadAstrid)
                }
            }

            readProperties {
                positive {
                    rule(::canReadAllProperties)
                }
                negative {
                    rule(::cannotReadAstrid)
                }
            }
            commands {
                positive {
                    rule(::`Everybody can do everything`)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(::`Everybody can read event log`)
                }
                negative {}
            }
            readAttachedData {
                positive {
                    rule(::onlyTheAuthorsOwnerCanReadThePicture)
                }
                negative {
                    rule(::unauthenticatedCannotReadAttachedData)
                }
            }
            writeAttachedData {
                positive {
                    rule(::everybodyCanPrepareAttachedData)
                }
                negative {
                    rule(::unauthenticatedCannotPrepareStrings)
                }
            }
            jobs {
                positive {
                    rule(::authorsCanSeeTheirOwnJobs)
                    rule(::systemCanSeeAllJobs)
                }
                negative {}
            }
            configureAuthorization()
        }
        systemContextProvider(::myContextProvider)
    }
}

/**
 * The settings the tests share.
 *
 * @param clock what background work (jobs, retries, cron, time triggers) reads the time from.
 * @param jobs manual execution by default: a test that wants a job to run says so, and nothing runs behind its back.
 */
fun testSettings(
    storage: Persistence = RamStorage(),
    clock: Clock = Clock.System,
    blobStore: AttachedBlobStore = AttachedBlobStore.Database,
    jobs: JobSettings = JobSettings(execution = JobExecution.Manual),
    modelCache: ModelCacheSettings = ModelCacheSettings(),
): KlerkSettings = KlerkSettings(
    persistence = storage,
    attachedBlobStore = blobStore,
    clock = clock,
    jobs = jobs,
    modelCache = modelCache,
)

/** [createConfig] and [testSettings] in one call, for the many tests that just want a running Klerk. */
fun createKlerk(
    collections: Views,
    storage: Persistence = RamStorage(),
    clock: Clock = Clock.System,
    blobStore: AttachedBlobStore = AttachedBlobStore.Database,
    jobs: JobSettings = JobSettings(execution = JobExecution.Manual),
    configureJobs: JobsBlock<Ctx, Views>.() -> Unit = {},
): Klerk<Ctx, Views> =
    Klerk.create(createConfig(collections, configureJobs), testSettings(storage, clock, blobStore, jobs))

fun myContextProvider(): Ctx {
    return Ctx(
        actor = SystemIdentity,

        )
}

/** Gives a job step a context whose time comes from the configured clock, so job time is controllable in tests. */
fun myJobContextProvider(request: JobContextRequest): Ctx = Ctx(actor = request.actor, time = request.time)

fun authorsCanSeeTheirOwnJobs(args: JobReadRuleArgs<Ctx, Views>): PositiveAuthorization =
    if (args.isOwnedByActor()) PositiveAuthorization.Allow else PositiveAuthorization.NoOpinion

fun systemCanSeeAllJobs(args: JobReadRuleArgs<Ctx, Views>): PositiveAuthorization =
    if (args.context.actor is SystemIdentity) PositiveAuthorization.Allow else PositiveAuthorization.NoOpinion

fun cannotReadAstrid(args: PropertyReadRuleArgs<Ctx, Views>): dev.klerkframework.klerk.NegativeAuthorization {
    return if (args.property is FirstName && args.property.valueWithoutAuthorization == "Astrid") Deny else Pass
}

fun canReadAllProperties(args: PropertyReadRuleArgs<Ctx, Views>): dev.klerkframework.klerk.PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}

/**
 * A model-relative rule: it reaches the owning model, which is the point of handing the rule a [Model] and a `Reader`.
 */
fun onlyTheAuthorsOwnerCanReadThePicture(args: AttachedDataReadRuleArgs<Ctx, Views>): PositiveAuthorization {
    val props = args.owner.props
    if (props is Author && props.lastName.value == "Secretive") {
        return PositiveAuthorization.NoOpinion
    }
    return PositiveAuthorization.Allow
}

fun unauthenticatedCannotReadAttachedData(args: AttachedDataReadRuleArgs<Ctx, Views>): NegativeAuthorization =
    if (args.context.actor is Unauthenticated) Deny else Pass

fun everybodyCanPrepareAttachedData(args: AttachedDataWriteRuleArgs<Ctx, Views>): PositiveAuthorization =
    PositiveAuthorization.Allow

/** A rule that keys on the kind. Nonsensical as a policy, but that is not the point. */
fun unauthenticatedCannotPrepareStrings(args: AttachedDataWriteRuleArgs<Ctx, Views>): NegativeAuthorization =
    if (args.kind == AttachedDataKind.String && args.context.actor is Unauthenticated) Deny else Pass

fun unauthenticatedCannotReadAstrid(args: ModelReadRuleArgs<Ctx, Views>): dev.klerkframework.klerk.NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is dev.klerkframework.klerk.Unauthenticated) Deny else Pass
}

fun `Everybody can do everything`(argCommandContextReader: CommandRuleArgs<*, Ctx, Views>): dev.klerkframework.klerk.PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}


fun `Everybody can read event log`(args: EventLogRuleArgs<Ctx, Views>): PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}

fun `Everybody can read`(args: ModelReadRuleArgs<Ctx, Views>): PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}

fun pelleCannotReadOnMornings(
    args: ModelReadRuleArgs<Ctx, Views>
): dev.klerkframework.klerk.NegativeAuthorization {
    try {
        if (args.context.user?.props?.name?.value.equals("Pelle")) {
            return if (args.context.time.toLocalDateTime(TimeZone.currentSystemDefault()).time < kotlinx.datetime.LocalTime.fromSecondOfDay(
                    3600 * 12
                )
            ) Deny else Pass
        }
    } catch (e: Exception) {
        //
    }
    return Pass
}

class BookViews : ModelViews<Book, Ctx>() {

    fun childrensBooks(): List<ModelID<Book>> {
        return emptyList()
    }
}

class AuthorViews<V>(val allBooks: ModelView<Book, Ctx>) : ModelViews<Author, Ctx>() {

    private val greatAuthorNames = setOf("Linus", "Bertil")

    val greatAuthors = this.all.filter { greatAuthorNames.contains(it.props.firstName.value) }.register("greatAuthors")
    val establishedAuthors = this.all.filter { it.state == Established.name }.register("establishedAuthors")
    val establishedGreatAuthors =
        greatAuthors.filter { it.state == Established.name }.register("establishedGreatAuthors")
    lateinit var establishedGreatWithAtLeastTwoBooks: AuthorsWithAtLeastTwoBooks<V>

    val midrangeAuthors = this.all.filter {
        val i = it.props.lastName.value.toIntOrNull() ?: 0
        return@filter i in 15..24
    }

    override fun initialize() {
        establishedGreatWithAtLeastTwoBooks =
            AuthorsWithAtLeastTwoBooks(all, allBooks)
        establishedGreatWithAtLeastTwoBooks.register("medMinst2Böcker")
    }

}

data class Book(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>>,
    val previousBooksInSameSeries: List<ModelID<Book>>,
    val tags: Set<BookTag>,
    val salesPerYear: Set<Quantity>,
    val averageScore: AverageScore,
    val writtenAt: BookWrittenAt,
    val readingTime: ReadingTime,
    val publishedAt: BookWrittenAt?,
    val releasePartyPosition: ReleasePartyPosition,
    val genre: BookGenreContainer = BookGenreContainer(BookGenre.Fiction),
    // attached data, see docs/attached-data.md
    val notes: BookNotes? = null,
    val cover: BookCover? = null,
    val thumbnail: BookThumbnail? = null,
    val chapters: List<BookChapter> = emptyList(),
) {
    override fun toString() = title.value
}

data class Author(
    val firstName: FirstName,
    val lastName: LastName,
    val address: Address,
    val picture: AuthorPicture?
) : Validatable {
    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf(::noAuthorCanBeNamedJamesClavell)

    private fun noAuthorCanBeNamedJamesClavell(): PropertyCollectionValidity {
        return if (firstName.value == "James" && lastName.value == "Clavell") Invalid() else Valid
    }

    override fun toString(): String = "$firstName $lastName"
}

class ReleasePartyPosition(value: GeoPosition) : GeoPositionContainer(value)

//data class Shop(val shopName: ShopName, val owner: Reference<Author>) : CudModel

class ShopName(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

data class CreateAuthorParams(
    val firstName: FirstName,
    val lastName: LastName,
    val phone: PhoneNumber,
    val age: PositiveEvenIntContainer = PositiveEvenIntContainer(68),
    //  val address: Address,
    val secretToken: SecretPasscode,
    val favouriteColleague: ModelID<Author>? = null,
    val picture: AuthorPicture? = null,
) : Validatable {

    override fun validators(): Set<() -> PropertyCollectionValidity> =
        setOf(::augustStrindbergCannotHaveCertainPhoneNumber)

    private fun augustStrindbergCannotHaveCertainPhoneNumber(): PropertyCollectionValidity {
        return if (firstName.value == "August" && lastName.value == "Strindberg" && phone.value == "123456") Invalid() else Valid
    }
}

data class ChangeNameParams(val updatedFirstName: FirstName, val updatedLastName: LastName)

fun authorStateMachine(collections: Views): StateMachine<Author, AuthorStates, Ctx, Views> =

    stateMachine {

        event(CreateAuthor) {
            validateWithContext(::preventUnauthenticated)
            validateWithParameters(::cannotHaveAnAwfulName)
            validateWithParameters(::secretTokenShouldBeZeroIfNameStartsWithM)
            validateWithParameters(::onlyAuthenticationIdentityCanCreateDaniel)
            validReferences(CreateAuthorParams::favouriteColleague, collections.authors.all)
        }

        event(AnEventWithoutParameters) {}

        event(UpdateAuthor) {}

        event(ImproveAuthor) {}

        event(ChangeName) {}

        event(DeleteAuthor) {}

        event(DeleteAuthorAndBooks) {}


        voidState {
            onEvent(CreateAuthor) {
                createModel(Amateur, ::newAuthor)
            }

            onEvent(AnEventWithoutParameters) {
                createModel(Amateur, ::newAuthor2)
            }
        }

        state(Amateur) {
            onEnter {
                unmanagedJob(::onEnterAmateurStateAction)
            }

            onEvent(UpdateAuthor) {
                update(::updateAuthor)
            }

            onEvent(DeleteAuthor) {
                delete()
            }

            onEvent(DeleteAuthorAndBooks) {
                commands(::eventsToDeleteAuthorAndBooks)
            }

            onEvent(ImproveAuthor) {
                unmanagedJob(::showNotification, onCondition = ShouldSendNotificationAlgorithm::execute)
                transitionTo(Improving)
            }

            onEvent(ChangeName) {
                update(::changeNameOfAuthor)
                jobs(::notifyBookStores)
            }

            after(30.seconds) {
                transitionTo(Established)
                update(::someUpdate)
                unmanagedJob(::sayHello)
            }

        }

        state(Improving) {
            onEnter {
                unmanagedJob(::onEnterImprovingStateAction)
                transitionWhen {
                    on(::isAnImpostor, Amateur)
                    on(::hasTalent, Established)
                }
                jobs(::aJob)
            }

        }

        state(Established) {

            atTime(::later) {
                delete()
            }

            onEvent(ImproveAuthor) {
                transitionWhen {
                    on(ShouldSendNotificationAlgorithm::execute, Improving)
                }
            }

            onEvent(DeleteAuthor) {
                delete()
            }
        }

    }

fun someUpdate(args: LifecycleArgs<Author, Ctx, Views>): Author {
    return args.model.props.copy(lastName = LastName("efter"))
}

fun onExitUpdate(args: LifecycleArgs<Author, Ctx, Views>): Author {
    return args.model.props.copy(FirstName("Changed name after exit"))
}

fun sayHello(args: LifecycleArgs<Author, Ctx, Views>) {
    println("Hello!")
}

fun later(args: LifecycleArgs<Author, Ctx, Views>): Instant {
    return args.time.plus(30.seconds)
}

fun hasTalent(args: LifecycleArgs<Author, Ctx, Views>): Boolean = true
fun isAnImpostor(args: LifecycleArgs<Author, Ctx, Views>): Boolean = false

fun aJob(args: LifecycleArgs<Author, Ctx, Views>): List<DeclaredJob<Ctx, Views>> {
    return listOf(MyJob.declare(MyJobCursor(greeting = "pelle")))
}


fun onEnterImprovingStateAction(args: LifecycleArgs<Author, Ctx, Views>) {
    if (onEnterImprovingStateActionCallback != null) {
        onEnterImprovingStateActionCallback!!()
    }
}


fun showNotification(args: InstanceEventArgs<Author, Nothing?, Ctx, Views>) {
    println("It was decided that we should show a notification")
}

fun onEnterAmateurStateAction(args: LifecycleArgs<Author, Ctx, Views>) {
    if (onEnterAmateurStateActionCallback != null) {
        onEnterAmateurStateActionCallback!!()
    }
}


fun notifyBookStores(args: InstanceEventArgs<Author, ChangeNameParams, Ctx, Views>): List<DeclaredJob<Ctx, Views>> {
    return listOf(MyJob2.declare(MyJobCursor(greeting = "Hej")))
}

/** A cursor that is deliberately just a value, so that tests can assert on what a step was given. */
@Serializable
data class MyJobCursor(val greeting: String, val stepsLeft: Int = 0)

object MyJob2 : JobType.Local<MyJobCursor, Ctx, Views>() {

    override val name = JobName("my-job-2")
    override val agent: JobAgent = JobAgent.System

    override suspend fun step(args: JobStepArgs.Local<MyJobCursor, Ctx, Views>): JobResult<MyJobCursor> {
        assertEquals("Hej", args.cursor.greeting)
        return JobResult.Success()
    }
}

fun changeNameOfAuthor(args: InstanceEventArgs<Author, ChangeNameParams, Ctx, Views>): Author {
    return args.model.props.copy(
        firstName = args.command.params.updatedFirstName,
        lastName = args.command.params.updatedLastName
    )
}

fun eventsToDeleteAuthorAndBooks(args: InstanceEventArgs<Author, Nothing?, Ctx, Views>): List<Command<Any, Any>> {
    args.reader.apply {
        val result: MutableList<Command<Any, Any>> = mutableListOf()
        val books = referencing(Book::class, requireNotNull(args.model.id))

        @Suppress("UNCHECKED_CAST")
        books.map { Command(DeleteBook, it.id) }
            .forEach { result.add(it as Command<Any, Any>) }

        @Suppress("UNCHECKED_CAST")
        result.add(
            Command(DeleteAuthor, requireNotNull(args.model.id))
                    as Command<Any, Any>
        )

        return result
    }
}

fun newAuthor(args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>): Author {
    val params = args.command.params
    return Author(
        firstName = params.firstName,
        lastName = params.lastName,
        address = Address(Street("kjh")),
        picture = params.picture
    )
}

fun newAuthor2(args: VoidEventArgs<Author, Nothing?, Ctx, Views>): Author {
    return Author(FirstName("Auto"), LastName("Created"), Address(Street("Somewhere")), picture = null)
}


fun updateAuthor(args: InstanceEventArgs<Author, Author, Ctx, Views>): Author {
    return args.command.params
}


fun onlyAuthenticationIdentityCanCreateDaniel(args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>): PropertyCollectionValidity {
    return if (args.command.params.firstName.value == "Daniel" && args.context.actor != dev.klerkframework.klerk.AuthenticationIdentity) Invalid() else Valid
}

fun cannotHaveAnAwfulName(args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>): PropertyCollectionValidity {
    return if (args.command.params.firstName.value == "Mike" && args.command.params.lastName.value == "Litoris") Invalid() else Valid
}

fun secretTokenShouldBeZeroIfNameStartsWithM(args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>): PropertyCollectionValidity {
    return if (args.command.params.firstName.value.startsWith("M") && args.command.params.secretToken.value != 0L) Invalid() else Valid
}

fun preventUnauthenticated(context: Ctx): PropertyCollectionValidity {
    return if (context.actor == dev.klerkframework.klerk.Unauthenticated) Invalid() else Valid
}

fun onlyAllowAuthorNameAstridIfThereIsNoRowling(args: VoidEventArgs<Author, CreateAuthorParams, Ctx, Views>): PropertyCollectionValidity {
    args.reader.apply {
        if (args.command.params.firstName.value != "Astrid") {
            return Valid
        }
        val rowling = views.authors.all.asSequence().firstOrNull { it.props.firstName.value == "Rowling" }
        return if (rowling == null) Valid else Invalid()
    }
}

fun newBook(args: VoidEventArgs<Book, CreateBookParams, Ctx, Views>): Book {
    val params = args.command.params
    return Book(
        title = params.title,
        author = params.author,
        coAuthors = params.coAuthors,
        previousBooksInSameSeries = params.previousBooksInSameSeries,
        tags = params.tags,
        salesPerYear = setOf(Quantity(43), Quantity(67)),
        averageScore = params.averageScore,
        writtenAt = BookWrittenAt(Instant.fromEpochSeconds(100000)),
        readingTime = params.readingTime,
        publishedAt = null,
        releasePartyPosition = ReleasePartyPosition(GeoPosition(latitude = 1.234, longitude = 3.456)),
        genre = BookGenreContainer(BookGenre.Fiction),
        notes = params.notes,
        cover = params.cover,
        thumbnail = params.thumbnail,
        chapters = params.chapters,
    )
}

fun updateBook(args: InstanceEventArgs<Book, Book, Ctx, Views>): Book = args.command.params


enum class AuthorStates {
    Amateur,
    Improving,
    Established,
}

data class Views(
    val books: BookViews,
    val authors: AuthorViews<Views>,
    val paintings: ModelViews<Painting, Ctx> = ModelViews(),
    val sketches: ModelViews<Sketch, Ctx> = ModelViews(),
    val scribbles: ModelViews<Scribble, Ctx> = ModelViews(),
    val doodles: ModelViews<Doodle, Ctx> = ModelViews(),
    val inventories: ModelViews<Inventory, Ctx> = ModelViews(),
    val notes: ModelViews<Note, Ctx> = ModelViews(),
) //, val shops: ModelView<Shop, Context>)

suspend fun createAuthorJKRowling(klerk: Klerk<Ctx, Views>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            CreateAuthor,
            CreateAuthorParams(
                firstName = FirstName("J.K"),
                lastName = LastName("Rowling"),
                phone = PhoneNumber("+46123456"),
                secretToken = SecretPasscode(234234902359245345),
                //       address = Address(Street("Storgatan"))
            )
        ),
        Ctx.system(),
    )
    return requireNotNull(result.getOrThrow().primaryModel)
}

suspend fun createAuthorAstrid(klerk: Klerk<Ctx, Views>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            CreateAuthor,
            createAstridParameters
        ),
        Ctx.system(),
    )
    @Suppress("UNCHECKED_CAST")
    return result.getOrThrow().createdModels.single() as ModelID<Author>
}

val createAstridParameters = CreateAuthorParams(
    firstName = FirstName("Astrid"),
    lastName = LastName("Lindgren"),
    phone = PhoneNumber("+4699999"),
    secretToken = SecretPasscode(234123515123434),
)

suspend fun createBookHarryPotter1(klerk: Klerk<Ctx, Views>, author: ModelID<Author>): ModelID<Book> {
    val result = klerk.handle(
        Command(
            CreateBook,
            CreateBookParams(
                title = BookTitle("Harry Potter and the Philosopher's Stone"),
                author = author,
                coAuthors = emptySet(),
                previousBooksInSameSeries = emptyList(),
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f),
                readingTime = ReadingTime(2.hours)
            )
        ),
        Ctx.system(),
    )
    return requireNotNull(result.getOrThrow().primaryModel)
}

suspend fun createBookHarryPotter2(
    klerk: Klerk<Ctx, Views>,
    author: ModelID<Author>,
    previousBooksInSameSeries: List<ModelID<Book>>,
    coAuthors: Set<ModelID<Author>>
): ModelID<Book> {
    val result = klerk.handle(
        Command(
            CreateBook,
            CreateBookParams(
                title = BookTitle("Harry Potter and the Chamber of Secrets"),
                author = author,
                coAuthors = coAuthors,
                previousBooksInSameSeries = previousBooksInSameSeries,
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f),
                readingTime = ReadingTime(2.hours)
            )
        ),
        Ctx.system(),
    )
    return requireNotNull(result.getOrThrow().primaryModel)
}

class PhoneNumber(value: String) : StringContainer(value) {
    override val minLength = 3
    override val maxLength = 10
    override val maxLines = 1
}

class PositiveEvenIntContainer(value: Int) : IntContainer(value) {
    override val min: Int = 2
    override val max: Int = Int.MAX_VALUE

    override val validators = setOf(::mustBeEven)

    fun mustBeEven(t: Translation): PropertyValidation {
        if (valueWithoutAuthorization % 2 == 0) {
            return PropertyValidation.Valid
        }
        return PropertyValidation.Invalid()
    }

}

class FirstName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
    override val recommendedDefault = "Astrid"
}

class LastName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

class BookTitle(value: String) : StringContainer(value) {
    override val minLength = 2
    override val maxLength = 100
    override val maxLines: Int = 1
    override val regexPattern = ".*"
    override val validators = setOf(::`title must be catchy`)

    private fun `title must be catchy`(translation: Translation): PropertyValidation {
        return PropertyValidation.Valid
    }
}

class BookTag(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

class SecretPasscode(value: Long) : LongContainer(value) {
    override val min: Long = Long.MIN_VALUE
    override val max: Long = Long.MAX_VALUE
}

class IsActive(value: Boolean) : BooleanContainer(value)

class Quantity(value: Int) : IntContainer(value) {
    override val min: Int = 0
    override val max: Int = Int.MAX_VALUE
}

class BookWrittenAt(value: Instant) : InstantContainer(value)

class ReadingTime(value: Duration) : DurationContainer(value)

enum class BookGenre { Fiction, Mystery, Fantasy }

class BookGenreContainer(value: BookGenre) : EnumContainer<BookGenre>(value)

data class Address(val street: Street)

class Street(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

fun addStandardTestConfiguration(auth: Boolean = true): SpecificationBuilder<Ctx, Views>.() -> Unit = {
    if (auth) {
        authorization {
            readModels {
                positive {
                    rule(::`Everybody can read`)
                }
                negative {
                    rule(::pelleCannotReadOnMornings)
                }
            }
            commands {
                positive {
                    rule(::`Everybody can do everything`)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(::`Everybody can read event log`)
                }
                negative {}
            }
            jobs {
                positive {
                    rule(::systemCanSeeAllJobs)
                }
                negative {}
            }
        }
        systemContextProvider { Ctx(SystemIdentity) }
        jobContextProvider(::myJobContextProvider)
        // The state machines used by the tests schedule these, so they have to be loadable on a restart.
        jobs {
            register(MyJob)
            register(MyJob2)
        }
    }
}

/**
 * This is a hack to keep the SQLite connection open.
 * See https://github.com/JetBrains/Exposed/issues/726#issuecomment-932202379
 */
object SQLiteInMemory {
    private var keepAlive: Connection? = null

    fun create(): SqlPersistence {
        keepAlive?.close()
        // Every call reuses the same shared-cache database name, so a counter keeps concurrent tests from
        // colliding on it.
        val sqliteMemoryPath = "jdbc:sqlite:file:test${dbCounter.getAndIncrement()}?mode=memory&cache=shared"
        // Shared-cache mode uses table-level locking between connections: without read_uncommitted, a reader
        // can hit SQLITE_LOCKED_SHAREDCACHE while a concurrent writer holds the table, and that error is not
        // retried by SQLite's busy handler.
        val config = SQLiteConfig().apply { setReadUncommitted(true) }
        val ds = SQLiteDataSource(config)
        ds.url = sqliteMemoryPath
        keepAlive = DriverManager.getConnection(sqliteMemoryPath)
        return SqlPersistence(ds)
    }

    private val dbCounter = java.util.concurrent.atomic.AtomicInteger()
}

object CreateAuthor :
    VoidEventWithParameters<Author, CreateAuthorParams>(External)

object UpdateAuthor : InstanceEventWithParameters<Author, Author>(External) {

}

object DeleteAuthor : InstanceEventNoParameters<Author>(External)

object DeleteAuthorAndBooks : InstanceEventNoParameters<Author>(External)

object ImproveAuthor : InstanceEventNoParameters<Author>(External)

object ChangeName : InstanceEventWithParameters<Author, ChangeNameParams>(External)

sealed class AlwaysFalseDecisions(
    override val name: String,
    override val function: (InstanceEventArgs<Author, CreateAuthorParams, Ctx, Views>) -> Boolean
) : Decision<Boolean, InstanceEventArgs<Author, CreateAuthorParams, Ctx, Views>> {
    data object Something : AlwaysFalseDecisions("This will always be false", ::alwaysFalse)

}

fun alwaysFalse(args: InstanceEventArgs<Author, CreateAuthorParams, Ctx, Views>): Boolean {
    return false
}


object AlwaysFalseAlgorithm :
    FlowChartAlgorithm<InstanceEventArgs<Author, CreateAuthorParams, Ctx, Views>, Boolean>("Always false") {

    override fun configure(): AlgorithmBuilder<InstanceEventArgs<Author, CreateAuthorParams, Ctx, Views>, Boolean>.() -> Unit =
        {
            start(Something)
            booleanNode(Something) {
                on(true, terminateWith = false)
                on(false, terminateWith = false)
            }
        }
}

data class Ctx(
    override val actor: dev.klerkframework.klerk.ActorIdentity,
    override val eventLogExtra: String? = null,
    override val time: Instant = Clock.System.now(),
    override val translation: Translation = DefaultTranslation,
    val user: Model<User>? = null,
    val purpose: String = "Pass the butter",
) : KlerkContext {

    companion object {
        fun fromUser(user: Model<User>): Ctx {
            return Ctx(ModelIdentity(user), user = user)
        }

        fun unauthenticated(): Ctx = Ctx(Unauthenticated)

        fun authenticationIdentity(): Ctx = Ctx(AuthenticationIdentity)

        fun system(): Ctx = Ctx(SystemIdentity)

        fun swedishUnauthenticated(): Ctx = Ctx(Unauthenticated, translation = SwedishTranslation)
    }

}

data class User(val name: FirstName)

object AnEventWithoutParameters : VoidEventNoParameters<Author>(External)

/**
 * Yields once per remaining step, so a test can watch a job progress through several checkpoints.
 */
object MyJob : JobType.Local<MyJobCursor, Ctx, Views>() {

    override val name = JobName("my-job")
    override val agent: JobAgent = JobAgent.System
    
    override suspend fun step(args: JobStepArgs.Local<MyJobCursor, Ctx, Views>): JobResult<MyJobCursor> {
        if (args.cursor.stepsLeft == 0) {
            return JobResult.Success(result = args.cursor.greeting)
        }
        return JobResult.Yield(
            cursor = args.cursor.copy(stepsLeft = args.cursor.stepsLeft - 1),
            progress = JobProgress(completed = 1, total = args.cursor.stepsLeft),
        )
    }
}

val english = EnglishKlerkTranslation(DefaultKlerkTranslation)

object SwedishTranslation : Translation {
    override val klerk: KlerkTranslation = SwedishKlerkTranslation(english)

}

class SwedishKlerkTranslation(val default: KlerkTranslation) : KlerkTranslation by default {

    override fun property(property: KProperty1<*, *>): String {
        return when (property) {
            CreateAuthorParams::firstName -> "Förnamn på den nya författaren"
            else -> default.property(property)
        }
    }

    override fun event(event: EventReference): String {
        return when (event) {
            PublishBook.id -> "Publicera bok"
            CreateAuthor.id -> "Ny författare"
            else -> default.event(event)
        }
    }

    override fun function(f: Function<Any>): String {
        ((f as KFunction<*>).annotations
            .firstOrNull { it is MyFunctionAnnotation } as? MyFunctionAnnotation)?.let { return it.name }
        return when (f) {
            ::myGreatFunction -> "Min fantastiska funktion"
            else -> default.function(f)
        }
    }

    override fun invalidProperty(
        propertyName: String,
        functionName: String,
        translationInfo: String?
    ): String {
        return when (functionName) {
            PositiveEvenIntContainer::mustBeEven.name -> "Förväntade mig ett jämt nummer"
            else -> return default.invalidProperty(propertyName, functionName, translationInfo)
        }
    }

    override fun mustBeAtLeast(value: Number) = "Måste vara minst $value"

}

class EnglishKlerkTranslation(val default: KlerkTranslation) : KlerkTranslation by default {

    override fun property(property: KProperty1<*, *>): String {
        return when (property) {
            CreateAuthorParams::firstName -> "First name of the new writer"
            CreateAuthorParams::lastName -> "Last name of the new writer"
            else -> default.property(property)
        }
    }

    override fun mustBeAtLeast(value: Number) = "Oops, we need at least $value, man!"

}


object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(External)

object PublishBook : InstanceEventNoParameters<Book>(External)

object UpdateBook : InstanceEventWithParameters<Book, Book>(External)

object DeleteBook : InstanceEventNoParameters<Book>(External)

data class CreateBookParams(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>> = emptySet(),
    val previousBooksInSameSeries: List<ModelID<Book>> = emptyList(),
    val tags: Set<BookTag> = emptySet(),
    val averageScore: AverageScore,
    val readingTime: ReadingTime,
    val genre: BookGenreContainer = BookGenreContainer(BookGenre.Fiction),
    val notes: BookNotes? = null,
    val cover: BookCover? = null,
    val thumbnail: BookThumbnail? = null,
    val chapters: List<BookChapter> = emptyList(),
)

class AverageScore(value: Float) : FloatContainer(value) {
    override val min: Float = 0f
    override val max: Float = Float.MAX_VALUE
}

// A model whose blob property declares what it will accept, so that the checks can be exercised from a command.
data class Painting(val title: PaintingTitle, val image: PaintingImage)

enum class PaintingStates { Hung }

class PaintingTitle(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

/** Images only, published to the world, and small. */
class PaintingImage(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept: Set<String> = setOf("image/png", "image/jpeg")
    override val maxSize: Long = 1000
    override val visibility: AttachedDataVisibility = AttachedDataVisibility.Public
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::noPreAttachProcessing)
}

data class CreatePaintingParams(
    val title: PaintingTitle,
    val image: PaintingImage,
    // Makes FlakyDocument a declaration the specification knows, so its processing job can find it.
    val document: dev.klerkframework.klerk.attacheddata.FlakyDocument? = null,
)

object CreatePainting : VoidEventWithParameters<Painting, CreatePaintingParams>(External)

object DeletePainting : InstanceEventNoParameters<Painting>(External)

fun paintingStateMachine(): StateMachine<Painting, PaintingStates, Ctx, Views> = stateMachine {
    event(CreatePainting) {}
    event(DeletePainting) {}
    voidState {
        onEvent(CreatePainting) { createModel(PaintingStates.Hung, ::newPainting) }
    }
    state(PaintingStates.Hung) {
        onEvent(DeletePainting) { delete() }
    }
}

private fun newPainting(args: VoidEventArgs<Painting, CreatePaintingParams, Ctx, Views>): Painting =
    Painting(args.command.params.title, args.command.params.image)

// Blob properties must be declared in an AttachedBlobContainer. These three accept anything, which is what the attached-data
// tests need; PaintingImage above is the one that declares real constraints.
class AuthorPicture(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::noPreAttachProcessing)
}

class BookCover(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::noPreAttachProcessing)
}

class BookThumbnail(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::noPreAttachProcessing)
}

// String properties must likewise be declared in an AttachedStringContainer. These accept text/plain, which is all
// the attached-data tests write.
class BookNotes(id: AttachedStringID) : AttachedStringContainer(id) {
    override val accept: Set<String> = setOf("text/plain")
}

class BookChapter(id: AttachedStringID) : AttachedStringContainer(id) {
    override val accept: Set<String> = setOf("text/plain")
}

// A model whose string property declares what it will accept, so that the checks can be exercised from a command —
// the string-kind counterpart of Painting/PaintingImage above.
data class Note(val title: NoteTitle, val body: NoteBody)

enum class NoteStates { Written }

class NoteTitle(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

/** Plain text only, and small. */
class NoteBody(id: AttachedStringID) : AttachedStringContainer(id) {
    override val accept: Set<String> = setOf("text/plain")
    override val maxSize: Long = 20
}

data class CreateNoteParams(val title: NoteTitle, val body: NoteBody)

object CreateNote : VoidEventWithParameters<Note, CreateNoteParams>(External)

object DeleteNote : InstanceEventNoParameters<Note>(External)

fun noteStateMachine(): StateMachine<Note, NoteStates, Ctx, Views> = stateMachine {
    event(CreateNote) {}
    event(DeleteNote) {}
    voidState {
        onEvent(CreateNote) { createModel(NoteStates.Written, ::newNote) }
    }
    state(NoteStates.Written) {
        onEvent(DeleteNote) { delete() }
    }
}

private fun newNote(args: VoidEventArgs<Note, CreateNoteParams, Ctx, Views>): Note =
    Note(args.command.params.title, args.command.params.body)

// Declared the old way, on purpose: the specification must refuse it. Never registered in createConfig.
data class Sketch(val drawing: AttachedBlobID)

enum class SketchStates { Drawn }

object CreateSketch : VoidEventWithParameters<Sketch, Sketch>(External)

fun sketchStateMachine(): StateMachine<Sketch, SketchStates, Ctx, Views> = stateMachine {
    event(CreateSketch) {}
    voidState {
        onEvent(CreateSketch) { createModel(SketchStates.Drawn, ::newSketch) }
    }
    state(SketchStates.Drawn) {}
}

private fun newSketch(args: VoidEventArgs<Sketch, Sketch, Ctx, Views>): Sketch = args.command.params

// Declared the old way, on purpose: the specification must refuse it. Never registered in createConfig.
data class Scribble(val text: AttachedStringID)

enum class ScribbleStates { Written }

object CreateScribble : VoidEventWithParameters<Scribble, Scribble>(External)

fun scribbleStateMachine(): StateMachine<Scribble, ScribbleStates, Ctx, Views> = stateMachine {
    event(CreateScribble) {}
    voidState {
        onEvent(CreateScribble) { createModel(ScribbleStates.Written, ::newScribble) }
    }
    state(ScribbleStates.Written) {}
}

private fun newScribble(args: VoidEventArgs<Scribble, Scribble, Ctx, Views>): Scribble = args.command.params

// A container that declares no step at all, which the specification must refuse. Never registered in createConfig.
class DoodleImage(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val preAttachSteps: List<BlobPreAttachStep> = emptyList()
}

data class Doodle(val drawing: DoodleImage)

enum class DoodleStates { Drawn }

object CreateDoodle : VoidEventWithParameters<Doodle, Doodle>(External)

fun doodleStateMachine(): StateMachine<Doodle, DoodleStates, Ctx, Views> = stateMachine {
    event(CreateDoodle) {}
    voidState {
        onEvent(CreateDoodle) { createModel(DoodleStates.Drawn, ::newDoodle) }
    }
    state(DoodleStates.Drawn) {}
}

private fun newDoodle(args: VoidEventArgs<Doodle, Doodle, Ctx, Views>): Doodle = args.command.params

// A blob whose bytes are checked, not just its metadata: the CSV must have the columns the application expects.
data class Inventory(val name: InventoryName, val rows: InventoryCsv)

enum class InventoryStates { Counted }

class InventoryName(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

class InventoryCsv(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept: Set<String> = setOf("text/plain")
    override val preAttachSteps: List<BlobPreAttachStep> = listOf(::checkTheHeader, ::normaliseLineEndings)
}

/** A step that only looks. */
suspend fun checkTheHeader(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
    val header = args.value.bufferedReader().buffered().readLine()
    return if (header == "name,quantity") BlobPreAttachStepResult.Pass
    else BlobPreAttachStepResult.Reject("the first line must be 'name,quantity', not '$header'")
}

/** A step that rewrites the bytes, standing in for something like a Content Disarm & Reconstruct pass. */
suspend fun normaliseLineEndings(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
    val text = args.value.readBytes().decodeToString()
    return if (!text.contains("\r\n")) BlobPreAttachStepResult.Pass
    else BlobPreAttachStepResult.Replace(text.replace("\r\n", "\n").byteInputStream())
}

data class CreateInventoryParams(
    val name: InventoryName,
    val rows: InventoryCsv,
    // Makes these declarations the specification knows, so that values can be prepared for them.
    val draft: dev.klerkframework.klerk.attacheddata.LenientCsv? = null,
    val tally: dev.klerkframework.klerk.attacheddata.CountedTwice? = null,
)

object CreateInventory : VoidEventWithParameters<Inventory, CreateInventoryParams>(External)

fun inventoryStateMachine(): StateMachine<Inventory, InventoryStates, Ctx, Views> = stateMachine {
    event(CreateInventory) {}
    voidState {
        onEvent(CreateInventory) { createModel(InventoryStates.Counted, ::newInventory) }
    }
    state(InventoryStates.Counted) {}
}

private fun newInventory(args: VoidEventArgs<Inventory, CreateInventoryParams, Ctx, Views>): Inventory =
    Inventory(args.command.params.name, args.command.params.rows)

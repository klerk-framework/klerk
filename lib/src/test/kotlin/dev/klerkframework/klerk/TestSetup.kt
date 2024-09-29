package dev.klerkframework.klerk


import dev.klerkframework.klerk.AlwaysFalseDecisions.Something
import dev.klerkframework.klerk.AuthorStates.*
import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.NegativeAuthorization.Pass
import dev.klerkframework.klerk.Validity.Invalid
import dev.klerkframework.klerk.Validity.Valid
import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.actions.JobContext
import dev.klerkframework.klerk.actions.JobId
import dev.klerkframework.klerk.actions.JobResult
import dev.klerkframework.klerk.collection.AllModelCollection
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.misc.AlgorithmBuilder
import dev.klerkframework.klerk.misc.Decision
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.misc.ShouldSendNotificationAlgorithm
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.storage.SqlPersistence
import dev.klerkframework.plugins.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.sql.DriverManager
import kotlin.reflect.KProperty1
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

var onEnterAmateurStateActionCallback: (() -> Unit)? = null
var onEnterImprovingStateActionCallback: (() -> Unit)? = null

fun createConfig(collections: MyCollections, storage: Persistence = RamStorage()): Config<Context, MyCollections> {
    return ConfigBuilder<Context, MyCollections>(collections).build {
        persistence(storage)
        managedModels {
            model(Book::class, bookStateMachine(collections.authors.all, collections), collections.books)
            model(Author::class, authorStateMachine(collections), collections.authors)
            //model(Shop::class, cudStateMachine(Shop::class), views.shops)
        }
        authorization {
            apply(insecureAllowEverything())
/*            readModels {
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

 */
        }
        contextProvider(::myContextProvider)
    }.withPlugin(
        PostmarkEmailService(
            System.getenv("POSTMARK_API_KEY") ?: "dummyKey",
            PostmarkEmailWebhooksAuth("abc", "123"),
            serverID = 13744766,
            defaultFromAddress = BasicEmail.EmailAndName("linus.tornkrantz@bostadskontrollen.se", "Linus Törnkrantz"),
        ),
    ).withPlugin(MagicLinkAuthentication("http://localhost:8080/login"))
}

fun myContextProvider(actorIdentity: dev.klerkframework.klerk.ActorIdentity): Context {
    return Context(
        actor = actorIdentity,

        )
}

fun cannotReadAstrid(args: ArgsForPropertyAuth<Context, MyCollections>): dev.klerkframework.klerk.NegativeAuthorization {
    return if (args.property is FirstName && args.property.valueWithoutAuthorization == "Astrid") Deny else Pass
}

fun canReadAllProperties(args: ArgsForPropertyAuth<Context, MyCollections>): dev.klerkframework.klerk.PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}

fun unauthenticatedCannotReadAstrid(args: ArgModelContextReader<Context, MyCollections>): dev.klerkframework.klerk.NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is dev.klerkframework.klerk.Unauthenticated) Deny else Pass
}

fun `Everybody can do everything`(argCommandContextReader: ArgCommandContextReader<*, Context, MyCollections>): dev.klerkframework.klerk.PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}


fun `Everybody can read event log`(args: ArgContextReader<Context, MyCollections>): dev.klerkframework.klerk.PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}

fun `Everybody can read`(args: ArgModelContextReader<Context, MyCollections>): dev.klerkframework.klerk.PositiveAuthorization {
    return dev.klerkframework.klerk.PositiveAuthorization.Allow
}

fun pelleCannotReadOnMornings(
    args: ArgModelContextReader<Context, MyCollections>
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

class BookCollections : ModelCollections<Book, Context>() {

    fun childrensBooks(): List<ModelID<Book>> {
        return emptyList()
    }
}

class AuthorCollections<V>(val allBooks: AllModelCollection<Book, Context>) : ModelCollections<Author, Context>() {

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
) {
    override fun toString() = title.value
}

data class Author(val firstName: FirstName, val lastName: LastName, val address: Address) : Validatable {
    override fun validators(): Set<() -> Validity> = setOf(::noAuthorCanBeNamedJamesClavell)

    private fun noAuthorCanBeNamedJamesClavell(): Validity {
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
    val age: EvenIntContainer = EvenIntContainer(68),
    //  val address: Address,
    val secretToken: SecretPasscode,
    val favouriteColleague: ModelID<Author>? = null
) : Validatable {

    override fun validators(): Set<() -> Validity> = setOf(::augustStrindbergCannotHaveCertainPhoneNumber)

    @HumanReadable("Testar att namnge en funktion")
    private fun augustStrindbergCannotHaveCertainPhoneNumber(): Validity {
        return if (firstName.value == "August" && lastName.value == "Strindberg" && phone.value == "123456") Invalid() else Valid
    }
}

data class ChangeNameParams(val updatedFirstName: FirstName, val updatedLastName: LastName)

fun authorStateMachine(collections: MyCollections): StateMachine<Author, AuthorStates, Context, MyCollections> =

    stateMachine {

        event(CreateAuthor) {
            validateContext(::preventUnauthenticated)
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
                action(::onEnterAmateurStateAction)
            }

            onEvent(UpdateAuthor) {
                update(::updateAuthor)
            }

            onEvent(DeleteAuthor) {
                delete()
            }

            onEvent(DeleteAuthorAndBooks) {
                createCommands(::eventsToDeleteAuthorAndBooks)
            }

            onEvent(ImproveAuthor) {
                action(::showNotification, onCondition = ShouldSendNotificationAlgorithm::execute)
                transitionTo(Improving)
            }

            onEvent(ChangeName) {
                update(::changeNameOfAuthor)
                job(::notifyBookStores)
            }

            after(30.seconds) {
                transitionTo(Established)
                update(::someUpdate)
                action(::sayHello)
            }

        }

        state(Improving) {
            onEnter {
                action(::onEnterImprovingStateAction)
                transitionWhen(
                    linkedMapOf(
                        ::isAnImpostor to Amateur,
                        ::hasTalent to Established,
                    )
                )
                job(::aJob)
            }

        }

        state(Established) {

            atTime(::later) {
                delete()
            }

            onEvent(ImproveAuthor) {
                sendEmail(::notifyPublisher)
                transitionWhen(
                    linkedMapOf(
                        ShouldSendNotificationAlgorithm::execute to Improving
                    )
                )
            }

            onEvent(DeleteAuthor) {
                delete()
            }
        }

    }

fun notifyPublisher(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>) =
    PostmarkEmail(
        From = BasicEmail.EmailAndName("linus.tornkrantz@bostadskontrollen.se", "Linus Törnkrantz").toStringWithoutDots(),
        To = BasicEmail.EmailAndName("linus.tornkrantz@bostadskontrollen.se", "Linus Törnkrantz").toStringWithoutDots(),
        // replyTo = EmailAndName("linus.tornkrantz@bostadskontrollen.se", "Linus Törnkrantz"),
        Subject = "Author has improved",
        TextBody = null,
        HtmlBody = "<b>Hello</b> this is html",
        )

fun someUpdate(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Author {
    return args.model.props.copy(lastName = LastName("efter"))
}

fun onExitUpdate(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Author {
    return args.model.props.copy(FirstName("Changed name after exit"))
}

fun sayHello(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    println("Hello!")
}

fun later(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Instant {
    return args.time.plus(30.seconds)
}

fun hasTalent(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Boolean = true
fun isAnImpostor(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Boolean = false

fun aJob(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): List<Job<Context, MyCollections>> {
    return listOf(MyJob)
}


fun onEnterImprovingStateAction(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    if (onEnterImprovingStateActionCallback != null) {
        onEnterImprovingStateActionCallback!!()
    }
}


fun showNotification(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>) {
    println("It was decided that we should show a notification")
}

fun onEnterAmateurStateAction(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    if (onEnterAmateurStateActionCallback != null) {
        onEnterAmateurStateActionCallback!!()
    }
}


fun notifyBookStores(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyCollections>): List<Job<Context, MyCollections>> {
    class MyJob : Job<Context, MyCollections> {
        override val id = 123L

        override suspend fun run(jobContext: JobContext<Context, MyCollections>): JobResult {
            println("Job started")
            return JobResult.Success
        }
    }

    return listOf(MyJob())
}

fun changeNameOfAuthor(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyCollections>): Author {
    return args.model.props.copy(
        firstName = args.command.params.updatedFirstName,
        lastName = args.command.params.updatedLastName
    )
}

fun eventsToDeleteAuthorAndBooks(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>): List<Command<Any, Any>> {
    args.reader.apply {
        val result: MutableList<Command<Any, Any>> = mutableListOf()
        val books = getRelated(Book::class, requireNotNull(args.model.id))

        @Suppress("UNCHECKED_CAST")
        books.map { Command(event = DeleteBook, model = it.id, null) }
            .forEach { result.add(it as Command<Any, Any>) }

        @Suppress("UNCHECKED_CAST")
        result.add(
            Command(event = DeleteAuthor, model = requireNotNull(args.model.id), null)
                    as Command<Any, Any>
        )

        return result
    }
}

fun newAuthor(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Author {
    val params = args.command.params
    return Author(
        firstName = params.firstName,
        lastName = params.lastName,
        address = Address(Street("kjh"))
    )
}

fun newAuthor2(args: ArgForVoidEvent<Author, Nothing?, Context, MyCollections>): Author {
    return Author(FirstName("Auto"), LastName("Created"), Address(Street("Somewhere")))
}


fun updateAuthor(args: ArgForInstanceEvent<Author, Author, Context, MyCollections>): Author {
    return args.command.params
}


fun onlyAuthenticationIdentityCanCreateDaniel(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    return if (args.command.params.firstName.value == "Daniel" && args.context.actor != dev.klerkframework.klerk.AuthenticationIdentity) Invalid() else Valid
}

fun cannotHaveAnAwfulName(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    return if (args.command.params.firstName.value == "Mike" && args.command.params.lastName.value == "Litoris") Invalid() else Valid
}

fun secretTokenShouldBeZeroIfNameStartsWithM(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    return if (args.command.params.firstName.value.startsWith("M") && args.command.params.secretToken.value != 0L) Invalid() else Valid
}

fun preventUnauthenticated(context: Context): Validity {
    return if (context.actor == dev.klerkframework.klerk.Unauthenticated) Invalid() else Valid
}

fun onlyAllowAuthorNameAstridIfThereIsNoRowling(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    args.reader.apply {
        if (args.command.params.firstName.value != "Astrid") {
            return Valid
        }
        val rowling = firstOrNull(data.authors.all) { it.props.firstName.value == "Rowling" }
        return if (rowling == null) Valid else Invalid()
    }
}

fun newBook(args: ArgForVoidEvent<Book, CreateBookParams, Context, MyCollections>): Book {
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
        readingTime = ReadingTime(23.hours),
        publishedAt = null,
        releasePartyPosition = ReleasePartyPosition(GeoPosition(latitude = 1.234, longitude = 3.456))
    )
}


enum class AuthorStates {
    Amateur,
    Improving,
    Established,
}

data class MyCollections(
    val books: BookCollections,
    val authors: AuthorCollections<MyCollections>
) //, val shops: ModelView<Shop, Context>)

suspend fun createAuthorJKRowling(klerk: Klerk<Context, MyCollections>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            event = CreateAuthor,
            model = null,
            params = CreateAuthorParams(
                firstName = FirstName("J.K"),
                lastName = LastName("Rowling"),
                phone = PhoneNumber("+46123456"),
                secretToken = SecretPasscode(234234902359245345),
                //       address = Address(Street("Storgatan"))
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    return requireNotNull(result.orThrow().primaryModel)
}

suspend fun createAuthorAstrid(klerk: Klerk<Context, MyCollections>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            event = CreateAuthor,
            model = null,
            params = createAstridParameters,
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    @Suppress("UNCHECKED_CAST")
    return result.orThrow().createdModels.single() as ModelID<Author>
}

val createAstridParameters = CreateAuthorParams(
    firstName = FirstName("Astrid"),
    lastName = LastName("Lindgren"),
    phone = PhoneNumber("+4699999"),
    secretToken = SecretPasscode(234123515123434),
)

suspend fun createBookHarryPotter1(klerk: Klerk<Context, MyCollections>, author: ModelID<Author>): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Philosopher's Stone"),
                author = author,
                coAuthors = emptySet(),
                previousBooksInSameSeries = emptyList(),
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f)
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple())
    )
    return requireNotNull(result.orThrow().primaryModel)
}

suspend fun createBookHarryPotter2(
    klerk: Klerk<Context, MyCollections>,
    author: ModelID<Author>,
    previousBooksInSameSeries: List<ModelID<Book>>,
    coAuthors: Set<ModelID<Author>>
): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Chamber of Secrets"),
                author = author,
                coAuthors = coAuthors,
                previousBooksInSameSeries = previousBooksInSameSeries,
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f)
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    return requireNotNull(result.orThrow().primaryModel)
}

class PhoneNumber(value: String) : StringContainer(value) {
    override val minLength = 3
    override val maxLength = 10
    override val maxLines: Int = 1
}

class EvenIntContainer(value: Int) : IntContainer(value) {
    override val min: Int = Int.MIN_VALUE
    override val max: Int = Int.MAX_VALUE

    override val validators = setOf(::mustBeEven)

    fun mustBeEven(): InvalidParametersProblem? {
        if (valueWithoutAuthorization % 2 == 0) {
            return null
        }
        return InvalidParametersProblem("Must be even")
    }

}

class FirstName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
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

    private fun `title must be catchy`(): InvalidParametersProblem? {
        return null
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

class BookWrittenAt(value: Instant) : InstantContainer(value) {

}

class ReadingTime(value: Duration) : DurationContainer(value)

data class Address(val street: Street)

class Street(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

fun addStandardTestConfiguration(auth: Boolean = true): ConfigBuilder<Context, MyCollections>.() -> Unit = {
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
        val sqliteMemoryPath = "jdbc:sqlite:file:test?mode=memory&cache=shared"
        val ds = SQLiteDataSource()
        ds.url = sqliteMemoryPath
        keepAlive = DriverManager.getConnection(sqliteMemoryPath)
        return SqlPersistence(ds)
    }
}

object CreateAuthor :
    VoidEventWithParameters<Author, CreateAuthorParams>(Author::class, true, CreateAuthorParams::class)

object UpdateAuthor : InstanceEventWithParameters<Author, Author>(Author::class, true, Author::class) {

}

object DeleteAuthor : InstanceEventNoParameters<Author>(Author::class, true)

object DeleteAuthorAndBooks : InstanceEventNoParameters<Author>(Author::class, true)

object ImproveAuthor : InstanceEventNoParameters<Author>(Author::class, true)

object ChangeName : InstanceEventWithParameters<Author, ChangeNameParams>(Author::class, true, ChangeNameParams::class)

sealed class AlwaysFalseDecisions(
    override val name: String,
    override val function: (ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>) -> Boolean
) : Decision<Boolean, ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>> {
    data object Something : AlwaysFalseDecisions("This will always be false", ::alwaysFalse)

}

fun alwaysFalse(args: ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>): Boolean {
    return false
}


object AlwaysFalseAlgorithm :
    FlowChartAlgorithm<ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>, Boolean>("Always false") {

    override fun configure(): AlgorithmBuilder<ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>, Boolean>.() -> Unit =
        {
            start(Something)
            booleanNode(Something) {
                on(true, terminateWith = false)
                on(false, terminateWith = false)
            }
        }
}

data class Context(
    override val actor: dev.klerkframework.klerk.ActorIdentity,
    override val auditExtra: String? = null,
    override val time: Instant = Clock.System.now(),
    override val translator: Translator = DefaultTranslator(),
    val user: Model<User>? = null,
    val purpose: String = "Pass the butter",
) : KlerkContext {

    companion object {
        fun fromUser(user: Model<User>): Context {
            return Context(dev.klerkframework.klerk.ModelIdentity(user), user = user)
        }

        fun unauthenticated(): Context = Context(dev.klerkframework.klerk.Unauthenticated)

        fun authenticationIdentity(): Context = Context(dev.klerkframework.klerk.AuthenticationIdentity)

        fun system(): Context = Context(dev.klerkframework.klerk.SystemIdentity)

        fun swedishUnauthenticated(): Context = Context(dev.klerkframework.klerk.Unauthenticated, translator = SwedishTranslation())
    }

}

data class User(val name: FirstName)

object AnEventWithoutParameters : VoidEventNoParameters<Author>(Author::class, true)

object MyJob : Job<Context, MyCollections> {
    override val id: JobId
        get() = 999

    override suspend fun run(jobContext: JobContext<Context, MyCollections>): JobResult {
        println("Did MyJob")
        return JobResult.Success
    }

}

class SwedishTranslation : EnglishTranslation() {

    override fun property(property: KProperty1<*, *>): String {
        return when (property) {
            CreateAuthorParams::firstName -> "Förnamn på den nya författaren"
            else -> super.property(property)
        }
    }

    override fun event(event: EventReference): String {
        return when (event) {
            PublishBook.id -> "Publicera bok"
            CreateAuthor.id -> "Ny författare"
            else -> super.event(event)
        }
    }

}


open class EnglishTranslation : DefaultTranslator() {
    override fun property(property: KProperty1<*, *>): String {
        return when (property) {
            CreateAuthorParams::firstName -> "First name of the new writer"
            CreateAuthorParams::lastName -> "Last name of the new writer"
            else -> super.property(property)
        }
    }
}

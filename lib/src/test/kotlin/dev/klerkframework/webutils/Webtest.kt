package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.misc.ShouldSendNotificationAlgorithm
import dev.klerkframework.klerk.read.ModelModification.*
import dev.klerkframework.klerk.storage.SqlPersistence
import dev.klerkframework.plugins.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.html.*
import org.sqlite.SQLiteDataSource
import java.io.File
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType

internal var lowCodeMain: LowCodeMain<Context, MyCollections>? = null

data class AuthorAndRecentPucl(val author: Author, val recentBooks: List<Book>)

fun main() {
    System.setProperty("DEVELOPMENT_MODE", "true")
    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))

    val dbFilePath = "/tmp/klerktest.sqlite"
    //File(dbFilePath).delete()
    val ds = SQLiteDataSource()
    ds.url = "jdbc:sqlite:$dbFilePath"
    val persistence = SqlPersistence(ds)
    //val persistence = RamStorage()
    val klerk = Klerk.create(createConfig(collections, persistence))
    runBlocking {

        launch {
            println("subscribing to Klerk logs")
            klerk.log.subscribe(Context.swedishUnauthenticated()).collect { entry ->
                println("got entry: ${entry.getHeading()}")
            }
        }

        println("...")
        klerk.meta.start()

        if (klerk.meta.modelsCount == 0) {
            //val rowling = createAuthorJKRowling(klerk)
            //val book = createBookHarryPotter1(klerk, rowling)

            generateSampleData(3, 1, klerk)
            //data.makeSnapshot()
        }

        lowCodeMain = LowCodeMain(
            klerk,
            LowCodeConfig(
                "/admin",
                ::anyUser,
                // cssPath = "https://unpkg.com/sakura.css/css/sakura.css",
                cssPath = "https://unpkg.com/almond.css@latest/dist/almond.min.css",
                showOptionalParameters = ::showOptionalParameters,
                knownAlgorithms = setOf(ShouldSendNotificationAlgorithm)
            )
        )

        val embeddedServer = io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            configureRouting(klerk)
            //        configureSecurity()
            //      configureHTTP()
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down")
            embeddedServer.stop()
            klerk.meta.stop()
            println("Shutdown complete")
        })

        embeddedServer.start(wait = false)

        klerk.models.subscribe(Context.system(), null).collect {
            when (it) {
                is Created -> println("${it.id} was created")
                is PropsUpdated -> println("${it.id} had props updated")
                is Transitioned -> println("${it.id} transitioned")
                is Deleted -> println("${it.id} was deleted")
            }
        }

    }

}

suspend fun generateSampleData(numberOfAuthors: Int, booksPerAuthor: Int, klerk: Klerk<Context, MyCollections>) {

    val startTime = kotlinx.datetime.Clock.System.now()
    val firstNames = setOf("Anna", "Bertil", "Janne", "Filip")
    val lastNames = setOf("Andersson", "Svensson", "Törnkrantz")
    val cities = setOf("Malmö, Göteborg, Falun", "Stockholm")

    for (i in 1..numberOfAuthors) {
        val result = klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName(firstNames.random()),
                    lastName = LastName(i.toString()),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(23290409),
                    //address = Address(Street("Lugna gatan"))
                ),
            ),
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        )

        if (i % 10 == 0) {
            klerk.handle(
                Command(
                    event = ImproveAuthor,
                    model = result.orThrow().primaryModel,
                    params = null
                ),
                context = Context.system(),
                ProcessingOptions(
                    CommandToken.simple()
                )
            )
        }

        val authorRef = requireNotNull(result.orThrow().primaryModel)
        println("Author: $authorRef")

        val author = klerk.read(Context.system()) { get(authorRef) }

        for (j in 1..booksPerAuthor) {
            klerk.handle(
                Command(
                    event = CreateBook,
                    model = null,
                    params = CreateBookParams(
                        title = BookTitle("Book $j"),
                        author = authorRef,
                        coAuthors = emptySet(),
                        previousBooksInSameSeries = emptyList(),
                        tags = setOf(BookTag("Fiction"), BookTag("Children")),
                        averageScore = AverageScore(0f)
                    ),
                ),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )
        }
    }
    val seconds = startTime.minus(kotlinx.datetime.Clock.System.now()).inWholeSeconds
    val eventsPerSecond = if (seconds > 0) (numberOfAuthors * (booksPerAuthor + 1)) / seconds else "?"
    logger.info { "Created $numberOfAuthors authors in $seconds s ($eventsPerSecond events/s)" }
}

fun Application.configureRouting(klerk: Klerk<Context, MyCollections>) {

    routing {

        get("/") { call.respondRedirect("/admin") }

        get("/startLogin") {
            when (val result = klerk.handle(
                Command(
                    event = StartMagicLinkAuthentication,
                    model = null,
                    params = StartMagicLinkAuthenticationParams(
                        email = PluginMagicLinkAuthenticationEmailAddress("linus.tornkrantz@bostadskontrollen.se"),
                        user = PluginMagicLinkAuthenticationUserRef(123),
                    )
                ),
                context = anyUser(call),
                options = ProcessingOptions(CommandToken.simple())
            )) {
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> call.respond("Failed: ${result.problem}")
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> call.respond("Success")
            }

        }

        get("/login") {
            val key = requireNotNull(call.request.queryParameters["key"])
            call.respondHtml {
                head { }
                body {
                    script {
                        unsafe {
                            +MagicLinkAuthentication.generateScript("/login", key)
                        }
                    }
                    noScript {
                        h3 { +"Click to log in" }
                        form("/login", method = FormMethod.post) {
                            hiddenInput {
                                name = "key"
                                value = key
                            }
                            submitInput {
                                value = "Log in"
                            }
                        }
                    }
                }
            }
        }

        post("/login") {
            val key = requireNotNull(call.receiveParameters().get("key"))
            val loginResult =
                klerk.config.plugins.filterIsInstance<MagicLinkAuthentication<Context, MyCollections>>().single()
                    .tryLogin(key, Context(actor = dev.klerkframework.klerk.AuthenticationIdentity))
            when (loginResult) {
                LoginResult.Failure -> call.respond("login Failed")
                is LoginResult.Success -> call.respond("Authenticated user: '${loginResult.userRef.int}'")
            }
        }

        apply(lowCodeMain!!.registerRoutes())
    }
}


fun showOptionalParameters(eventReference: EventReference): Boolean {
    return false
}

suspend fun anyUser(call: ApplicationCall): Context = Context.swedishUnauthenticated()


fun authorizeAllDatatypes(instance: Any) {
    instance::class.memberProperties.forEach {
        if (it.returnType.isSubtypeOf(DataContainer::class.starProjectedType)) {
            (it.getter.call(instance) as DataContainer<*>).initAuthorization(true)
        }
    }
}

class MyInstant(value: Instant) : InstantContainer(value)

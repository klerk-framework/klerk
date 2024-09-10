package dev.klerkframework.webutils

import com.google.gson.Gson
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.storage.ModelCache
import dev.klerkframework.webutils.ParseResult.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import kotlin.reflect.full.memberProperties

fun main() {

    runBlocking {

        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val klerk = Klerk.create(createConfig(collections))
        klerk.meta.start()
        if (ModelCache.isEmpty()) {
            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)

            val formBuilder = EventFormTemplate(
                EventWithParameters(
                    CreateAuthor.id,
                    EventParameters(CreateAuthorParams::class)
                ), klerk, "/",
            ) {
                text(CreateAuthorParams::firstName)
                text(CreateAuthorParams::lastName)
                text(CreateAuthorParams::phone)
                number(CreateAuthorParams::age)
                populatedAfterSubmit(CreateAuthorParams::secretToken)
            }

            formBuilder.validate()

            io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                routing {
                    get("/") {
                        val p = CreateAuthorParams(
                            firstName = FirstName(""),
                            lastName = LastName(""),
                            phone = PhoneNumber("+46123456"),
                            secretToken = SecretPasscode(99999),
                            //address = Address(Street("Storgatan"))
                        )
                        authorizeAllDatatypes(p)

                        val context = Context.system()

                        val form2 = klerk.read(context) {
                            formBuilder.build(call, p, this, translator = context.translator)
                        }

                        call.respondHtml {
                            head {
                                styleLink("https://unpkg.com/sakura.css/css/sakura.css")
                            }
                            body {
                                h1 { +"With server validation" }
                                form2.render(this)
                            }
                        }
                    }

                    post("/") {
                        when (val result = formBuilder.parse(
                            call,
                            mapOf(CreateAuthorParams::secretToken to SecretPasscode(1)),
                        )) {
                            is Invalid -> EventFormTemplate.respondInvalid(result, call)
                            is DryRun -> respondDryRun<Author>(
                                result.params,
                                result.key,
                                CreateAuthor,
                                null,
                                call,
                                klerk
                            )

                            is Parsed -> call.respondHtml {
                                body {
                                    h1 { +"Success" }
                                    p { +result.params::class.qualifiedName.toString() }
                                    result.params::class.memberProperties.forEach {
                                        val value = it.getter.call(result.params) as DataContainer<*>
                                        value.initAuthorization(true)
                                        +"${it.name}: $value"
                                        br
                                    }
                                }
                            }
                        }

                    }
                }
                //        configureSecurity()
                //      configureHTTP()
            }.start(wait = true)
        }
    }
}

suspend fun <T : Any> respondDryRun(
    params: CreateAuthorParams,
    key: CommandToken,
    event: CreateAuthor,
    id: ModelID<Author>?,
    call: ApplicationCall,
    klerk: Klerk<Context, MyCollections>
) {
    val re = Command(
        event = event,
        params = params,
        model = id,
    )
    when (val result =
        klerk.handle(re, Context.system(), ProcessingOptions(key, dryRun = true))) {
        is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.UnprocessableEntity,
                text = Gson().toJson(
                    ValidationResponse(
                        fieldProblems = emptyMap(),
                        formProblems = emptyList(),
                        dryRunProblems = listOf(result.toString())
                    )
                )
            )
        }

        is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> call.respond(HttpStatusCode.OK)
    }
}

data class ValidationResponse(
    val fieldProblems: Map<String, String>,
    val formProblems: List<String>,
    val dryRunProblems: List<String>
)

private fun allowAll(dataContainer: DataContainer<*>, actorIdentity: dev.klerkframework.klerk.ActorIdentity): Boolean {
    return true
}

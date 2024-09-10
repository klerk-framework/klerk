package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.Validity.Valid
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.StringContainer
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
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability

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
            val harryPotter2 = createBookHarryPotter2(klerk, rowling, listOf(), setOf())

            val improveResult = klerk.handle(
                Command(ImproveAuthor, rowling, null), Context.system(), ProcessingOptions(
                    CommandToken.simple()
                )
            )
            println(improveResult)

            generateSampleData(50, 3, klerk)

            val eventParams = EventParameters(CreateAuthorParams::class)
            val template = EventFormTemplate(eventWithParameters = EventWithParameters(CreateAuthor.id, eventParams),
                klerk, "/noklerkvalidation"
            ) {
                text(CreateAuthorParams::phone)
                remaining(inHtmlDetails = "Remaining stuff")
            }

            template.validate()

            io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                routing {
                    get("/") {

                        val params = CreateAuthorParams(
                            firstName = FirstName("Janne"),
                            lastName = LastName("Svensson"),
                            phone = PhoneNumber("0987654321"),
                            age = EvenIntContainer(22),
                            secretToken = SecretPasscode(234)
                        )

                        authorizeAllDatatypes(params)

                        val context = Context.swedishUnauthenticated()

                        klerk.readSuspend(context) {
                            val form1 =
                                template.build(
                                    call,
                                    params,
                                    this,
                                    translator = context.translator,
                                    // referenceSelects = mapOf(TestParams::book to collections.books.all, TestParams::author to collections.authors.all),
                                    //enumSelects = mapOf(TestParams::anEnum to MyEnum.values())
                                )
                            call.respondHtml {
                                head {
                                    link(href = "https://unpkg.com/sakura.css/css/sakura.css", rel = "stylesheet")
                                }
                                body {
                                    h1 { +"No Klerk validation" }
                                    form1.render(this)
                                }
                            }
                        }
                    }

                    post("/noklerkvalidation") {

                        when (val result = template.parse(
                            call,
                            mapOf(TestParams::populatedLater to PhoneNumber("After post")),
                        )) {
                            is Invalid -> EventFormTemplate.respondInvalid(result, call)
                            is DryRun -> call.respond(HttpStatusCode.OK)
                            is Parsed -> {
                                call.respondHtml {
                                    body {
                                        h1 { +"Success" }
                                        p { +result.params::class.qualifiedName.toString() }
                                        result.params::class.memberProperties.forEach {
                                            if (it.returnType.isSubtypeOf(
                                                    DataContainer::class.starProjectedType.withNullability(
                                                        false
                                                    )
                                                ) ||
                                                it.returnType.isSubtypeOf(
                                                    DataContainer::class.starProjectedType.withNullability(
                                                        true
                                                    )
                                                )
                                            ) {
                                                val value = it.getter.call(result.params) as DataContainer<*>?
                                                value?.initAuthorization(true)
                                                +"${it.name}: $value"
                                                br
                                            } else if (it.returnType.isSubtypeOf(
                                                    ModelID::class.starProjectedType.withNullability(
                                                        false
                                                    )
                                                ) ||
                                                it.returnType.isSubtypeOf(
                                                    ModelID::class.starProjectedType.withNullability(
                                                        true
                                                    )
                                                )
                                            ) {
                                                +"${it.name}: ?"
                                                br
                                            } else if (it.returnType.isSubtypeOf(
                                                    Enum::class.starProjectedType.withNullability(
                                                        false
                                                    )
                                                ) ||
                                                it.returnType.isSubtypeOf(
                                                    Enum::class.starProjectedType.withNullability(
                                                        true
                                                    )
                                                )
                                            ) {
                                                +"${it.name}: ${it.getter.call(result.params)}"
                                                br
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.start(wait = true)
        }
    }
}

fun myLabelProvider(elementData: UIElementData): String? {
    return if (elementData.propertyName == "aLong") "this is my label: ${elementData.propertyName}" else null
}

data class TestParams(
    val aString: FirstName,
    val aPhone: PhoneNumber,
    val anEvenInt: EvenIntContainer = EvenIntContainer(44),
    val aBoolean: IsActive,
    val aNullableString: FirstName?,
    val aLong: SecretPasscode,
    val email: Email,
    val populatedLater: PhoneNumber,
    val book: ModelID<Book>?,
    val author: ModelID<Author>,
    val aRemainingString: FirstName,
) {

    fun phoneMustEndWith7(): Validity {
        return if (aPhone.value.endsWith("7")) Valid else Validity.Invalid()
    }

    fun aNullableStringMustBeNull(): Validity {
        return if (!aBoolean.value && aNullableString != null) Validity.Invalid(fieldMustBeNull = this::aNullableString) else Valid
    }

    fun aNullableStringMustNotBeNull(): Validity {
        return if (aBoolean.value && aNullableString == null) Validity.Invalid(fieldMustNotBeNull = this::aNullableString) else Valid
    }

}

private fun allowAll(dataContainer: DataContainer<*>, actorIdentity: dev.klerkframework.klerk.ActorIdentity): Boolean {
    return true
}

class Email(value: String) : StringContainer(value) {
    override val regexPattern = emailRegexRFC5322
    override val minLength = 3
    override val maxLength = 50
    override val maxLines = 1
}

val emailRegexRFC5322 =
    """(?:[a-z0-9!#${'$'}%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#${'$'}%&'*+/=?^_`{|}~-]+)*|"(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21\x23-\x5b\x5d-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])*")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21-\x5a\x53-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])+)\])"""

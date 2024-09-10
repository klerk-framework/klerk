package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.ReflectedModel
import io.ktor.http.*

import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlin.reflect.KClass

internal class LowCodeItemDetails<T : Any, C : KlerkContext, V>(
    private val kClass: KClass<out Any>,
    private val config: LowCodeConfig<C>,
    private val modelPathPart: String,
    private val humanName: String,
    private val klerk: Klerk<C, V>,
    private val auditPath: String
) {

    private lateinit var basePath: String

    fun initView(basePath: String) {
        this.basePath = basePath
    }

    fun registerRoutes(): Routing.() -> Unit = {
        get("${basePath}/$modelPathPart/items/{id}") {
            renderModel(call)
        }

    }

    private fun renderModelDetails(
        reflectedModel: ReflectedModel<Any>,
        eventReferences: Set<EventReference>,
        model: Model<Any>,
        context: C
    ): DIV.() -> Unit = {

        h3 { +"Details" }
        table {
            tbody {
                reflectedModel.getMeta().forEach { property ->
                    tr {
                        td { apply(property.renderNameNonBreakingHtml()) }
                        td {
                            property.description()?.let { title = it }
                            +property.toString()
                        }
                    }
                }
            }
        }

        apply(renderProperties(model.props))


        p { a(href = "$auditPath?model=${reflectedModel.id}") { button { +"History" } } }

        h3 { +"Commands" }

        eventReferences.forEach { event ->
            val buttonTargets =
                ButtonTargets(back = "/", model = "${config.basePath}/$modelPathPart/items/{id}", error = "/")
            p { apply(LowCodeCreateEvent.renderButton(event, klerk, reflectedModel.id, config, buttonTargets, context)) }
        }

        if (config.customAfterEventButtonsOnDetailView != null) {
            apply(config.customAfterEventButtonsOnDetailView.invoke(kClass, reflectedModel.original))
        }

        reflectedModel.referencesPretty().forEach { relatedList ->
            details {
                summary { +relatedList.key }
                relatedList.value.forEach {
                    +it.toString()
                }
            }
        }

        val referencesToThis = reflectedModel.referencesToThis()
        if (referencesToThis?.isNotEmpty() == true) {
            details {
                summary { +"Relations to this" }
                table {
                    tbody {
                        referencesToThis.forEach {
                            tr {
                                td { +"$it (id: ${it.id})" }
                            }
                        }
                    }
                }
            }
        }

    }

    private fun renderProperties(props: Any): DIV.() -> Unit = {
        // should change to YAML or HOCON ?
        val jsonPretty = klerk.config.gson.newBuilder().setPrettyPrinting().serializeNulls().create().toJson(props)
        textArea {
            disabled = true
            rows = jsonPretty.lines().size.toString()
            +jsonPretty
        }
    }


    private suspend fun renderModel(call: ApplicationCall) {
        val context = config.contextProvider(call)
        val id = ModelID.from<Any>(call.parameters["id"]!!)
        val (reflectedModelPopulated, model) = klerk.read(context) {
            val model = get(id)
            val reflectedModel = ReflectedModel(model)
            apply(reflectedModel.populateRelations())
            Pair(reflectedModel, model)
        }

        val events = klerk.read(context) { getPossibleEvents(id) }

        call.respondHtml {
            apply(lowCodeHtmlHead(config))
            body {
                apply(navMenu(basePath, modelPathPart, humanName))
                div {
                    apply(renderModelDetails(reflectedModelPopulated, events, model, context))
                }
            }
        }
    }

}

internal fun EventReference.urlEncode(): String = id().encodeURLPathPart()

internal fun EventReference.Companion.urlDecode(encoded: String): EventReference = from(encoded.decodeURLPart())

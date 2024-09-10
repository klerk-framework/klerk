package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.*
import dev.klerkframework.klerk.misc.ReflectedModel
import dev.klerkframework.klerk.misc.ReflectedProperty
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.misc.dateFormatter
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.VoidState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import java.net.URLDecoder
import java.nio.charset.Charset
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties


internal const val NON_BREAKING_SPACE = "&nbsp;"
internal const val NON_BREAKING_HYPHEN = "&#8209;"

internal class LowCodeList<T : Any, C : KlerkContext, V>(
    private val kClass: KClass<out Any>,
    private val config: LowCodeConfig<C>,
    private val createCommandPages: List<LowCodeCreateEvent<C, V>>,
    private val modelPathPart: String,
    val pathToList: String,
    val humanName: String,
    private val klerk: Klerk<C, V>
) {
    private lateinit var basePath: String
    private val tableTemplate = DefaultTableTemplate(5, klerk, kClass)


    fun initView(basePath: String) {
        this.basePath = basePath
    }

    fun registerRoutes(): Routing.() -> Unit = {
        get("${basePath}/${modelPathPart}") {
            renderModelList(call, config)
        }

        get("${basePath}/${modelPathPart}/analysis") {
            renderListAnalysis<T, V, C>(call, config, klerk, kClass)
        }
    }

    private fun <T : Any> detailsPathProvider(model: Model<T>) = "${kClass.simpleName!!}/items/${model.id}"

    // ------------ List ------------------------------------------------------

    private suspend fun renderModelList(call: ApplicationCall, config: LowCodeConfig<C>) {
        val context = config.contextProvider(call)
        val modelView = klerk.config.getView<T>(kClass)
        val collection = getCollection(call.request.queryParameters, modelView)

        val (table, voidEvents) = klerk.read(context) {
            Pair(
                tableTemplate.create(collection, ::detailsPathProvider, this, call),
                getPossibleVoidEvents(kClass)
            )
        }

        call.respondHtml {
            apply(lowCodeHtmlHead(config))
            body {
                apply(navMenu(basePath, modelPathPart, humanName))
                h2 { +humanName }
                ul {
                    /*klerk.config.getView<T>(kClass).getCollections().forEach {
                        it.getId().let { id ->
                            val encodedId = URLEncoder.encode(id, Charset.forName("utf-8"))
                            li {
                                a(href = "${basePath}/${modelPathPart}?listsource=$encodedId") {
                                    +(it.getId())
                                }
                            }

                        }
                    }
                     */
                }

                div {
                    apply(renderModelList2(table, voidEvents, call, modelView, context))
                }
            }
        }
    }

    private fun getCollection(
        queryParameters: Parameters,
        modelCollections: ModelCollections<T, C>
    ): ModelCollection<T, C> {
        val collectionId = queryParameters["collection"] ?: return modelCollections.all
        val decoded = CollectionId.from(URLDecoder.decode(collectionId, Charset.forName("utf-8")))
        return klerk.config.getView<T>(kClass).getCollections().single { it.getFullId() == decoded }
    }

    /*
    val refModels = list(modelView.all()) { passesFilter(it, call) }.map { ReflectedModel(it) }
            refModels.forEach {
                apply(it.populateRelations())
            }
     */


    private fun renderPieChart(reflectedModels: List<ReflectedModel<T>>): DIV.() -> Unit = {
        pre(classes = "mermaid") {
            unsafe {
                +"""pie title By state
                    ${
                    reflectedModels.groupBy { it.original.state }.map {
                        """"${it.key}" : ${it.value.size}"""
                    }.joinToString("\n")
                }
                """.trimIndent()
            }
        }
        apply(addMermaidScript())
    }

    private fun passesFilter(model: Model<T>, call: ApplicationCall): Boolean {
        val stateFilter = call.request.queryParameters["filterState"] ?: return true
        if (stateFilter == "All") {
            return true
        }
        return model.state == stateFilter
    }


    private fun renderModelList2(
        table: Table<T, C, V>,
        voidEventReferences: Set<EventReference>,
        call: ApplicationCall,
        modelCollections: ModelCollections<T, C>,
        context: C
    ): DIV.() -> Unit = {

        apply(table.render())

        h3 { +"Events" }
        val buttonTargets =
            ButtonTargets(back = call.request.uri, model = "${config.basePath}/$modelPathPart/items/{id}", error = "/")
        voidEventReferences.forEach { event ->
            p { apply(LowCodeCreateEvent.renderButton(event, klerk, null, config, buttonTargets, context)) }
        }

        a(href = "$basePath/$modelPathPart/analysis") { +"(More details about the list)" }
    }

}

private fun <C : KlerkContext, V> renderFilter(
    call: ApplicationCall,
    klerk: Klerk<C, V>,
    kClass: KClass<out Any>
): HtmlBlockTag.() -> Unit =
    {
        val stateNames = klerk.config.managedModels
            .single { it.kClass == kClass }
            .stateMachine.states.filter { it !is VoidState }
            .map { it.name }

        fieldSet {
            legend { +"Filters" }
            form(action = call.request.uri, method = FormMethod.get) {

                p {
                    label {
                        htmlFor = "collectionselect"
                        +"Collection"
                    }
                    br()
                    select {
                        id = "collectionselect"
                        name = "collection"
                        klerk.config.getCollections().filter { it.first == kClass }.map { it.second }.forEach {
                            option {
                                value = it.getFullId().toString()
                                if (call.request.queryParameters["collection"]?.equals(
                                        it.getFullId().toString()
                                    ) == true
                                ) {
                                    selected = true
                                }
                                +camelCaseToPretty(it.getId())
                            }
                        }
                    }
                }

                p {
                    label {
                        htmlFor = "filterselect"
                        +"State"
                    }
                    br()
                    select() {
                        id = "filterselect"
                        name = "filterState"
                        option {
                            value = "All"
                            if (call.request.queryParameters["filterState"]?.equals("All") == true) {
                                selected = true
                            }
                            +"All"
                        }

                        stateNames.forEach {
                            option {
                                value = it
                                if (call.request.queryParameters["filterState"]?.equals(it) == true) {
                                    selected = true
                                }
                                +it
                            }
                        }
                    }
                }

                p {
                    label {
                        htmlFor = "filterstring"
                        title = additionalFiltersExplanationText
                        +"Additional filters"

                        /*                    details {
                                            summary { +"Filter" }
                                            +"This field understands a few filter commands. Some examples:"
                                            ul {
                                                li { +"created>2023-03-10T20:23:13Z" }
                                                li { +"updated<2023-03-10T20:23:13Z" }
                                                li { +"contains=Bertil" }
                                                li { +"updated<2023-03-10T20:23:13Z contains=Anna" }
                                            }
                                        }

                     */
                    }
                    br()
                    textInput {
                        id = "filterstring"
                        name = "filterString"
                        size = "40"
                        title = additionalFiltersExplanationText
                        value = call.request.queryParameters["filterString"] ?: ""
                    }
                }

                p {
                    button(type = ButtonType.submit) { +"Apply filter" }
                }

            }
        }

    }


private fun <T : Any> renderTable(
    models: List<Model<T>>,
    pathProvider: (Model<T>) -> String
): HtmlBlockTag.() -> Unit = {

    table(classes = "indicator table") {
        id = "table"
        thead {
            tr {
                th { +"Created" }
                th { +"Updated" }
                th { +"State" }
                th { +"Properties" }
            }
        }
        tbody {
            models
                .forEach { model ->
                    //val path = "$basePath/$modelPathPart/items/${model.id}"
                    val path = pathProvider(model)
                    tr {
                        onClick = """window.location = '$path';"""
                        td {
                            a(path) {
                                unsafe {
                                    +dateFormatter.format(model.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()))
                                        .replace("-", NON_BREAKING_HYPHEN)
                                }
                            }
                        }
                        td {
                            unsafe {
                                +dateFormatter.format(model.lastModifiedAt.toLocalDateTime(TimeZone.currentSystemDefault()))
                                    .replace("-", NON_BREAKING_HYPHEN)
                            }
                        }
                        td { +model.state }
                        td {
                            +(if (model.props.toString().length < 100) model.props.toString() else model.props.toString()
                                .take(100).plus("..."))
                        }
                    }
                }
        }
    }
}


internal fun ReflectedProperty.renderValueNonBreakingHtml(): HTMLTag.() -> Unit = {
    if (value is Instant) {
        unsafe {
            +(dateFormatter.format(value.toLocalDateTime(TimeZone.currentSystemDefault()))
                .replace("-", NON_BREAKING_HYPHEN))
        }
    } else {
        +value.toString()
    }
}

internal fun ReflectedProperty.renderNameNonBreakingHtml(): HTMLTag.() -> Unit = {
    unsafe { +name().replace("-", NON_BREAKING_HYPHEN).replace(" ", NON_BREAKING_SPACE) }
}

internal fun addMermaidScript(): HtmlBlockTag.() -> Unit = {
    script(type = "module") {
        unsafe {
            +"import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';\n"
            +"mermaid.initialize({ startOnLoad: true });"
        }
    }
}

private fun createQueryOptions(queryParameters: Parameters): QueryOptions {
    val cursorInRequest = queryParameters["cursor"]
    val cursor = if (cursorInRequest == null) null else QueryListCursor.fromString(cursorInRequest)
    return QueryOptions(maxItems = 15, cursor = cursor)
}

private fun <T : Any> createMetaFilter(queryParameters: Parameters): ((Model<T>) -> Boolean)? {
    val includeState = queryParameters["filterState"]
    val filterString = queryParameters["filterString"] ?: ""
    if ((includeState == null || includeState == "All") && filterString.isEmpty()) {
        return null
    }
    return fun(model: Model<T>): Boolean {
        if (includeState != null && includeState != model.state && includeState != "All") {
            return false
        }
        require(!filterString.contains("  ")) { "Filter string cannot contain two consecutive spaces" }
        val filtersAndValues = filterString.split(" ")

        // should improve this...
        filtersAndValues.forEach {
            if (it.startsWith("created>")) {
                val timeString = it.split(">").last()
                val instant = Instant.parse(timeString)
                if (model.createdAt < instant) {
                    return false
                }
            }
            if (it.startsWith("created<")) {
                val timeString = it.split("<").last()
                val instant = Instant.parse(timeString)
                if (model.createdAt > instant) {
                    return false
                }
            }
            if (it.startsWith("updated>")) {
                val timeString = it.split(">").last()
                val instant = Instant.parse(timeString)
                if (model.lastModifiedAt < instant) {
                    return false
                }
            }
            if (it.startsWith("updated<")) {
                val timeString = it.split("<").last()
                val instant = Instant.parse(timeString)
                if (model.lastModifiedAt > instant) {
                    return false
                }
            }
            if (it.startsWith("contains=")) {
                val textToFind = it.split("=").last()
                if (model.props::class.declaredMemberProperties.none { prop ->
                        prop.getter.call(model.props).toString().contains(textToFind)
                    }) {
                    return false
                }
            }
        }

        return true
    }
}

private const val BUTTON_STYLE = "margin: 1rem;"

private fun <T : Any> renderPagination(
    queryResponse: QueryResponse<T>,
    call: ApplicationCall
): HtmlBlockTag.() -> Unit = {
    div {

        queryResponse.cursorFirst?.let {
            a(withQueryParam(call.request.uri, "cursor", it.toString())) {
                button {
                    style = BUTTON_STYLE
                    +"First"
                }
            }
        }

        queryResponse.cursorPrevious?.let {
            a(withQueryParam(call.request.uri, "cursor", it.toString())) {
                button {
                    style = BUTTON_STYLE
                    +"Previous"
                }
            }
        }

        queryResponse.cursorNext?.let {
            a(withQueryParam(call.request.uri, "cursor", it.toString())) {
                button {
                    style = BUTTON_STYLE
                    +"Next"
                }
            }
        }

        queryResponse.cursorLast?.let {
            a(withQueryParam(call.request.uri, "cursor", it.toString())) {
                button {
                    style = BUTTON_STYLE
                    +"Last"
                }
            }
        }
    }
}

internal fun withQueryParam(url: String, paramName: String, paramValue: String): String {
    // FIXME: sloppy implementation
    if (!url.contains("?")) {
        return "$url?$paramName=$paramValue"
    }
    if (!url.contains(paramName)) {
        return "$url&$paramName=$paramValue"
    }
    val valueStartIndex = url.indexOf(paramName) + paramName.length + 1
    val valueEndIndex = url.indexOf("&", valueStartIndex)
    val oldValue =
        if (valueEndIndex == -1) url.substring(valueStartIndex) else url.substring(valueStartIndex, valueEndIndex)
    return url.replace(oldValue, paramValue)
}

public open class DefaultTableTemplate<C : KlerkContext, V>(
    private val maxItems: Int,
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>
) {

    init {
        require(maxItems in 1..999)
    }

    public fun <T : Any> create(
        source: ModelCollection<T, C>,
        detailsPathProvider: (Model<T>) -> String,
        reader: Reader<C, V>,
        call: ApplicationCall,
    ): Table<T, C, V> {
        val queryOptions = createQueryOptions(call.request.queryParameters)
        val metaFilter = createMetaFilter<T>(call.request.queryParameters)
        val queryResponse = reader.query(source.filter(filter = metaFilter), queryOptions)
        return Table(queryResponse, detailsPathProvider, call, klerk, kClass)
    }

}

public class Table<T : Any, C : KlerkContext, V>(
    private val queryResponse: QueryResponse<T>,
    private val pathProvider: (Model<T>) -> String,
    private val call: ApplicationCall,
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>,
) {
    public fun render(): HtmlBlockTag.() -> Unit = {
        apply(renderFilter(call, klerk, kClass))
        if (queryResponse.items.isEmpty()) {
            p { +"The list is empty" }
        } else {
            apply(renderTable(queryResponse.items, pathProvider))
            apply(renderPagination(queryResponse, call))
        }
    }
}

private val additionalFiltersExplanationText: String = """This field understands a few filter commands. Some examples:
                        |created>2023-03-10T20:23:13Z
                        |updated<2023-03-10T20:23:13Z
                        |contains=Bertil
                        |updated<2023-03-10T20:23:13Z contains=Anna
                    """.trimMargin()

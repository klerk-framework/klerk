package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.AlgorithmDocumenter
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KSuspendFunction1

public data class LowCodeConfig<C : KlerkContext>(
    val basePath: String,
    val contextProvider: KSuspendFunction1<ApplicationCall, C>,
    val customAfterEventButtonsOnDetailView: ((KClass<out Any>, Model<Any>) -> DIV.() -> Unit)? = null,
    val showOptionalParameters: (EventReference) -> Boolean,
    val cssPath: String,
    val knownAlgorithms: Set<FlowChartAlgorithm<*, *>> = emptySet(),
    val createCommandPath: String = "/_createevent"
) {
    val fullCreateEventPath: String
        get() = "${basePath}${createCommandPath}"
}

public class LowCodeMain<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>, private val config: LowCodeConfig<C>
) {
    private val listViews: List<LowCodeList<out Any, C, V>>
    private val detailViews: List<LowCodeItemDetails<out Any, C, V>>
    private val createCommandsWithParams: List<LowCodeCreateEvent<C, V>>

    private val auditPath = "${config.basePath}/_audit"
    private val jobsPath = "${config.basePath}/_jobs"
    private val metricsPath = "${config.basePath}/_metrics"
    private val pluginsPath = "${config.basePath}/_plugins"
    private val logPath = "${config.basePath}/_log"
    private val documentationPath = "${config.basePath}/_documentation"

    init {
        createCommandsWithParams = klerk.config.managedModels.flatMap { managed ->
            managed.stateMachine.getAllEvents().filter { klerk.config.getParameters(it) != null }.map { event ->
                LowCodeCreateEvent(klerk, config, event, managed.kClass)
            }
        }

        val pairs = klerk.config.getManagedClasses().map { managedClass ->
            val humanName =
                managedClass.simpleName!!.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val modelPathPart = managedClass.simpleName!!
            val pathToList = "${config.basePath}/$modelPathPart"

            val listView = LowCodeList<Any, C, V>(
                managedClass, config, createCommandsWithParams, modelPathPart, pathToList, humanName, klerk
            )
            val detailView = LowCodeItemDetails<Any, C, V>(
                managedClass, config, modelPathPart, humanName, klerk, auditPath
            )
            Pair(listView, detailView)
        }
        listViews = pairs.map { it.first }
        detailViews = pairs.map { it.second }
        listViews.forEach { it.initView(config.basePath) }
        detailViews.forEach { it.initView(config.basePath) }

        AlgorithmDocumenter.setKnownAlgorithms(config.knownAlgorithms)
    }

    public fun registerRoutes(): Routing.() -> Unit = {
        listViews.forEach { apply(it.registerRoutes()) }
        detailViews.forEach { apply(it.registerRoutes()) }

        get(config.fullCreateEventPath) {
            LowCodeCreateEvent.renderCreateEventPage(call, createCommandsWithParams, klerk, config)
        }

        post(config.fullCreateEventPath) {
            LowCodeCreateEvent.renderExecuteEvent(call, createCommandsWithParams, klerk, config)
        }

        get(config.basePath) {
            renderMain(call)
        }

        get(auditPath) {
            renderAudit(call, config, config.basePath, klerk)
        }

        get("$auditPath/{id}") {
            renderAuditDetails(call, config, klerk)
        }

        get(jobsPath) {
            renderJobs(call, config, jobsPath, klerk)
        }

        get("${jobsPath}/{id}") {
            renderJobDetails(call, config, klerk)
        }

        get(metricsPath) {
            renderMetrics(call, config, metricsPath, klerk)
        }

        get(documentationPath) {
            renderDocumentation(call, config, klerk, documentationPath)
        }

        get("$documentationPath/algorithms/{name}") {
            renderAlgorithm(call, config, klerk)
        }

        get(pluginsPath) {
            renderPlugins(call, config, klerk)
        }

        get("${config.basePath}/plugin") {
            renderPluginPage(call, config, klerk)
        }

        klerk.config.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>().forEach { plugin ->
            plugin.registerExtraRoutes(this, config.basePath)
        }

        get(logPath) {
            renderLog(call, config, klerk)
        }


    }

    private suspend fun renderMain(call: ApplicationCall) {
        val actor = config.contextProvider(call)
        call.respondHtml {
            apply(lowCodeHtmlHead(config))
            body {
                header {
                    h1 { +"Klerk Admin" }
                }
                nav {
                    span {
                        style = "margin: 10px;"
                        a(href = logPath) { button { +"Log" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = jobsPath) { button { +"Jobs" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = documentationPath) { button { +"Documentation" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = auditPath) { button { +"Audit Log" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = metricsPath) { button { +"Metrics" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = pluginsPath) { button { +"Plugins" } }
                    }

                    klerk.config.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>().forEach { plugin ->
                        span {
                            style = "margin: 10px;"
                            a(href = "${config.basePath}/plugin?name=${plugin.name}") { button { +plugin.page.buttonText } }
                        }

                    }

                    h3 { +"Data" }
                    table {
                        listViews.forEach { view ->
                            tr {
                                td {
                                    a(href = view.pathToList) {
                                        +view.humanName
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
    }
}

public interface AdminUIPluginIntegration<C : KlerkContext, V> : KlerkPlugin<C, V> {

    public val page: PluginPage<C, V>
    public fun registerExtraRoutes(routing: Routing, basePath: String)

}

public interface PluginPage<C : KlerkContext, V> {
    public val buttonText: String
    public suspend fun render(
        call: ApplicationCall,
        config: LowCodeConfig<C>,
        klerk: Klerk<C, V>
    ): Unit

}

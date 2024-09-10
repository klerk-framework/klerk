package dev.klerkframework.webutils

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderMetrics(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    jobsPath: String,
    data: Klerk<C, V>
) {
    val actor = config.contextProvider(call)
    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav { div { a(href = config.basePath) { +"Home" } } }
            }
            main {
                h1 { +"Runtime" }
                val runtime = Runtime.getRuntime()
                table {
                    tr {
                        td { +"Available processors" }
                        td { +runtime.availableProcessors().toString() }
                    }
                    tr {
                        td { +"Max memory (MB)" }
                        td { +(runtime.maxMemory() / 1000000).toString() }
                    }
                    tr {
                        td { +"Total memory (MB)" }
                        td { +(runtime.totalMemory() / 1000000).toString() }
                    }
                    tr {
                        td { +"Free memory (MB)" }
                        td { +(runtime.freeMemory() / 1000000).toString() }
                    }

                }
                h1 { +"Metrics" }
                table {
                    thead {
                        tr {
                            th { +"Name" }
                            th { +"Value" }
                            th { +"Base unit" }
                            th { +"Description" }
                            // th { +"Tags" }
                        }
                    }
                    tbody {
                        data.config.meterRegistry.meters.sortedBy { it.id.name }.forEach {
                            tr {
                                td { +it.id.name }
                                td {
                                    unsafe {
                                        it.measure().forEach {
                                            +"${it.statistic.name}:$NON_BREAKING_SPACE${it.value}"
                                            +"<br>"
                                        }
                                    }
                                }
                                td { +(it.id.baseUnit ?: "") }
                                td { +(it.id.description ?: "") }
                                //td { +it.id.tags.joinToString(", ") }
                            }
                        }
                    }
                }
            }
        }
    }
}

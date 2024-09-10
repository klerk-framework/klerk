package dev.klerkframework.webutils

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.misc.dateTimeFormatter
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderLog(
    call: ApplicationCall, config: LowCodeConfig<C>, klerk: Klerk<C, V>
) {
    val context = config.contextProvider.invoke(call)
    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav { div { a(href = config.basePath) { +"Home" } } }
            }
            main {
                h1 { +"Log" }
                klerk.log.entries(context).forEach { entry ->
                    // the LogEntry is currently always a fat object (i.e. it contains lots of interesting stuff). But
                    // soon we will persist the log, which means that after a restart, the entries will not be fat
                    // anymore. So we can only use the template and facts here. (but for now we have to use toLogMessage())

                    details {
                        summary { +"${dateTimeFormatter.format(entry.time.toLocalDateTime(TimeZone.currentSystemDefault()))} ${entry.getHeading()}" }
                        table {
                            entry.getContent()?.let {
                                tr {
                                    td { +"Content" }
                                    td { +it }
                                }
                            }
                            tr {
                                td { +"Actor" }
                                td { +(entry.actor?.toString() ?: "[none]") }
                            }
                            tr {
                                td { +"Source" }
                                td { +entry.source.toString() }
                            }
                            entry.facts.forEach {
                                tr {
                                    td { +it.type.name }
                                    td { +"${it.name}=${it.value}" }
                                }
                            }
                        }


                    }
                    br()

                }


            }
        }
    }
}

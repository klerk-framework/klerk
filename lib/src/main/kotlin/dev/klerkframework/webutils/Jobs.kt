package dev.klerkframework.webutils

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.misc.dateTimeFormatter

import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderJobs(
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
                h1 { +"Jobs" }
                table {
                    thead {
                        tr {
                            th { +"Id" }
                            th { +"Name" }
                            th { +"Status" }
                            th { +"Time" }
                        }
                    }
                    tbody {
                        data.jobs.getAllJobs().forEach {
                            val timeString =
                                if (it.lastAttemptFinished == null) "" else dateTimeFormatter.format(
                                    it.lastAttemptFinished.toLocalDateTime(
                                        TimeZone.currentSystemDefault()
                                    )
                                )
                            tr {
                                onClick = """window.location = '$jobsPath/${it.id}';"""
                                td { +it.id.toString() }
                                td { +it.name }
                                td { +it.status.name }
                                td { +timeString }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun <C : KlerkContext, V> renderJobDetails(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    data: Klerk<C, V>
) {
    val actor = config.contextProvider(call)
    val idString = requireNotNull(call.parameters["id"])
    val id = idString.toLong()
    val job = data.jobs.getJob(id)

    val lastAttemptStartedString =
        if (job.lastAttemptStarted == null) "" else dateTimeFormatter.format(
            job.lastAttemptStarted.toLocalDateTime(
                TimeZone.currentSystemDefault()
            )
        )
    val lastAttemptFinishedString =
        if (job.lastAttemptFinished == null) "" else dateTimeFormatter.format(
            job.lastAttemptFinished.toLocalDateTime(
                TimeZone.currentSystemDefault()
            )
        )

    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            nav {
                div {
                    a(href = config.basePath) { +"Home" }
                    +" / "
                    a(href = "${config.basePath}/_jobs") { +"Jobs" }
                }
            }
            h1 { +"Job details" }
            table {
                tbody {
                    tr {
                        td { +"Id" }
                        td { +job.id.toString() }
                    }
                    tr {
                        td { +"Name" }
                        td { +job.name }
                    }
                    tr {
                        td { +"Status" }
                        td { +job.status.name }
                    }
                    tr {
                        td { +"Failed attempts" }
                        td { +"${job.failedAttempts} (max retries: ${job.maxRetries})" }
                    }
                    tr {
                        td { +"Created" }
                        td { +dateTimeFormatter.format(job.created.toLocalDateTime(TimeZone.currentSystemDefault())) }
                    }
                    tr {
                        td { +"Last attempt started" }
                        td { +lastAttemptStartedString }
                    }
                    tr {
                        td { +"Last attempt finished" }
                        td { +lastAttemptFinishedString }
                    }
                    tr {
                        td { +"Data" }
                        td { +job.data }
                    }
                }
            }
            div {
                h3 { +"Log" }
                table {
                    tbody {
                        job.log.forEach {
                            tr {
                                td { +dateTimeFormatter.format(it.key.toLocalDateTime(TimeZone.currentSystemDefault())) }
                                td { +it.value }
                            }
                        }
                    }
                }
            }
        }
    }
}

package dev.klerkframework.webutils

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.dateTimeFormatter
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

internal suspend fun <C:KlerkContext, V> renderAudit(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    basePath: String,
    klerk: Klerk<C, V>
) {
    val context = config.contextProvider(call)

    val forModel = call.request.queryParameters["model"]
    val id = forModel?.let { ModelID.from<Any>(it) }
    val modelSummary = if (id == null) "" else klerk.read(context) { get(id).toString() }

    val events = klerk.events.getEventsInAuditLog(context, id)

    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav { div { a(href = config.basePath) { +"Home" } } }
            }
            h1 { +"Events" }
            if (forModel != null) {
                +modelSummary
                br
                +"ID: $forModel"
            }
            table {
                thead {
                    tr {
                        th { +"Time" }
                        //  th { +"actor" }
                        th { +"Event" }
                        if (forModel == null) th { +"Model" }
                    }
                }
                tbody {
                    events.forEach { event ->
                        tr {
                            td { +dateTimeFormatter.format(event.time.toLocalDateTime(TimeZone.currentSystemDefault())) }
                            // td { +(event.actor?.toAuditLog() ?: "[unauthenticated]") }
                            td { a(href = "_audit/${event.time.to64bitMicroseconds()}") { +event.eventReference.eventName } }
                            if (forModel == null) td { +(ModelID<Any>(event.reference).toString()) }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun <C:KlerkContext, V> renderAuditDetails(call: ApplicationCall, config: LowCodeConfig<C>, data: Klerk<C, V>) {
    val context = config.contextProvider(call)
    val instantString = requireNotNull(call.parameters["id"])
    val time = decode64bitMicroseconds(instantString.toLong())
    val event = data.events.getEventsInAuditLog(context, after = time, before = time).single()

    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav {
                    div {
                        a(href = config.basePath) { +"Home" }
                        +" / "
                        a(href = "${config.basePath}/_audit") { +"Audit log" }
                    }
                }
            }
            h1 { +"Event details" }
            table {
                tr {
                    td { +"Time" }
                    td { +dateTimeFormatter.format(event.time.toLocalDateTime(TimeZone.currentSystemDefault())) }
                }
                tr {
                    td { +"Actor" }
                    td { +describeActor(event.actorType, event.actorReference, event.actorExternalId) }
                }
                tr {
                    td { +"Event" }
                    td { +event.eventReference.eventName }
                }
                tr {
                    td { +"Model ID" }
                    td { +"${ModelID<Any>(event.reference)} (${event.reference})" }
                }
            }
            h2 { +"Parameters" }
            val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
            val jsonPretty = gson.toJson(JsonParser.parseString(event.params))
            textArea {
                disabled=true
                rows = jsonPretty.lines().size.toString()
                +jsonPretty
            }
        }
    }
}

internal fun describeActor(actorType: Byte, actorReference: Int?, actorExternalId: Long?): String {
    val type = when (actorType) {
        dev.klerkframework.klerk.ActorIdentity.systemType.toByte() -> "system"
        dev.klerkframework.klerk.ActorIdentity.authentication.toByte() -> "authentication"
        dev.klerkframework.klerk.ActorIdentity.modelType.toByte() -> "model"
        dev.klerkframework.klerk.ActorIdentity.modelReferenceType.toByte() -> "modelReference"
        dev.klerkframework.klerk.ActorIdentity.unauthenticatedType.toByte() -> "unauthenticated"
        dev.klerkframework.klerk.ActorIdentity.customType.toByte() -> "custom"
        else -> error("Unknown ActorIdentity $actorType")
    }
    val modelReference = if (actorReference == null) "" else "Model reference: $actorReference"
    val externalId = if (actorExternalId == null) "" else "External ID: $actorExternalId"
    return "$type $modelReference $externalId"
}

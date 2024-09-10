package dev.klerkframework.webutils

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Klerk

import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*
import kotlin.reflect.KClass

internal suspend fun <T : Any, V, C:KlerkContext> renderListAnalysis(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    klerk: Klerk<C, V>,
    kClass: KClass<out Any>
) {
    val context = config.contextProvider(call)
    val modelView = klerk.config.getView<T>(kClass)

    klerk.readSuspend(context) {
        call.respondHtml {
            apply(lowCodeHtmlHead(config))
            body {
                h1 { +"Details" }
                +kClass.toString()
                h2 { +"Collections" }
                modelView.getCollections().forEach { collection ->
                    h6 { +(collection.getFullId().toString()) }
                    val groupedByState = collection.withReader(this@readSuspend, null).groupBy { it.state }
                    val countPerState = groupedByState.mapValues { (k, v) -> groupedByState[k]?.count() ?: 0 }
                    +"Total count: ${countPerState.values.sum()}"
                    ul {
                        groupedByState.keys.forEach {
                            li { +"${it}: ${countPerState[it]}" }
                        }
                    }
                }
            }
        }
    }
}

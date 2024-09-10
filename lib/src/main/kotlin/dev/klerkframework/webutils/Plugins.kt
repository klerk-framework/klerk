package dev.klerkframework.webutils

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderPlugins(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    klerk: Klerk<C, V>
) {
    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav { div { a(href = config.basePath) { +"Home" } } }
            }
            h1 { +"Plugins" }
            main {
                val plugins = klerk.config.plugins
                if (plugins.isEmpty()) {
                    p { +"No plugins" }
                } else {
                    ul {
                        plugins.forEach { plugin ->
                            li {
                                if (plugin is AdminUIPluginIntegration<C, V>) {
                                    a(href = "${config.basePath}/plugin?name=${plugin.name}") { +plugin.name }
                                } else {
                                    +plugin.name
                                }
                                br()
                                i { +plugin.description }
                                br()
                            }
                        }
                    }

                }
            }
        }
    }
}

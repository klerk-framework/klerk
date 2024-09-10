package dev.klerkframework.webutils

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*

internal suspend fun <C : KlerkContext, V> renderPluginPage(call: ApplicationCall, config: LowCodeConfig<C>, klerk: Klerk<C, V>) {
    val pluginName = requireNotNull(call.request.queryParameters["name"])
    val plugin = klerk.config.plugins.single { it.name == pluginName } as AdminUIPluginIntegration<C, V>
    plugin.page.render(call, config, klerk)
}

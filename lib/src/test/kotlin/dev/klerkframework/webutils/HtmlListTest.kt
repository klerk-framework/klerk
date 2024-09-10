package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.ModelCache
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.html.*

fun main() {

    runBlocking {

        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val klerk = Klerk.create(createConfig(collections))
        klerk.meta.start()
        if (ModelCache.isEmpty()) {
            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)
        }

        io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            routing {
                get("/") {

                    val table = klerk.read(Context.system()) {
                        DefaultTableTemplate(5, klerk, Author::class).create(
                            klerk.config.collections.authors.all,
                            ::detailsPashProvider,
                            this,
                            call
                        )
                    }

                    call.respondHtml {
                        head {
                            link(href = "https://unpkg.com/sakura.css/css/sakura.css", rel = "stylesheet")
                        }
                        body {
                            h1 { +"A page that shows a list" }
                            div {
                                apply(table.render())
                            }
                        }
                    }
                }

            }
        }.start(wait = true)
    }
}

private fun <T : Any> detailsPashProvider(model: Model<T>) = "Author/items/${model.id}"

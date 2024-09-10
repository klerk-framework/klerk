package dev.klerkframework.webutils

import com.expediagroup.graphql.server.ktor.*

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.graphql.EventMutationService
import dev.klerkframework.klerk.graphql.GenericQuery
import dev.klerkframework.klerk.read.ModelModification.*
import dev.klerkframework.klerk.storage.ModelCache
import graphql.GraphQLContext
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking

fun main() {

    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))
    val klerk = Klerk.create(createConfig(collections))
    runBlocking {


        klerk.meta.start()

        if (ModelCache.isEmpty()) {
            generateSampleData(50, 2, klerk)
        }

        val embeddedServer = io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            install(GraphQL) {
                schema {
                    packages = listOf("dev.klerkframework.klerk.graphql")
                    queries = listOf(GenericQuery(klerk, ::contextFactory))
                    mutations = listOf(EventMutationService(klerk, ::contextFactory))
                }
                server {
                    contextFactory = CustomGraphQLContextFactory()
                }
            }

            routing {
                graphQLPostRoute()
                graphQLGetRoute()
                graphiQLRoute()
                graphQLSDLRoute()
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down")
            embeddedServer.stop()
            klerk.meta.stop()
            println("Shutdown complete")
        })

        embeddedServer.start(wait = false)

        klerk.models.subscribe(Context.system(), null).collect {
            when (it) {
                is Created -> println("${it.id} was created")
                is PropsUpdated -> println("${it.id} had props updated")
                is Transitioned -> println("${it.id} transitioned")
                is Deleted -> println("${it.id} was deleted")
            }
        }
    }

}

private fun contextFactory(graphQlContext: GraphQLContext) = Context.unauthenticated()

class CustomGraphQLContextFactory : DefaultKtorGraphQLContextFactory() {
    override suspend fun generateContext(request: ApplicationRequest): GraphQLContext {
        return super.generateContext(request)
    }
}

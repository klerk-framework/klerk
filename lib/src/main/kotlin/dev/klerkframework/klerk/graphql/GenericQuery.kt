package dev.klerkframework.klerk.graphql

import com.expediagroup.graphql.generator.scalars.ID
import com.expediagroup.graphql.server.operations.Query
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.misc.EventParameter
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.Field
import dev.klerkframework.klerk.misc.PropertyType
import graphql.GraphQLContext
import graphql.GraphqlErrorException
import graphql.schema.DataFetchingEnvironment
import kotlin.reflect.full.memberProperties

public class GenericQuery<C : KlerkContext, V>(private val klerk: Klerk<C, V>, private val contextFactory: suspend (GraphQLContext) -> C) : Query {

    public suspend fun collections(): List<KlerkCollection> {
        return klerk.config.getCollections().map { (type, collection) ->
            KlerkCollection(
                ID(collection.getFullId().toString()),
                type.simpleName!!
            )
        }
    }

    public suspend fun models(
        collectionId: String,
        first: Int? = 10,
        after: String? = null,
        before: String? = null,
        env: DataFetchingEnvironment
    ): KlerkModelsResponse {
        try {
            val context = contextFactory(env.graphQlContext)
            require(!(after != null && before != null))
            var cursor = after?.let { QueryListCursor.fromString(after) }
            if (before != null) {
                cursor = QueryListCursor.fromString(before)
            }
            val collection = klerk.config.getCollection(CollectionId.from(collectionId))
            val result = klerk.read(context) {
                query(collection, QueryOptions(maxItems = first ?: 10, cursor))
            }

            val nodesAndCursors = result.items.map {
                val possibleEvents = klerk.read(context) { getPossibleEvents(it.id) }
                Pair(
                    KlerkModel.from(it, possibleEvents, klerk),
                    QueryListCursor(after = it.createdAt)
                )
            }

            return KlerkModelsResponse(
                nodesAndCursors.map { KlerkEdge(it.first, it.second.toString()) }, PageInfo(
                    result.hasPreviousPage,
                    result.hasNextPage,
                    result.cursorFirst.toString(),
                    result.cursorLast.toString(),
                )
            )
        } catch (e: Exception) {
            val message = when (e) {
                is AuthorizationException -> "The operation is not authorized"
                is NoSuchElementException -> "Collection not found"
                else -> "Unknown problem"
            }
            throw GraphqlErrorException.newErrorException().message(message).build()
        }
    }

    public suspend fun model(id: ID, env: DataFetchingEnvironment): KlerkModel? {
        try {
            val context = contextFactory(env.graphQlContext)
            val model = klerk.read(context) { getOrNull(ModelID.from(id.value)) } ?: return null
            val events = klerk.read(context) { getPossibleEvents(model.id) }
            return KlerkModel.from(model, events, klerk)
        } catch (e: Exception) {
            if (e is AuthorizationException) {
                throw GraphqlErrorException.newErrorException().message("The operation is not authorized").build()
            }
            throw GraphqlErrorException.newErrorException().build()
        }
    }

    public suspend fun voidCommands(type: String, env: DataFetchingEnvironment): List<KlerkCommand> {
        val context = contextFactory(env.graphQlContext)
        val managed = klerk.config.managedModels.single { it.kClass.simpleName == type }
        return klerk.config.getPossibleVoidEvents(managed.kClass, context)
            .map { KlerkCommand.from(it, klerk.config.getParameters(it)) }
    }
}

public data class KlerkCommand(val name: String, val parameters: List<KlerkParameter>) {
    internal companion object {
        fun from(eventReference: EventReference, parameters: EventParameters<*>?): KlerkCommand {
            if (parameters == null) {
                return KlerkCommand(eventReference.toString(), emptyList())
            }
            return KlerkCommand(eventReference.toString(), parameters.all.map { toParam(it) })
        }

        private fun toParam(p: EventParameter): KlerkParameter {
            return KlerkParameter(p.name, p.type?.name ?: "[?]", p.modelIDType, p.isNullable, p.isRequired)
        }

    }
}

public data class KlerkParameter(
    val name: String,
    val type: String,
    val ofType: String?,
    val nullable: Boolean,
    val required: Boolean
)

public data class KlerkField(val name: String, val type: PropertyType, val value: String?)

public data class KlerkModel(
    val id: ID,
    val type: String?,
    val state: String,
    val createdAt: String,
    val lastModifiedAt: String,
    val lastPropsModifiedAt: String,
    val lastStateTransitionAt: String,
    val props: List<KlerkField>,
    val possibleEvents: List<KlerkCommand>
) {

    internal companion object {
        fun <C : KlerkContext> from(
            model: Model<out Any>,
            eventReferences: Set<EventReference>,
            klerk: Klerk<C, *>
        ): KlerkModel {
            val props = mutableListOf<KlerkField>()
            model.props::class.memberProperties.forEach {
                println(it)
            }
            model.props::class.memberProperties.map { Field(it, it.getter.call(model.props)) }.forEach {
                props.add(
                    KlerkField(
                        it.name,
                        it.type ?: PropertyType.Int,
                        it.toString()
                    )
                )
            }
            return KlerkModel(
                id = ID(model.id.toString()),
                type = model.props::class.simpleName,
                state = model.state,
                createdAt = model.createdAt.toString(),
                lastModifiedAt = model.lastModifiedAt.toString(),
                lastPropsModifiedAt = model.lastPropsUpdateAt.toString(),
                lastStateTransitionAt = model.lastStateTransitionAt.toString(),
                props = props,
                possibleEvents = eventReferences.map { KlerkCommand.from(it, klerk.config.getParameters(it)) }
            )
        }

    }
}

public data class KlerkCollection(val id: ID, val type: String)

public data class KlerkModelsResponse(
    val edges: List<KlerkEdge>,
    val pageInfo: PageInfo
)

public data class KlerkEdge(val node: KlerkModel, val cursor: String)

public data class PageInfo(
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
    val startCursor: String?,
    val endCursor: String?
)

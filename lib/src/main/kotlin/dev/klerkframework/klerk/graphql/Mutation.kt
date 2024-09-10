package dev.klerkframework.klerk.graphql

import com.expediagroup.graphql.generator.scalars.ID
import com.expediagroup.graphql.server.operations.Mutation
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import graphql.ErrorClassification
import graphql.GraphQLContext
import graphql.GraphQLError
import graphql.GraphqlErrorException
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment

public class EventMutationService<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val contextFactory: suspend (GraphQLContext) -> C
) : Mutation {

    public suspend fun createCommand(
        event: String,
        model: ID?,
        paramsJson: String,
        dryRun: Boolean,
        env: DataFetchingEnvironment
    ): DataFetcherResult<CreateCommandResponse?> {

        val context = contextFactory(env.graphQlContext)

        val eventObj = try {
            klerk.config.getEvent(EventReference.from(event))
        } catch (e: Exception) {
            throw GraphqlErrorException.newErrorException().message("There is no event '$event'").build()
        }
        val parameterInfo = klerk.config.getParameters(eventObj.id)
        val paramsObject = if (parameterInfo == null) null else
            klerk.config.gson.fromJson(paramsJson, parameterInfo.raw.javaObjectType)

        val result = klerk.handle(
            Command(
                event = eventObj,
                model = if (model == null) null else ModelID.from(model.value),
                params = paramsObject,
            ),
            context,
            ProcessingOptions(CommandToken.simple(), dryRun = dryRun)
        )
        return when (result) {
            is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> toDataFetcherResult(result)
            is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> throw GraphqlErrorException.newErrorException()
                .message(result.problem.toString()).build()
        }
    }

}

public data class CreateCommandResponse(
    val createdModels: List<String>,
    val modifiedModels: List<String>,
    val deletedModels: List<String>,
    val generatedJobs: List<String>,
    val secondaryEvents: List<String>
)

private fun <C:KlerkContext, V> toDataFetcherResult(result: _root_ide_package_.dev.klerkframework.klerk.CommandResult<Any, C, V>): DataFetcherResult<CreateCommandResponse?> {
    return when (result) {
        is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> DataFetcherResult.newResult<CreateCommandResponse>()
            .error(MyGraphQlError(result.problem)).build()

        is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> DataFetcherResult.newResult<CreateCommandResponse>().data(
            CreateCommandResponse(
                createdModels = result.createdModels.map { it.toString() },
                modifiedModels = result.modelsWithUpdatedProps.map { it.toString() },
                deletedModels = result.deletedModels.map { it.toString() },
                secondaryEvents = result.secondaryEvents.map { it.id() },
                generatedJobs = result.jobs.map { it.id.toUInt().toString() }
            )
        ).build()
    }
}

private class MyGraphQlError(private val a: Problem) : GraphQLError {
    override fun getMessage(): String {
        return a.toString()
    }

    override fun getLocations(): MutableList<graphql.language.SourceLocation> {
        return emptyList<graphql.language.SourceLocation>().toMutableList()
    }

    override fun getErrorType(): ErrorClassification {
        TODO("Not yet implemented")
    }

}

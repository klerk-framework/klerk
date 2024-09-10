package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.camelCaseToPretty
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import kotlinx.html.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.jvmErasure

public class LowCodeCreateEvent<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val config: LowCodeConfig<C>,
    internal val eventReference: EventReference,
    internal val modelClass: KClass<out Any>
) {
    private var template: EventFormTemplate<out Any, C>? = null

    init {
        val parameters = requireNotNull(klerk.config.getParameters(eventReference))
        try {
            template = EventFormTemplate(
                EventWithParameters(eventReference, parameters),
                //eventWithParameters.parameters.raw,
                klerk,
                getUrl(),
            ) {
                remaining()
            }
        } catch (e: NotImplementedError) {
            logger.warn { "AutoUI will not be able to create the event '${eventReference}' (not implemented)" }
        }
    }

    override fun toString(): String = eventReference.toString()

    internal fun hasTemplate() = template != null

    internal fun getUrl(): String {
        return "${config.fullCreateEventPath}?eventId=${eventReference.id().encodeURLPathPart()}"
    }

    public companion object {
        public suspend fun <C : KlerkContext, V> renderCreateEventPage(
            call: ApplicationCall,
            createCommandsWithParams: List<LowCodeCreateEvent<C, V>>,
            klerk: Klerk<C, V>,
            config: LowCodeConfig<C>
        ) {
            val context = config.contextProvider(call)
            val queryParameters = call.request.queryParameters
            val buttonTargets = ButtonTargets.parse(call, null)
            val id = queryParameters["modelId"]?.let { ModelID.from<Any>(it) }
            val eventReference = EventReference.urlDecode(requireNotNull(queryParameters["eventId"]))
            val eventWithParameters =
                EventWithParameters(eventReference, requireNotNull(klerk.config.getParameters(eventReference)))

            requireNotNull(eventWithParameters.parameters)
            val possibleReferenceValues = getPossibleReferenceValues(eventWithParameters.parameters, context.actor)

            val template =
                createCommandsWithParams.single { it.eventReference == eventWithParameters.eventReference }.template

            requireNotNull(template) { "AutoUI not available for this event (as earlier mentioned in the log)" }

            val modelIdQueryParams = if (id != null) mapOf("modelId" to id.toString()) else emptyMap
            val form = klerk.read(context) {
                template.build(
                    call,
                    params = null,
                    reader = this,
                    queryParams = buttonTargets.toQueryParams().plus(modelIdQueryParams),
                    translator = context.translator
                )
            }

            val eventName = camelCaseToPretty(eventReference.eventName)
            val modelName = camelCaseToPretty(eventReference.modelName)
            val heading =
                if (eventName.lowercase().contains(modelName.lowercase())) eventName else "$eventName ($modelName)"

            call.respondHtml {
                apply(lowCodeHtmlHead(config))
                body {
                    main {
                        h1 { +heading }
                        form.render(this)
                    }
                }
            }
        }

        public suspend fun <C : KlerkContext, V> renderExecuteEvent(
            call: ApplicationCall,
            createCommandsWithParams: List<LowCodeCreateEvent<C, V>>,
            klerk: Klerk<C, V>,
            config: LowCodeConfig<C>
        ) {
            val context = config.contextProvider(call)
            val queryParameters = call.request.queryParameters
            //  val buttonTargets = parseButtonTargets(call, null)
            val eventReference = EventReference.from(requireNotNull(queryParameters["eventId"]))
            val id = queryParameters["modelId"]?.let { ModelID.from<Any>(it) }
            //val eventWithParameters = EventWithParameters(eventReference, requireNotNull(klerk.config.getParameters(eventReference)))
            val parameters = klerk.config.getParameters(eventReference)
            val event = klerk.config.getEvent(eventReference)

            val template = createCommandsWithParams.singleOrNull { it.eventReference == eventReference }?.template

            if (template == null) {
                executeEvent(
                    event,
                    id,
                    klerk,
                    context,
                    call,
                    config,
                    CommandToken.simple(),
                    null
                )
            } else {
                when (val parseResult = template.parse(call)) {
                    is ParseResult.Invalid -> EventFormTemplate.respondInvalid(parseResult, call)
                    is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                    is ParseResult.Parsed -> {
                        executeEvent(
                            event,
                            id,
                            klerk,
                            context,
                            call,
                            config,
                            parseResult.key,
                            parseResult.params
                        )
                    }
                }
            }
        }

        private suspend fun <C : KlerkContext, V> executeEvent(
            event: Event<Any, Any?>,
            id: ModelID<Any>?,
            klerk: Klerk<C, V>,
            context: C,
            call: ApplicationCall,
            config: LowCodeConfig<C>,
            key: CommandToken,
            params: Any?
        ) {
            val options = ProcessingOptions(token = key)
            val command = when (event) {
                is InstanceEventNoParameters -> Command(event, id, null)
                is InstanceEventWithParameters -> Command(event, id, params!!)
                is VoidEventNoParameters -> Command(event, null, null)
                is VoidEventWithParameters -> Command(event, null, params!!)
            }

            when (val eventResult = klerk.handle(command, context, options)) {
                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> {
                    call.respondHtml {
                        apply(lowCodeHtmlHead(config))
                        body {
                            h1 { +"Bad request" }
                            val violatedRule = eventResult.problem.violatedRule
                            if (violatedRule != null) {
                                div {
                                    +"$violatedRule (${violatedRule.type})"
                                }
                            } else {
                                div { +eventResult.toString() }
                            }
                        }
                    }
                }

                is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> {
                    val modelId = id ?: eventResult.createdModels.firstOrNull()
                    val modelWasDeleted = eventResult.deletedModels.contains(modelId)
                    val buttonTargets = ButtonTargets.parse(call, modelId)
                    call.respondHtml {
                        apply(lowCodeHtmlHead(config))
                        body {
                            h3 { +"Event was executed" }
                            apply(renderSuccess(eventResult))
                            br
                            a(href = if (modelId == null || modelWasDeleted) buttonTargets.back else buttonTargets.model) {
                                button { +"Ok" }
                            }
                        }
                    }
                }
            }
        }

        public fun <C : KlerkContext, V> renderButton(
            event: EventReference,
            klerk: Klerk<C, V>,
            modelId: ModelID<*>?,
            config: LowCodeConfig<C>,
            buttonTargets: ButtonTargets,
            context: C
        ): HtmlBlockTag.() -> Unit = {
            //val buttonTargets = """${LowCodeCreateEvent.ButtonTarget.error}=${config.basePath}&${LowCodeCreateEvent.ButtonTarget.model}=${config.}basePath/$modelPathPart/items/$modelId&${LowCodeCreateEvent.ButtonTarget.back}=$basePath/$modelPathPart"""

            val parameters = klerk.config.getParameters(event)
            var url =
                "${config.fullCreateEventPath}?eventId=${event.urlEncode()}&_showOptionalParameters=${
                    config.showOptionalParameters(event)
                }${buttonTargets.toQueryParamsString()}"
            if (modelId != null) {
                url = url.plus("&modelId=${modelId}")
            }

            if (parameters == null) {
                form(action = url, method = FormMethod.post) { button { +context.translator.event(event) } }
            } else {
                a(url) {
                    button() {
                        +context.translator.event(event)
                    }
                }
            }
        }

    }
}

internal class AuthenticationException(message: String? = null) : RuntimeException(message)

internal fun valueWithCorrectType(value: String?, type: KType): Any? {
    if (value == null) {
        if (type.isSubtypeOf(BooleanContainer::class.starProjectedType)) {
            // a form containing an unchecked checkbox will appear as null here.
            return if (!type.isMarkedNullable) return type.jvmErasure.constructors.first()
                .call(false) else throw RuntimeException("but how do we know if it was null or false here? Perhaps send hidden? Urk!")
        }
        return null
    }

    if (type.isSubtypeOf(ModelID::class.starProjectedType.withNullability(false))) {
        return ModelID.from<Any>(value)
    }
    if (type.isSubtypeOf(ModelID::class.starProjectedType.withNullability(true))) {
        return if (value.isEmpty()) null else ModelID.from<Any>(value)
    }
    if (type.isSubtypeOf(Enum::class.starProjectedType)) {
        @Suppress("UNCHECKED_CAST")
        val enumClz = Class.forName(type.toString()).enumConstants as Array<Enum<*>>
        return enumClz.first { it.name == value }
    }

    if (type.isSubtypeOf(StringContainer::class.starProjectedType.withNullability(false)) ||
        type.isSubtypeOf(StringContainer::class.starProjectedType.withNullability(true))
    ) {
        return type.jvmErasure.constructors.first().call(value)
    }
    if (type.isSubtypeOf(IntContainer::class.starProjectedType.withNullability(false)) ||
        type.isSubtypeOf(IntContainer::class.starProjectedType.withNullability(true))
    ) {
        try {
            return type.jvmErasure.constructors.first().call(value.toInt())
        } catch (e: NumberFormatException) {
            throw RuntimeException("Could not parse $type", e)
        }
    }
    if (type.isSubtypeOf(LongContainer::class.starProjectedType.withNullability(false)) ||
        type.isSubtypeOf(LongContainer::class.starProjectedType.withNullability(true))
    ) {
        return type.jvmErasure.constructors.first().call(value.toLong())
    }

    if (type.isSubtypeOf(FloatContainer::class.starProjectedType.withNullability(false)) ||
        type.isSubtypeOf(FloatContainer::class.starProjectedType.withNullability(true))
    ) {
        return type.jvmErasure.constructors.first().call(value.toFloat())
    }

    if (type.isSubtypeOf(BooleanContainer::class.starProjectedType.withNullability(false)) ||
        type.isSubtypeOf(BooleanContainer::class.starProjectedType.withNullability(true))
    ) {
        return type.jvmErasure.constructors.first().call(value.toBoolean())
    }

    /*    if (type.isSubtypeOf(EnumContainer::class.starProjectedType.withNullability(false)) ||
            type.isSubtypeOf(EnumContainer::class.starProjectedType.withNullability(true))
        ) {
            val enumClassName =
                type.jvmErasure.members.first() { it.name == "value" }.returnType.arguments.single().toString()
            return type.jvmErasure.constructors.first().call(getEnumValue(enumClassName, value))
        }
     */

    if (type.isSubtypeOf(ModelID::class.starProjectedType.withNullability(false)) ||
        type.isSubtypeOf(ModelID::class.starProjectedType.withNullability(true))
    ) {
        return type.jvmErasure.constructors.first().call(ModelID.from<Any>(value))
    }

    try {
        return type.jvmErasure.constructors.first().call(value)
    } catch (e: Exception) {
        logger.error(e) { "Could not extract value from '$value' for type: $type " }
        throw e
    }
}


/**
 * Check if the parameter class is the same as the model class. However, we don't really know for sure that it is
 * intended to be used as an update...
 */
private suspend fun <C : KlerkContext, V> isCrudUpdate(
    id: ModelID<Any>?,
    e: EventWithParameters<*>,
    context: C,
    klerk: Klerk<C, V>
): Boolean {
    if (id == null) return false
    val model = klerk.read(context) { get(id) }
    return model.props::class == e.parameters?.raw
}

private suspend fun getPossibleReferenceValues(
    eventInputs: EventParameters<*>?,
    actor: dev.klerkframework.klerk.ActorIdentity?
): Map<String, List<Model<Any>>> {
    return emptyMap()
    /*
    if (eventInputs == null) {
        return emptyMap()
    }
    logger.warn { "Will not try to find possible reference values due to bug below" }
    return emptyMap()
    val context = Context(actor)
    val map = mutableMapOf<String, List<Model<Any>>>()
    eventInputs.all
        .filter { it.type == PropertyType.Ref }
        .forEach { p ->
            val suitableModelsIds = data.config.getViewList(p.raw.type, "all")
            val suitableModels = data.models.read(context) { list(suitableModelsIds) }
            map[p.name] = suitableModels
        }
    return map
     */
}

private fun renderSuccess(result: _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success<out Any, *, *>): BODY.() -> Unit = {
    result.createdModels.forEach { p { +"Model (id: $it) was created" } }
    result.modelsWithUpdatedProps.forEach { p { +"Model (id: $it) was updated" } }
    result.deletedModels.forEach { p { +"Model (id: $it) was deleted" } }
    result.secondaryEvents.forEach { p { +"Secondary event executed: $it" } }
    result.jobs.forEach { p { +"Job (id: $it) was created" } }
}


public data class ButtonTargets(public val back: String, public val model: String?, public val error: String) {

    internal fun toQueryParams(): Map<String, String> {
        if (model == null) {
            return mapOf("bt_back" to back, "bt_error" to error)
        }
        return mapOf("bt_back" to back, "bt_model" to model, "bt_error" to error)
    }

    internal fun toQueryParamsString(): String = toQueryParams().map { "${it.key}=${it.value}" }.joinToString("&")

    internal companion object {
        fun parse(call: ApplicationCall, id: ModelID<out Any>?): ButtonTargets {
            var parsedModelPath = call.request.queryParameters["bt_model"] ?: "/"
            if (id != null) {
                parsedModelPath = parsedModelPath.replace("{id}", id.toString())
            }
            return ButtonTargets(
                back = call.request.queryParameters["bt_back"] ?: "/",
                model = parsedModelPath,
                error = call.request.queryParameters["bt_error"] ?: "/",
            )
        }
    }
}

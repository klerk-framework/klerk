package dev.klerkframework.webutils

import com.google.gson.Gson
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.misc.*
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.webutils.ParseResult.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.html.*
import kotlinx.html.InputType.*
import mu.KotlinLogging
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*

private val CSRF_TOKEN = if (isDevelopmentMode()) "csrf-token" else "__Host-csrf-token"
private val IDEMPOTENCE_KEY = if (isDevelopmentMode()) "idempotence-key" else "__Host-idempotence-key"


public data class UIElementData(val propertyName: String, val dataContainer: DataContainer<*>, val enabled: Boolean)

/**
 * Regarding CSRF protection: the 'Double Submit Pattern' with '__Host-' cookie-prefix is used.
 */
public class EventFormTemplate<T : Any, C : KlerkContext>(
    private val eventWithParameters: EventWithParameters<T>,
    internal val klerk: Klerk<C, *>,
    private val postPath: String? = null,
    init: EventFormTemplate<T, C>.() -> Unit
) {
    private val log = KotlinLogging.logger {}

    internal val parameters: EventParameters<T> = eventWithParameters.parameters

    /*private val items = mutableListOf<Item<T>>()
    private val hidden = mutableMapOf<String, Any>()
     */
    private val inputs = mutableListOf<Pair<String, InputType>>()
    private val selectReferences = mutableListOf<String>()
    private val selectEnums = mutableListOf<String>()

    // private val emailInputs = mutableListOf<String>()
    private val propsPopulatedAfterSubmit = mutableListOf<String>()
    private var htmlDetailsSummary: String? = null
    private val htmlDetailsContents = mutableSetOf<String>()
    internal var labelProvider: ((UIElementData) -> String?)? = null

    init {
        this.init()
        validate()
    }

    public fun text(property: KProperty1<*, StringContainer?>): Unit { inputs.add(Pair(property.name, text)) }
    private fun text(parameter: EventParameter) = inputs.add(Pair(parameter.name, text))

    public fun email(property: KProperty1<*, StringContainer?>): Unit { inputs.add(Pair(property.name, email)) }
    private fun email(parameter: EventParameter) = inputs.add(Pair(parameter.name, email))

    public fun password(property: KProperty1<*, StringContainer?>): Unit { inputs.add(Pair(property.name, password)) }
    private fun password(parameter: EventParameter) = inputs.add(Pair(parameter.name, password))

    public fun number(property: KProperty1<*, DataContainer<*>?>): Unit { inputs.add(Pair(property.name, number)) }
    private fun number(parameter: EventParameter) = inputs.add(Pair(parameter.name, number))

    public fun checkbox(property: KProperty1<*, BooleanContainer?>): Unit { inputs.add(Pair(property.name, checkBox)) }
    private fun checkbox(parameter: EventParameter) = inputs.add(Pair(parameter.name, checkBox))

    public fun hidden(property: KProperty1<*, Any?>): Unit { inputs.add(Pair(property.name, hidden)) }

    public fun selectReference(property: KProperty1<*, ModelID<out Any>?>): Unit { selectReferences.add(property.name) }
    private fun selectReference(parameter: EventParameter) = selectReferences.add(parameter.name)

/*    fun selectEnum(property: KProperty1<*, EnumContainer<*>>) = selectEnums.add(property.name)
    private fun selectEnum(parameter: EventParameter) = selectEnums.add(parameter.name)
 */

    public fun populatedAfterSubmit(property: KProperty1<*, Any?>): Unit { propsPopulatedAfterSubmit.add(property.name) }

    public fun remaining(inHtmlDetails: String? = null): Unit {
        htmlDetailsSummary = inHtmlDetails
        val remaining = parameters.all
            .filter { p -> inputs.none { input -> input.first == p.name } }
            .filter { p -> selectReferences.none { select -> select == p.name } }
            .filter { p -> selectEnums.none { select -> select == p.name } }
            .filter { p -> propsPopulatedAfterSubmit.none { it == p.name } }

        if (inHtmlDetails != null) {
            htmlDetailsContents.addAll(remaining.map { it.name })
        }

        remaining
            .forEach { p ->
                when (p.type) {
                    PropertyType.String -> text(p)
                    PropertyType.Boolean -> checkbox(p)
                    PropertyType.Int -> number(p)
                    PropertyType.Long -> number(p)
                    PropertyType.Float -> number(p)
                    PropertyType.Ref -> selectReference(p)
                    else -> TODO(p.type?.name ?: "Cannot handle this")
                }
            }
    }

    public fun build(
        call: ApplicationCall,
        params: T?,
        reader: Reader<C, *>,
        modelIDSelects: Map<KProperty1<*, ModelID<out Any>?>, ModelCollection<out Any, C>> = emptyMap(),
        // enumSelects: Map<KProperty1<*, EnumContainer<*>>, Array<out Enum<*>>>? = null,
        path: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        translator: Translator
    ): EventForm<T, C> {
        val csrfToken = generateRandomString()
        try {
            call.response.cookies.append(
                Cookie(
                    name = CSRF_TOKEN,
                    value = csrfToken,
                    secure = !isDevelopmentMode(),
                    httpOnly = true,
                    path = "/",
                    maxAge = 3600,
                    extensions = mapOf("SameSite" to "Strict"),
                )
            )
        } catch (e: UnsupportedOperationException) {
            log.error { "The form must be built before call.respond is called" }
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("HTTPS") == true) {
                log.error { "Did you forget to set the property/environment variable DEVELOPMENT_MODE=true ?" }
            }
            throw e
        }
        return EventForm(
            csrfToken,
            inputs,
            populateMissingReferenceSelects(modelIDSelects, reader, inputs),
          //  enumSelects,
            propsPopulatedAfterSubmit,
            params,
            path ?: postPath,
            queryParams,
            htmlDetailsSummary = htmlDetailsSummary,
            htmlDetailsContents = htmlDetailsContents,
            this,
            translator
        )
    }

    private fun populateMissingReferenceSelects(
        developerProvidedModelIDSelects: Map<KProperty1<*, ModelID<out Any>?>, ModelCollection<out Any, C>>,
        reader: Reader<C, *>,
        inputs: List<Pair<String, InputType>>
    ): Set<ReferencePropertyWithOptions> {
        val result = mutableSetOf<ReferencePropertyWithOptions>()
        parameters.all
            .filter { it.raw.type.withNullability(false).isSubtypeOf(ModelID::class.starProjectedType) }
            .map { eventParameter ->
                val ls = klerk.config.getValidationCollectionFor(eventWithParameters.eventReference, eventParameter)
                    ?: return@map
                val options = reader.query(ls, QueryOptions(maxItems = 300)).items
                if (options.size >= 300) {
                    TODO("Too many options")
                } else {
                    result.add(ReferencePropertyWithOptions(eventParameter.name, eventParameter.isNullable, options))
                }
            }

        result.addAll(developerProvidedModelIDSelects.map { entry ->
            ReferencePropertyWithOptions(
                entry.key.name, entry.key.returnType.isMarkedNullable,
                reader.query(entry.value).items
            )
        }
        )
        return result
    }

    internal fun validate() {
        if (klerk.config.getParameters(eventWithParameters.eventReference) != eventWithParameters.parameters) {
            log.warn { "Trying to make a form for an event that doesn't match the parameters" }
        }

        parameters.all.forEach {
            if (it.isNullable && it.type == PropertyType.Boolean) {
                throw IllegalArgumentException("Nullable Booleans are not supported yet")  // how do we know if a field is false or null?
            }
            it.validate()
        }

        val missing = parameters.all
            .filterNot { prop -> inputs.map { i -> i.first }.contains(prop.name) }
            .filterNot { prop -> selectReferences.contains(prop.name) }
            .filterNot { prop -> selectEnums.contains(prop.name) }
            .filterNot { prop -> propsPopulatedAfterSubmit.contains(prop.name) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Form for class ${parameters.raw} is missing declaration for ${
                    missing.map { it.name }.joinToString(", ")
                }"
            )
        }
        val tooMany = inputs        // also selects and other stuff...
            .map { i -> i.first }
            .filterNot { i -> parameters.all.map { it.name }.contains(i) }
        if (tooMany.isNotEmpty()) {
            throw IllegalStateException(
                "Form for class ${parameters.raw} contains too many declarations: ${
                    tooMany.joinToString(
                        ", "
                    )
                }"
            )
        }
    }

    public suspend fun parse(
        call: ApplicationCall,
        populatedAfterSubmit: Map<KProperty1<*, Any?>, DataContainer<*>> = emptyMap(),      // not only DataContainer, also references. Collections?
    ): ParseResult<T> {
        val csrfCookie = call.request.cookies[CSRF_TOKEN]
        val callParams = call.receiveParameters()
        val csrfHiddenInput = callParams[CSRF_TOKEN]
        if (csrfCookie == null || csrfHiddenInput == null || csrfCookie != csrfHiddenInput) {
            log.info { "CSRF check failed" }
            throw SecurityException("CSRF check failed")
        }
        val key = callParams[IDEMPOTENCE_KEY]?.let { CommandToken.from(it) }
            ?: throw java.lang.IllegalArgumentException("Missing input: $IDEMPOTENCE_KEY")

        callParams.forEach { name, _ ->
            if (name != CSRF_TOKEN && name != IDEMPOTENCE_KEY && inputs.none { it.first == name } && selectReferences.none { it == name } && selectEnums.none { it == name }) {
                throw IllegalArgumentException("Parameter $name is not expected to be present in request")
            }
        }

        val allParams = callParams.plus(ParametersBuilder().apply {
            populatedAfterSubmit.forEach { p -> append(p.key.name, p.value.valueWithoutAuthorization.toString()) }
        }.build())



        // someday maybe: https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#verifying-origin-with-standard-headers
        try {
            @Suppress("UNCHECKED_CAST")
            val paramsClass = createParamClassFromCallParameters(parameters.raw, allParams) as T
            val propertyValidationProblems =
                emptySet<InvalidParametersProblem>()
            if (propertyValidationProblems.isNotEmpty()) {
                return Invalid(propertyValidationProblems)
            }

            if (call.request.queryParameters["dryRun"]?.equals("true") == true) {
                return DryRun(paramsClass, key)
            }
            /*  val validationProblems = paramsClass.validate()
        if (call.request.queryParameters["dry-run"]?.equals("true") == true) {
            return Validation(validationProblems)
        }
        if (validationProblems.isEmpty()) {
            return ParseSuccess(paramsClass)
        }
        return Invalid(validationProblems)

       */
            return Parsed(paramsClass, key)
        } catch (e: Exception) {
            return Invalid(setOf(InvalidParametersProblem(e)))
        }
    }

    public fun labelProvider(labelProvider: (UIElementData) -> String?) {
        this.labelProvider = labelProvider
    }

    private class TextInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, StringContainer?>,
        val labelText: String,
        val datatypeValue: StringContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //val initialValue = params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as StringValue

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.value
                required = !property.returnType.isMarkedNullable
                datatypeValue.minLength?.apply { minLength = this.toString() }
                datatypeValue.maxLength?.apply { maxLength = this.toString() }
                if (datatypeValue.regexPattern != null) {
                    pattern = datatypeValue.regexPattern!!.toString()
                } // for some reason, the apply didn't work
            }
        }

        override fun getName() = property.name
    }

    private class IntInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: IntContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //val initialValue = (params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as Datatype<*>).value.toString()

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.value.toString()
                required = !property.returnType.isMarkedNullable
                datatypeValue.min?.apply { min = this.toString() }
                datatypeValue.max?.apply { max = this.toString() }
            }
        }

        override fun getName() = property.name
    }

    private class LongInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: LongContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //val initialValue = (params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as Datatype<*>).value.toString()

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.value.toString()
                required = !property.returnType.isMarkedNullable
                datatypeValue.min?.apply { min = this.toString() }
                datatypeValue.max?.apply { max = this.toString() }
            }
        }

        override fun getName() = property.name
    }

    private class CheckboxInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: BooleanContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //  val initialValue = params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as BooleanValue

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                checked = datatypeValue.value
                //  required = !property.returnType.isMarkedNullable
            }
        }

        override fun getName() = property.name
    }

    private class DateItem<T>(
        val property: KProperty1<*, String?>,
        val labelText: String,
        val minFunction: (() -> LocalDate)?
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.dateInput {
                id = property.name
                name = property.name
                required = !property.returnType.isMarkedNullable
                if (minFunction != null) {
                    min = minFunction.invoke().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
                }
                // maxFunction
            }
        }

        override fun getName() = property.name
    }

    private class TextAreaItem<T>(
        val property: KProperty1<*, String?>,
        val labelText: String,
        val rowsSettings: Int = 10,
        val columns: Int = 30
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.textArea {
                id = property.name
                name = property.name
                required = !property.returnType.isMarkedNullable
                rows = rowsSettings.toString()
                cols = columns.toString()
            }
        }

        override fun getName() = property.name
    }

    private class HiddenItem<T>(val property: KProperty1<*, Any?>, val valueString: String) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.hiddenInput {
                id = property.name
                name = property.name
                value = valueString
            }
        }

        override fun getName() = property.name
    }

    internal interface Item<T> {
        fun render(tag: HtmlBlockTag, params: T)
        fun getName(): String
    }

    public companion object {
        public suspend fun respondInvalid(result: Invalid<*>, call: ApplicationCall) {
            if (call.request.queryParameters["onlyErrors"]?.equals("true") == true) {
                call.respond(HttpStatusCode.BadRequest, createBody(result.problems))
            } else {
                call.respondHtml {
                    body {
                        h1 { +"Problem" }
                        +(result.problems.first().toString())
                        form {
                            button {
                                onClick = "history.back();"
                                +"Back"
                            }
                        }
                    }
                }
            }
        }

        public suspend fun respondDryRun(call: ApplicationCall): Unit = call.respond(HttpStatusCode.OK)

        private fun createBody(invalidParametersProblems: Set<InvalidParametersProblem>): String {
            val problems = mutableListOf<ValidationProblemResponse>()
            val fieldsMustBeNull =
                invalidParametersProblems.filter { it.fieldsMustBeNull != null }
                    .flatMap { it.fieldsMustBeNull ?: emptySet() }
                    .map { it.name }.toSet()
            val fieldsMustNotBeNull = invalidParametersProblems.filter { it.fieldsMustNotBeNull != null }
                .flatMap { it.fieldsMustNotBeNull ?: emptySet() }.map { it.name }.toSet()
            invalidParametersProblems.forEach {
                if (it.fieldsMustNotBeNull == null && it.fieldsMustBeNull == null) {
                    // We can't do anything but show the problem to the user
                    problems.add(ValidationProblemResponse(humanReadable = it.toString(), field = null))
                }
            }
            val response = ValResponse(
                problems = problems,
                fieldsMustBeNull = fieldsMustBeNull,
                fieldsMustNotBeNull = fieldsMustNotBeNull
            )
            return Gson().toJson(response)
        }
    }
}

internal data class ValResponse(
    val problems: List<ValidationProblemResponse>,
    val fieldsMustBeNull: Set<String>,
    val fieldsMustNotBeNull: Set<String>
)

public data class ValidationProblemResponse(public val field: String?, public val humanReadable: String)

public class EventForm<T : Any, C : KlerkContext>(
    private val csrfToken: String,
    private val inputs: List<Pair<String, InputType>>,
    private val referenceSelects: Set<ReferencePropertyWithOptions>,
 //   private val enumSelects: Map<KProperty1<*, EnumContainer<*>>, Array<out Enum<*>>>?,
    private val propsPopulatedAfterSubmit: List<String>,
    private val params: T?,
    private val postPath: String?,
    private val queryParams: Map<String, String>,
    private val htmlDetailsSummary: String?,
    private val htmlDetailsContents: Set<String>,
    private val template: EventFormTemplate<T, C>,
    private val translator: Translator

) {
    private val log = KotlinLogging.logger {}

    private fun renderReferenceSelect(prop: ReferencePropertyWithOptions, params: T?): HtmlBlockTag.() -> Unit = {
        label {
            id = "label-${prop.propertyName}"
            htmlFor = prop.propertyName
            /*    if (!enabled) {
                    style = "opacity: 0.5;"
                }
             */
            +camelCaseToPretty(prop.propertyName)
        }
        br()
        select {
            name = prop.propertyName
            if (prop.propertyNullable) {
                option {
                    value = ""
                    +"(none)"
                }
            }
            optGroup() {
                prop.options.forEach { option ->
                    option {
                        value = option.id.toString()
                        params?.let {
                            val paramValue = getModelIdValue(prop.propertyName, params)
                            selected = paramValue == option.id.toInt()
                        }
                        +option.toString()
                    }
                }
            }
        }
    }
/*
    private fun renderEnumSelect(
        property: KProperty1<*, EnumContainer<*>>,
        options: Array<out Enum<*>>,
        params: T
    ): HtmlBlockTag.() -> Unit = {
        val paramValue = getEnumValue(property.name, params)
        label {
            id = "label-${property.name}"
            htmlFor = property.name
            /*    if (!enabled) {
                    style = "opacity: 0.5;"
                }
             */
            +camelCaseToPretty(property.name)
        }
        select {
            name = property.name
            if (property.returnType.isMarkedNullable) {
                option {
                    value = ""
                    +"(none)"
                }
            }
            optGroup() {
                options.forEach {
                    option {
                        value = it.name
                        selected = paramValue == it.name
                        +it.toString()
                    }
                }
            }
        }
    }
 */

    private fun renderInput(
        propertyName: String,
        type: InputType,
        parameters: EventParameters<T>,
        params: T?
    ): HtmlBlockTag.() -> Unit =
        {
            val value = if (params == null) null else getParamDatatype(propertyName, params)
            when (type) {
                text -> this.apply(
                    renderTextInput(
                        propertyName,
                        value,
                        text,
                        getNewInstance(propertyName, parameters)  // since value can be null
                    )
                )

                email -> this.apply(
                    renderTextInput(
                        propertyName,
                        value,
                        email,
                        getNewInstance(propertyName, parameters)  // since value can be null
                    )
                )

                password -> this.apply(
                    renderTextInput(
                        propertyName,
                        value,
                        password,
                        getNewInstance(propertyName, parameters)  // since value can be null
                    )
                )

                number -> {
                    val newInstance = getNewInstance(propertyName, parameters)  // since value can be null
                    when (newInstance) {
                        is IntContainer -> this.apply(renderIntNumberInput(propertyName, value, newInstance))
                        is LongContainer -> this.apply(renderLongNumberInput(propertyName, value, newInstance))
                        is FloatContainer -> this.apply(renderFloatNumberInput(propertyName, value, newInstance))
                    }
                }

                checkBox -> this.apply(
                    renderCheckboxInput(
                        propertyName,
                        value,
                        getNewInstance(propertyName, parameters)
                    )
                )

                InputType.hidden -> {
                    requireNotNull(params) { "Params cannot be null when there are hidden inputs" }
                    val modelId = getModelId(propertyName, params)
                    val valueAsString: String? =
                        modelId?.toString() ?: getParamDatatype(propertyName, params)?.toString()
                    this.apply(renderHiddenInput(propertyName, valueAsString))
                }

                else -> TODO(type.name)
            }
        }

    private fun getModelId(propertyName: String, params: T): ModelID<*>? {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return try {
            prop.getter.call(params) as ModelID<*>
        } catch (e: Exception) {
            null
        }
    }

    private fun createLabel(
        propertyName: String,
        //typeInstance: DataContainer<*>,
        enabled: Boolean
    ): HtmlBlockTag.() -> Unit = {
        //val elementData = UIElementData(propertyName, typeInstance, enabled)
        label {
            id = "label-$propertyName"
            htmlFor = propertyName
            //if (!enabled) {
            //  style = "opacity: 0.5;"
            //}
            //  +(template.labelProvider?.invoke(elementData) ?: camelCaseToPretty(propertyName))
//            +translator.translateProperty(propertyName)
            val property = template.parameters.raw.declaredMemberProperties.single { it.name == propertyName }
            +translator.property(property)
            //+camelCaseToPretty(propertyName)
        }
        br()
    }

    private fun renderHiddenInput(propertyName: String, theValue: String?): HtmlBlockTag.() -> Unit = {
        input(InputType.hidden) {
            id = propertyName
            name = propertyName
            theValue?.let {
                value = theValue
            }
        }
    }

    private fun renderCheckboxInput(
        propertyName: String,
        theValue: DataContainer<*>?,
        newInstance: DataContainer<*>
    ): HtmlBlockTag.() -> Unit = {
        apply(createLabel(propertyName, (theValue != null)))
        theValue as BooleanContainer?
        input(checkBox) {
            id = propertyName
            name = propertyName
            value = theValue?.toString() ?: ""
            checked = theValue?.value ?: false
            //  disabled = theValue == null
        }
        // required = !property.returnType.isMarkedNullable
    }

    private fun renderIntNumberInput(
        propertyName: String,
        theValue: DataContainer<*>?,
        newInstance: DataContainer<*>
    ): HtmlBlockTag.() -> Unit = {
        apply(createLabel(propertyName, (theValue != null)))
        theValue as IntContainer?
        newInstance as IntContainer
        input(number) {
            id = propertyName
            name = propertyName
            value = theValue?.toString() ?: ""
            // required = !property.returnType.isMarkedNullable
            newInstance.min?.apply { min = this.toString() }
            newInstance.max?.apply { max = this.toString() }
        }
    }


    private fun renderLongNumberInput(
        propertyName: String,
        theValue: DataContainer<*>?,
        newInstance: DataContainer<*>
    ): HtmlBlockTag.() -> Unit = {
        apply(createLabel(propertyName, (theValue != null)))
        theValue as LongContainer?
        newInstance as LongContainer
        input(number) {
            id = propertyName
            name = propertyName
            value = theValue?.toString() ?: ""
            // required = !property.returnType.isMarkedNullable
            newInstance.min?.apply { min = this.toString() }
            newInstance.max?.apply { max = this.toString() }
        }
    }

    private fun renderFloatNumberInput(
        propertyName: String,
        theValue: DataContainer<*>?,
        newInstance: DataContainer<*>
    ): HtmlBlockTag.() -> Unit = {
        apply(createLabel(propertyName, (theValue != null)))
        theValue as FloatContainer?
        newInstance as FloatContainer
        input(number) {
            id = propertyName
            name = propertyName
            value = theValue?.toString() ?: ""
            // required = !property.returnType.isMarkedNullable
            newInstance.min?.apply { min = this.toString() }
            newInstance.max?.apply { max = this.toString() }
            step = "any"
        }
    }

    private fun renderTextInput(
        propertyName: String,
        theValue: DataContainer<*>?,
        type: InputType,
        newInstance: DataContainer<*>
    ): HtmlBlockTag.() -> Unit = {
        apply(createLabel(propertyName, (theValue != null)))
        theValue as StringContainer?
        newInstance as StringContainer
        input(type) {
            id = propertyName
            name = propertyName
            value = theValue?.toString() ?: ""
            // disabled = theValue == null
            newInstance.minLength?.let {
                minLength = it.toString()
                required = it > 0
            }
            newInstance.maxLength?.let { maxLength = it.toString() }
            if (newInstance.regexPattern != null) {
                pattern = newInstance.regexPattern!!.toString()
            } // for some reason, the apply didn't work
        }
    }

    private fun getParamDatatype(propertyName: String, params: T): DataContainer<*>? {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return prop.getter.call(params) as? DataContainer<*>
    }

    private fun getModelIdValue(propertyName: String, params: T): Int? {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return (prop.getter.call(params) as? ModelID<*>)?.toInt()
    }

/*    private fun getEnumValue(propertyName: String, params: T): String {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return (prop.getter.call(params) as? EnumContainer<*>)?.value?.name ?: throw IllegalArgumentException()
    }
 */

    private fun getNewInstance(propertyName: String, eventParameters: EventParameters<*>): DataContainer<*> {
        val prop = eventParameters.all.single { it.name == propertyName }.raw
        val clazz = prop.type.withNullability(false).classifier as KClass<*>
        try {
            if (clazz.isSubclassOf(StringContainer::class)) {
                return clazz.constructors.first().call("") as DataContainer<*>
            }
            if (clazz.isSubclassOf(IntContainer::class)) {
                return clazz.constructors.first().call(0) as DataContainer<*>
            }
            if (clazz.isSubclassOf(LongContainer::class)) {
                return clazz.constructors.first().call(0L) as DataContainer<*>
            }
            if (clazz.isSubclassOf(FloatContainer::class)) {
                return clazz.constructors.first().call(0f) as DataContainer<*>
            }
            if (clazz.isSubclassOf(BooleanContainer::class)) {
                return clazz.constructors.first().call(false) as DataContainer<*>
            }
            if (clazz.isSubclassOf(Enum::class)) {
                return clazz.constructors.first().call(false) as DataContainer<*>
            }
            if (clazz.isSubclassOf(ModelID::class)) {
                return clazz.constructors.first().call(ModelID<Any>(0)) as DataContainer<*>
            }
            TODO("cannot handle $clazz")
        } catch (e: InstantiationException) {
            log.error(
                "Double check that your parameter class only consists of Datatypes and ModelIds (or set, list thereof). Note that it cannot be abstract!",
                e
            )
            throw e
        }
    }


    /**
     * Renders the form in the provided tag.
     * Note that only one form may be rendered per page (otherwise you will get a CSRF-token problem).
     */
    public fun render(tag: HtmlBlockTag, postPath: String? = null): Unit {
        try {
            val path = getPath(postPath, queryParams)
            tag.script { unsafe { +generateValidationScript(path) } }
            tag.form(path, method = FormMethod.post) {
                id = "eventForm"
                onChange = "validate()"
                +System.lineSeparator()

                // csrf-token should be placed before non-hidden inputs (see https://portswigger.net/web-security/csrf/preventing#how-should-csrf-tokens-be-transmitted)
                hiddenInput(name = CSRF_TOKEN) { value = csrfToken }

                +System.lineSeparator()
                hiddenInput(name = IDEMPOTENCE_KEY) { value = CommandToken.simple().toString() }
                +System.lineSeparator()
                inputs.filterNot { htmlDetailsContents.contains(it.first) }.forEach {
                    p { tag.apply(renderInput(it.first, it.second, template.parameters, params)) }
                    +System.lineSeparator()
                }
                referenceSelects.forEach { refSelect ->
                    p { tag.apply(renderReferenceSelect(refSelect, params)) }
                }

/*                enumSelects?.forEach { enumSelect ->
                    val definedInput = inputs.singleOrNull { it.first == enumSelect.key.name }
                    if (definedInput != null && definedInput.second != InputType.hidden) { // not tested
                        tag.apply(renderEnumSelect(enumSelect.key, enumSelect.value, requireNotNull(params)))
                    }
                }
 */
                if (htmlDetailsContents.isNotEmpty()) {
                    details {
                        summary { +(htmlDetailsSummary ?: "Details") }
                        inputs.filter { htmlDetailsContents.contains(it.first) }.forEach {
                            tag.apply(renderInput(it.first, it.second, template.parameters, params))
                        }
                    }
                }
                div {
                    id = "errormessages"
                    attributesMapOf(key = "aria-live", value = "assertive")
                    style = "color:red;"
                }

                submitInput { value = "Ok" }
            }
        } catch (e: Exception) {
            log.error(e) { "Could not render template" }
        }
    }

    private fun getPath(postPath: String?, queryParams: Map<String, String>): String {
        var path = requireNotNull(
            postPath ?: this.postPath
        ) { "postPath must be provided, either when building the form or when rendering the form" }
        if (path.contains("?")) {
            path += "&"
        } else {
            path += "?"
        }
        return "$path${queryParams.map { "${it.key}=${it.value}" }.joinToString("&")}"
    }

    private fun generateValidationScript(postPath: String) = """
    function validate() {
        const form = document.getElementById("eventForm")
        const XHR = new XMLHttpRequest();
        const FD = new FormData(form);

        XHR.addEventListener("load", (event) => {
            document.getElementById("errormessages").replaceChildren();
            if (event.target.status == 200) {
                return;
            }
            if (event.target.response) {
                const response = JSON.parse(event.target.response);
                showHumanErrorMessage(response);
                toggleNullableFields(response);
            }
        });

        XHR.addEventListener("error", (event) => {
            console.log('Oops! Validation went wrong.');
        });

        XHR.open("POST", "$postPath${if (postPath.contains("?")) "&" else "?"}dryRun=true&onlyErrors=true");
        XHR.send(FD);
    }
    
    function showHumanErrorMessage(response) {
                    const element = document.getElementById("errormessages");
                response.problems
                    .map(p => p.humanReadable)
                    .forEach(text => {
                      const paragraph = document.createElement("p");
                      paragraph.innerText = text;
                          element.append(paragraph);
                    });
}

function toggleNullableFields(response) {
    response.fieldsMustBeNull.forEach(f => {
        document.getElementById(f).disabled = true;
        document.getElementById("label-" + f).style = "opacity: 0.5;";
    });

        response.fieldsMustNotBeNull.forEach(f => {
            document.getElementById(f).disabled = false;
            document.getElementById("label-" + f).style = "opacity: 1.0;";
    });

}
""".trimIndent()
}

public sealed class ParseResult<T> {
    public data class Invalid<T>(val problems: Set<InvalidParametersProblem>) : ParseResult<T>()
    public data class DryRun<T>(val params: T, val key: CommandToken) : ParseResult<T>()
    public data class Parsed<T>(val params: T, val key: CommandToken) : ParseResult<T>()
}

public data class ReferencePropertyWithOptions(
    val propertyName: String,
    val propertyNullable: Boolean,
    val options: List<Model<out Any>>
)

private fun createParamClassFromCallParameters(parameterClass: KClass<*>, callParams: Parameters): Any {
    val constructors = parameterClass.constructors
    val parameters = mutableMapOf<KParameter, Any?>()

    constructors.first().parameters
        .forEach {
            val value = valueWithCorrectType(callParams[it.name!!], it.type)
            if (value != null) {
                parameters[it] = value
                //    log.debug { "set ${it.name} to $value}" }
            } else {
                if (it.type.isMarkedNullable) {
                    parameters[it] = value
                } else {
                    // throw IllegalArgumentException("${it.name} is null but it is not nullable and it is not optional")
                }
            }

        }
    return constructors.first().callBy(parameters)
}

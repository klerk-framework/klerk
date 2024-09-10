package dev.klerkframework.plugins

import com.google.gson.Gson
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.actions.JobContext
import dev.klerkframework.klerk.actions.JobId
import dev.klerkframework.klerk.actions.JobResult
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.log.Fact
import dev.klerkframework.klerk.log.LogEntry
import dev.klerkframework.klerk.log.LogSource
import dev.klerkframework.klerk.log.MajorSource
import dev.klerkframework.klerk.misc.dateFormatter
import dev.klerkframework.klerk.statemachine.Block
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.plugins.PostmarkEmailService.CreateEmail
import dev.klerkframework.plugins.PostmarkEmailService.PluginPostmarkEmailStates.*
import dev.klerkframework.plugins.PostmarkEmailService.SendEmail
import dev.klerkframework.webutils.AdminUIPluginIntegration
import dev.klerkframework.webutils.LowCodeConfig
import dev.klerkframework.webutils.PluginPage
import dev.klerkframework.webutils.lowCodeHtmlHead
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.*
import kotlinx.html.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private lateinit var _apiKey: String
private var _serverID: Int? = null
private lateinit var _messageStream: String

/**
 * Plugin for sending transactional email via Postmark.
 *
 * Adds a block-function 'sendEmail' so that it is easy to trigger emails via your state machines:
 * ```
 * onEvent(ApproveInvoice) {
 *    sendEmail(::generateNotificationEmail)
 *    transitionTo(Approved)
 * }
 * ```
 *
 * It is also possible to send via a normal Klerk command:
 * ```
 * klerk.handle(
 *     Command(
 *         event = PostmarkEmailService.CreateEmail,
 *         model = null,
 *         params = CreateEmailParams.from(PostmarkEmail(
 *         ...
 * ```
 *
 * When an email should be sent, a PluginPostmarkEmail model is and a Job is created to process the email in a
 * fault-tolerant way. The model will be deleted some time after the email has been processed.
 *
 * @param serverID is not required, but there will be no link in AdminUI to the email details page on postmarkapp.com
 * unless it is provided.
 */
public class PostmarkEmailService<C : KlerkContext, V>(
    apiKey: String,
    private val webhooksAuth: PostmarkEmailWebhooksAuth,
    serverID: Int? = null,
    public override val defaultFromAddress: BasicEmail.EmailAndName,
    messageStream: String = "outbound",
) : AdminUIPluginIntegration<C, V>,
    EmailSender<C, V> {

    private val emailCollections = ModelCollections<PluginPostmarkEmail, C>()
    private lateinit var _klerk: Klerk<C, V>

    init {
        _apiKey = apiKey
        _serverID = serverID
        _messageStream = messageStream
    }

    override val name: String = "PostmarkEmail"
    override val description: String = "Provides email sending and managing transactional emails."

    override fun start(klerk: Klerk<C, V>) {
        _klerk = klerk
        checkNotNull(klerk.config.contextProvider) { "You must have a contextProvider in the configuration" }
    }

    override fun mergeConfig(previous: Config<C, V>): Config<C, V> {
        val managedModels = previous.managedModels.toMutableSet()
        managedModels.add(
            ManagedModel(
                PluginPostmarkEmail::class, createPluginPostmarkEmailStatemachine(), emailCollections
            )
        )
        return previous.copy(managedModels = managedModels)
    }

    public enum class PluginPostmarkEmailStates {
        Preparing, TransferredToPostmark, Delivered, Completed
    }

    private fun createPluginPostmarkEmailStatemachine(): StateMachine<PluginPostmarkEmail, Enum<*>, C, V> =
        stateMachine {
            event(CreateEmail) {}

            event(SendEmail) {}

            event(Deliver) {}

            event(Bounce) {}

            event(SpamComplaint) {}

            voidState {
                onEvent(CreateEmail) {
                    createModel(Preparing, ::newEmail)
                }
            }

            state(Preparing) {
                onEnter {
                    job(::sendEmailJob)
                }

                onEvent(SendEmail) {
                    update(::emailWasSent)
                    transitionTo(TransferredToPostmark)
                }
            }

            state(TransferredToPostmark) {
                onEvent(Deliver) {
                    update(::delivered)
                    transitionTo(Completed)
                }

                onEvent(Bounce) {
                    update(::bounced)
                    transitionTo(Completed)
                }

                onEvent(SpamComplaint) {
                    update(::spamComplaint)
                    transitionTo(Completed)
                }

            }

            state(Completed) {
                after(2.minutes) {
                    delete()
                }
            }
        }

    public object CreateEmail : VoidEventWithParameters<PluginPostmarkEmail, CreateEmailParams>(
        isExternal = false, forModel = PluginPostmarkEmail::class, parametersClass = CreateEmailParams::class
    )

    public object SendEmail : InstanceEventWithParameters<PluginPostmarkEmail, SendEmailParams>(
        isExternal = false, forModel = PluginPostmarkEmail::class, parametersClass = SendEmailParams::class
    )

    public object Deliver : InstanceEventNoParameters<PluginPostmarkEmail>(
        isExternal = false, forModel = PluginPostmarkEmail::class
    )

    public object Bounce : InstanceEventNoParameters<PluginPostmarkEmail>(
        isExternal = false, forModel = PluginPostmarkEmail::class
    )

    public object SpamComplaint : InstanceEventNoParameters<PluginPostmarkEmail>(
        isExternal = false, forModel = PluginPostmarkEmail::class
    )


    override val page: PluginPage<C, V> = EmailPluginPage(emailCollections, webhooksAuth)

    override fun registerExtraRoutes(routing: Routing, basePath: String) {
        with(routing) {
            post("$basePath/plugin/postmarkemail/webhook") {
                val text = call.receiveText()
                val parsed = Gson().fromJson(text, WebhookRequest::class.java)
                if (parsed == null) {
                    logger.error { "Could not parse $text" }
                    call.respond(status = HttpStatusCode.InternalServerError, "")
                    return@post
                }
                call.respond(status = HttpStatusCode.OK, "")
                val plugin = _klerk.config.plugins.filterIsInstance<PostmarkEmailService<C, *>>().single()
                val context = _klerk.config.contextProvider!!.invoke(dev.klerkframework.klerk.PluginIdentity(plugin))

                val model = _klerk.read(context) {
                    firstOrNull(emailCollections.all) { it.props.messageID?.string == parsed.MessageID }
                }
                if (model == null) {
                    logger.error { "Got a delivery notification for an email that doesn't exist: ${parsed.MessageID}" }
                    return@post
                }
                val command = when (parsed.RecordType) {
                    "Delivery" -> Command(
                        event = Deliver,
                        model = model.id,
                        params = null,
                    )

                    "Bounce" -> Command(
                        event = Bounce,
                        model = model.id,
                        params = null,
                    )

                    "SpamComplaint" -> Command(
                        event = SpamComplaint,
                        model = model.id,
                        params = null,
                    )

                    else -> null
                }
                if (command == null) {
                    logger.warn { "Unknown RecordType: ${parsed.RecordType}" }
                    return@post
                }
                _klerk.handle(
                    command = command, context = context, options = ProcessingOptions(CommandToken.simple())
                )
            }
        }
    }

    private fun sendEmailJob(args: ArgForInstanceNonEvent<PluginPostmarkEmail, C, V>): List<Job<C, V>> {
        return listOf(PluginPostmarkEmailJob<C, V>(args.model.id))
    }

    private fun emailWasSent(args: ArgForInstanceEvent<PluginPostmarkEmail, SendEmailParams, C, V>): PluginPostmarkEmail {
        return args.model.props.copy(messageID = args.command.params.messageID)
    }

    private fun delivered(args: ArgForInstanceEvent<PluginPostmarkEmail, Nothing?, C, V>): PluginPostmarkEmail =
        args.model.props.copy(status = PluginPostmarkEmailStatus("Delivered"))

    private fun bounced(args: ArgForInstanceEvent<PluginPostmarkEmail, Nothing?, C, V>): PluginPostmarkEmail =
        args.model.props.copy(status = PluginPostmarkEmailStatus("Bounced"))

    private fun spamComplaint(args: ArgForInstanceEvent<PluginPostmarkEmail, Nothing?, C, V>): PluginPostmarkEmail =
        args.model.props.copy(status = PluginPostmarkEmailStatus("Bounced"))

    override suspend fun sendEmail(email: BasicEmail, context: C): Result<JobId> {
        val result = _klerk.handle(
            Command(
                event = CreateEmail,
                model = null,
                params = CreateEmailParams(PluginPostmarkEmailString(Gson().toJson(PostmarkEmail.from(email))))
            ),
            context,
            options = ProcessingOptions(CommandToken.simple())
        )
        return when (result) {
            is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> Result.failure(result.problem.asException())
            is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> Result.success(result.jobs.single().id)
        }
    }

    override fun getSendEmailCommand(email: BasicEmail): Command<out Any, out Any> {
        return Command(
            event = CreateEmail,
            model = null,
            params = CreateEmailParams(PluginPostmarkEmailString(Gson().toJson(PostmarkEmail.from(email))))
        )
    }

}

/**
 * Postmark requires special handling of dots. If the name contains dots, this will only return the email.
 */
public fun BasicEmail.EmailAndName.toStringWithoutDots(): String = if (name.contains(".")) email else toString()


public fun <C : KlerkContext, V> newEmail(args: ArgForVoidEvent<PluginPostmarkEmail, CreateEmailParams, C, V>): PluginPostmarkEmail {
    return PluginPostmarkEmail(null, null, args.command.params.email, status = null)
}

/**
 * Sends an email using [PostmarkEmailService]
 */
public fun <T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> Block.VoidEventBlock<T, P, ModelStates, C, V>.sendEmail(
    function: (ArgForVoidEvent<T, P, C, V>) -> PostmarkEmail
) {
    fun createEmailCommand(args: ArgForVoidEvent<T, P, C, V>): List<Command<PluginPostmarkEmail, CreateEmailParams>> {
        val email = function.invoke(args)
        val emailWithMessageStream =
            if (email.MessageStream != null) email else email.copy(MessageStream = _messageStream)
        return listOf(
            Command(
                event = CreateEmail,
                model = null,
                params = CreateEmailParams(PluginPostmarkEmailString(Gson().toJson(emailWithMessageStream)))
            ),
        )
    }
    createCommands(::createEmailCommand)

}

/**
 * Sends an email using [PostmarkEmailService]
 */
public fun <T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> Block.InstanceEventBlock<T, P, ModelStates, C, V>.sendEmail(
    function: (ArgForInstanceEvent<T, P, C, V>) -> PostmarkEmail
) {
    fun createEmailCommand(args: ArgForInstanceEvent<T, P, C, V>): List<Command<PluginPostmarkEmail, CreateEmailParams>> {
        val email = function.invoke(args)
        return listOf(
            Command(
                event = CreateEmail,
                model = null,
                params = CreateEmailParams(PluginPostmarkEmailString(Gson().toJson(email)))
            ),
        )
    }
    createCommands(::createEmailCommand)
}

/**
 * Sends an email using [PostmarkEmailService]
 */
public fun <T : Any, ModelStates : Enum<*>, C : KlerkContext, V> Block.InstanceNonEventBlock<T, ModelStates, C, V>.sendEmail(
    function: (ArgForInstanceNonEvent<T, C, V>) -> PostmarkEmail
) {
    fun createEmailCommand(args: ArgForInstanceNonEvent<T, C, V>): List<Command<PluginPostmarkEmail, CreateEmailParams>> {
        val email = function.invoke(args)
        return listOf(
            Command(
                event = CreateEmail,
                model = null,
                params = CreateEmailParams(PluginPostmarkEmailString(Gson().toJson(email)))
            ),
        )
    }
    createCommands(::createEmailCommand)
}

private const val baseUrl = "https://api.postmarkapp.com"

public class PluginPostmarkEmailJob<C : KlerkContext, V>(private val emailID: ModelID<PluginPostmarkEmail>) : Job<C, V> {
    override val id: JobId = Random.nextLong()

    override suspend fun run(jobContext: JobContext<C, V>): JobResult {
        val plugin = jobContext.klerk.config.plugins.filterIsInstance<PostmarkEmailService<C, *>>().single()
        val context = jobContext.klerk.config.contextProvider!!.invoke(dev.klerkframework.klerk.PluginIdentity(plugin))
        val email = jobContext.klerk.read(context) { get(emailID) }

        val response = client.post("$baseUrl/email") {
            headers {
                append("Accept", JSON)
                append("Content-Type", JSON)
                append("X-Postmark-Server-Token", _apiKey)
            }
            setBody(email.props.email.string)
        }
        val result = when {
            response.status.isSuccess() -> JobResult.Success
            response.status.value >= 500 -> JobResult.Retry
            else -> JobResult.Fail
        }
        val message = response.bodyAsText()
        val parsedMessage = Gson().fromJson(message, SendEmailApiResponse::class.java)
        when (result) {
            JobResult.Success -> {
                jobContext.klerk.log.add(LogPluginPostmarkEmailSucceeded(null))
                jobContext.klerk.handle(
                    Command(
                        event = SendEmail,
                        model = emailID,
                        params = SendEmailParams(messageID = PluginPostmarkEmailMessageID(parsedMessage.MessageID)),
                    ), context = context, options = ProcessingOptions(CommandToken.simple())
                )
            }

            JobResult.Retry -> {
                logger.warn { "Problem sending email. Response status: ${response.status}. Message: ${message}" }
                jobContext.klerk.log.add(LogPluginPostmarkEmailFailed(true, null, response.status, message))
            }

            JobResult.Fail -> {
                logger.error { "Problem sending email. Response status: ${response.status}. Message: ${message}" }
                jobContext.klerk.log.add(LogPluginPostmarkEmailFailed(false, null, response.status, message))
            }
        }
        println(result)
        return result
    }

}

public data class PostmarkEmail(
    public val From: String,
    public val To: String,
    public val Cc: String? = null,
    public val Bcc: String? = null,
    public val Subject: String,
    public val HtmlBody: String?,
    public val TextBody: String?,
    public val MessageStream: String? = null
) {
    internal companion object {
        fun from(basic: BasicEmail): PostmarkEmail =
            PostmarkEmail(
                From = basic.from.toStringWithoutDots(),
                To = basic.to.joinToString(",") { it.toStringWithoutDots() },
                Cc = basic.cc.joinToString(",") { it.toStringWithoutDots() },
                Bcc = basic.bcc.joinToString(",") { it.toStringWithoutDots() },
                Subject = basic.subject,
                HtmlBody = basic.htmlBody,
                TextBody = basic.textBody,
            )
    }
}

private val client = HttpClient(CIO)
private const val JSON = "application/json"


public class LogPluginPostmarkEmailSucceeded(context: KlerkContext?) : LogEntry {
    override val time: Instant = context?.time ?: Clock.System.now()
    override val actor: dev.klerkframework.klerk.ActorIdentity? = context?.actor
    override val source: LogSource = LogSource(MajorSource.Plugin, "PostmarkEmail")
    override val logEventName: String = this::class.simpleName!!
    override val headingTemplate: String = "Email was sent."
    override val contentTemplate: String? = null
    override val facts: List<Fact> = emptyList()
}

public class LogPluginPostmarkEmailFailed(
    willRetry: Boolean, context: KlerkContext?, status: HttpStatusCode, message: String
) : LogEntry {
    override val time: Instant = context?.time ?: Clock.System.now()
    override val actor: dev.klerkframework.klerk.ActorIdentity? = context?.actor
    override val source: LogSource = LogSource(MajorSource.Plugin, "PostmarkEmail")
    override val logEventName: String = this::class.simpleName!!
    override val headingTemplate: String =
        "Email could not be sent. ${if (willRetry) "Will retry." else "Will not retry."}"
    override val contentTemplate: String = "HTTP-status: ${status}. Message: $message\"}"
    override val facts: List<Fact> = emptyList()
}

public class EmailPluginPage<C : KlerkContext, V>(
    private val emailCollections: ModelCollections<PluginPostmarkEmail, C>,
    private val webhooksAuth: PostmarkEmailWebhooksAuth
) : PluginPage<C, V> {
    override val buttonText: String = "Email"
    override suspend fun render(
        call: ApplicationCall, config: LowCodeConfig<C>, klerk: Klerk<C, V>
    ): Unit {
        val context = config.contextProvider.invoke(call)
        val subpage = call.request.queryParameters["subpage"]
        val statistics = if (subpage == "stats") getStats() else null
        val webhooksJson = if (subpage == "config") getWebhooks() else null
        val gson = klerk.config.gson.newBuilder().setPrettyPrinting().serializeNulls().create()
        val webhooksPretty = gson.toJson(gson.fromJson<Map<String, Any>>(webhooksJson, Map::class.java))

        val queryParamSubpage = call.request.uri.split("&").find { it.startsWith("subpage=") }
        val pageBaseUrl =
            if (queryParamSubpage == null) call.request.uri else call.request.uri.replace(queryParamSubpage, "")
        val listOfEmails = if (subpage == null) klerk.read(context) { list(emailCollections.all) } else emptyList()

        call.respondHtml {
            apply(lowCodeHtmlHead(config))
            body {
                header {
                    nav { div { a(href = config.basePath) { +"Home" } } }
                }
                main {
                    h1 { +"Postmark Email" }
                    when (subpage) {
                        "stats" -> apply(renderStats(statistics))
                        "config" -> apply(renderConfig(webhooksPretty))
                        else -> apply(renderPage(pageBaseUrl, listOfEmails))
                    }
                }
            }
        }
    }

    private fun renderPage(pageBaseUrl: String, listOfEmails: List<Model<PluginPostmarkEmail>>): MAIN.() -> Unit = {
        p { a(href = "https://status.postmarkapp.com/") { +"Postmark status page" } }
        p { a(href = "${pageBaseUrl}&subpage=stats") { +"Statistics" } }
        p { a(href = "${pageBaseUrl}&subpage=config") { +"Configuration" } }

        if (listOfEmails.isEmpty()) {
            p { +"There are no emails" }
        } else {
            table {
                listOfEmails.forEach {
                    thead {
                        tr {
                            th { +"MessageID" }
                            th { +"State" }
                            th { +"Postmark status" }
                        }
                    }
                    tbody {
                        tr {
                            td { +(it.props.messageID?.string ?: "-") }
                            td { +it.state }
                            td { +(it.props.status?.string ?: "-") }
                            if (_serverID != null) {
                                it.props.messageID?.let {
                                    td {
                                        a(href = "https://account.postmarkapp.com/servers/${_serverID}/streams/outbound/messages/${it.string}") {
                                            target = "_blank"
                                            rel = "noopener noreferrer"
                                            +"Details"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun renderConfig(webhooks: String?): MAIN.() -> Unit = {
        +("Note that if there are no webhooks, the email will still be sent. However we will not receive any information " + "about the delivery status, so they will remain in the 'Sent' state.")
        h3 { +"Configuration in code" }
        ul {
            li { +"apiKey (not shown here as a security measure)" }
            li { +"Webhooks auth (should be the same as below): Username=${webhooksAuth.username} Password=${webhooksAuth.password}" }
            li { +"Default message stream: $_messageStream" }
        }

        h3 { +"Configuration in Postmark account" }
        p {
            +"To change this configuration, login on "
            a(href = "https://account.postmarkapp.com/") { +"your account at postmarkapp.com" }
        }
        h5 { +"Webhooks" }
        if (webhooks == null) {
            +"There was a problem"
        } else {
            textArea {
                disabled = true
                rows = webhooks.lines().size.toString()
                +webhooks
            }
            if (!webhooks.contains(""""Username": "${webhooksAuth.username}"""") || !webhooks.contains(""""Password": "${webhooksAuth.password}"""")) {
                // this may be improved: there may be more than one webhook
                p { b { +"Check the username and password!" } }
            }
        }
    }


    private fun renderStats(stats: StatsResponse?): MAIN.() -> Unit = {
        if (stats == null) {
            +"Could not fetch stats"
        } else {
            table {
                tr {
                    td { +"Sent" }
                    td { +stats.Sent.toString() }
                }
                tr {
                    td { +"Bounced" }
                    td { +stats.Bounced.toString() }
                }
                tr {
                    td { +"Bounce rate" }
                    td { +stats.BounceRate.toString() }
                }
                tr {
                    td { +"Spam complaints" }
                    td { +stats.SpamComplaints.toString() }
                }
                tr {
                    td { +"Spam complaints rate" }
                    td { +stats.SpamComplaintsRate.toString() }
                }
            }
        }
    }


}

private suspend fun getStats(): StatsResponse? {
    val now = Clock.System.now()
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).format(dateFormatter)
    val thirtyDaysAgo = now.minus(30.days).toLocalDateTime(TimeZone.currentSystemDefault()).format(dateFormatter)

    val response = client.get("${baseUrl}/stats/outbound?fromdate=$thirtyDaysAgo&todate=$today") {
        headers {
            append("Accept", JSON)
            append("X-Postmark-Server-Token", _apiKey)
        }
    }
    val body = response.bodyAsText()
    if (response.status.isSuccess()) {
        return Gson().fromJson(body, StatsResponse::class.java)
    }
    logger.error { "Could not get stats. ${response.status} $body" }
    return null
}

private suspend fun getWebhooks(): String? {
    val response = client.get("${baseUrl}/webhooks") {
        headers {
            append("Accept", JSON)
            append("X-Postmark-Server-Token", _apiKey)
        }
    }
    val body = response.bodyAsText()
    if (response.status.isSuccess()) {
        return body
    }
    logger.error { "Could not get webhooks. ${response.status} $body" }
    return null
}

private data class StatsResponse(
    val Sent: Int, val Bounced: Int, val BounceRate: Float, val SpamComplaints: Int, val SpamComplaintsRate: Float
)

public data class PluginPostmarkEmail(
    val messageID: PluginPostmarkEmailMessageID?,
    val jobID: JobIdContainer?, val email: PluginPostmarkEmailString, val status: PluginPostmarkEmailStatus?
)

public data class CreateEmailParams(val email: PluginPostmarkEmailString) {
    public companion object {
        public fun from(email: PostmarkEmail): CreateEmailParams {
            return CreateEmailParams(PluginPostmarkEmailString(Gson().toJson(email)))
        }
    }
}

public class PluginPostmarkEmailMessageID(value: String) : StringContainer(value) {
    override val minLength: Int = 0
    override val maxLength: Int = 40
    override val maxLines: Int = 1
}

public class PluginPostmarkEmailString(value: String) : StringContainer(value) {
    override val minLength: Int = 0
    override val maxLength: Int = 100000
    override val maxLines: Int = 1
}

public data class SendEmailParams(public val messageID: PluginPostmarkEmailMessageID)

private data class SendEmailApiResponse(val MessageID: String)

public class PluginPostmarkEmailStatus(value: String) : StringContainer(value) {
    override val minLength: Int = 0
    override val maxLength: Int = 20
    override val maxLines: Int = 1
}

public data class PostmarkEmailWebhooksAuth(val username: String, val password: String)

private data class WebhookRequest(
    val RecordType: String,
    val MessageID: String,
    val Type: String?,
)

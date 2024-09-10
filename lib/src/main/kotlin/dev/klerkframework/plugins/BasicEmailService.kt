package dev.klerkframework.plugins

import com.google.gson.Gson
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.BasicEmail.*
import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.actions.JobContext
import dev.klerkframework.klerk.actions.JobId
import dev.klerkframework.klerk.actions.JobResult
import dev.klerkframework.klerk.log.*
import dev.klerkframework.klerk.statemachine.Block
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random

private lateinit var config: BasicEmailConfiguration
private lateinit var klerkLog: KlerkLog

/**
 * A plugin for sending transactional emails.
 * Note that it cannot send attachments.
 * Currently, these providers are supported:
 * - Sendgrid
 * - Postmark
 */
public class BasicEmailService<C : KlerkContext, V>(configuration: BasicEmailConfiguration) : KlerkPlugin<C, V> {
    override val name: String = "Basic email"
    override val description: String = "Provides basic transactional email sending functionality."

    override fun mergeConfig(previous: Config<C, V>): Config<C, V> = previous

    init {
        config = configuration
    }

    override fun start(klerk: Klerk<C, V>) {
        klerkLog = klerk.log
    }

}

public interface BasicEmailConfiguration {
    public val url: String
    public val headers: StringValues
    public fun body(basic: BasicEmail): String
}

private const val JSON = "application/json"

public class SendgridConfiguration(private val apiKey: String) : BasicEmailConfiguration {
    override val url: String = "https://api.sendgrid.com/v3/mail/send"

    override val headers: StringValues = StringValues.build {
        append("Authorization", "Bearer $apiKey")
        append("Content-Type", JSON)
    }

    override fun body(basic: BasicEmail): String {
        val content = mutableListOf<SendgridContent>()
        basic.textBody?.let {
            content.add(SendgridContent(type = "text/plain", value = it))
        }
        basic.htmlBody?.let {
            content.add(SendgridContent(type = "text/html", value = it))
        }
        val sgEmail = SendgridEmail(
            from = basic.from,
            reply_to = basic.replyTo,
            personalizations = listOf(
                SendgridPersonalizations(
                    to = basic.to,
                    subject = basic.subject,
                )
            ),
            content = content,
        )
        return Gson().toJson(sgEmail)
    }

    private class SendgridPersonalizations(val to: List<EmailAndName>, val subject: String)

    private class SendgridContent(val type: String, val value: String)

    private class SendgridEmail(
        val personalizations: List<SendgridPersonalizations>,
        val content: List<SendgridContent>,
        val from: EmailAndName,
        val reply_to: EmailAndName?
    )

}

public class PostmarkConfiguration(private val apiKey: String, private val messageStream: String = "outbound") :
    BasicEmailConfiguration {
    override val url: String = "https://api.postmarkapp.com/email"
    override val headers: StringValues = StringValues.build {
        append("Accept", JSON)
        append("Content-Type", JSON)
        append("X-Postmark-Server-Token", apiKey)
    }

    override fun body(basic: BasicEmail): String {
        val pmEmail = PostmarkEmail(
            From = basic.from.toString(),
            To = basic.to.joinToString(",") { it.toStringWithoutDots() },
            Cc = if (basic.cc.isEmpty()) null else basic.cc.joinToString(",") { it.toStringWithoutDots() },
            Bcc = if (basic.bcc.isEmpty()) null else basic.bcc.joinToString(",") { it.toStringWithoutDots() },
            Subject = basic.subject,
            HtmlBody = basic.htmlBody,
            TextBody = basic.textBody,
            MessageStream = messageStream,
        )
        return Gson().toJson(pmEmail)
    }

    private class PostmarkEmail(
        val From: String,
        val To: String,
        val Cc: String?,
        val Bcc: String?,
        val Subject: String,
        val HtmlBody: String?,
        val TextBody: String?,
        val MessageStream: String
    )

}

public fun <T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> Block.VoidEventBlock<T, P, ModelStates, C, V>.sendEmail(
    function: (ArgForVoidEvent<T, P, C, V>) -> BasicEmail
) {
    fun createEmailJob(args: ArgForVoidEvent<T, P, C, V>): List<Job<C, V>> {
        val email = function.invoke(args)
        return listOf(EmailJob(email, args.context))
    }
    job(::createEmailJob)
}

public fun <T : Any, P, ModelStates : Enum<*>, C : KlerkContext, V> Block.InstanceEventBlock<T, P, ModelStates, C, V>.sendEmail(
    function: (ArgForInstanceEvent<T, P, C, V>) -> BasicEmail
) {
    fun createEmailJob(args: ArgForInstanceEvent<T, P, C, V>): List<EmailJob<C, V>> {
        val email = function.invoke(args)
        return listOf(EmailJob(email, args.context))
    }
    job(::createEmailJob)
}

public fun <T : Any, ModelStates : Enum<*>, C : KlerkContext, V> Block.InstanceNonEventBlock<T, ModelStates, C, V>.sendEmail(
    function: (ArgForInstanceNonEvent<T, C, V>) -> BasicEmail
) {
    fun createEmailJob(args: ArgForInstanceNonEvent<T, C, V>): List<EmailJob<C, V>> {
        val email = function.invoke(args)
        return listOf(EmailJob(email, null))
    }
    job(::createEmailJob)
}

public class EmailJob<C:KlerkContext, V>(private val email: BasicEmail, private val context: KlerkContext?) : Job<C, V> {
    override val id: JobId = Random.nextLong()

    override suspend fun run(jobContext: JobContext<C, V>): JobResult {
        val response = client.post(config.url) {
            headers {
                appendAll(config.headers)
            }
            setBody(config.body(email))
        }
        val result = when {
            response.status.value in 200..299 -> JobResult.Success
            response.status.value >= 500 -> JobResult.Retry
            else -> JobResult.Fail
        }
        val message = response.bodyAsText()
        when (result) {
            JobResult.Success -> klerkLog.add(LogPluginBasicEmailSucceeded(context))
            JobResult.Retry -> {
                logger.warn { "Problem sending email. Response status: ${response.status}. Message: ${message}" }
                klerkLog.add(LogPluginBasicEmailFailed(true, context, response.status, message))
            }
            JobResult.Fail -> {
                logger.error { "Problem sending email. Response status: ${response.status}. Message: ${message}" }
                klerkLog.add(LogPluginBasicEmailFailed(false, context, response.status, message))
            }
        }
        println(result)
        return result
    }

}

private val client = HttpClient(CIO)

public class LogPluginBasicEmailSucceeded(context: KlerkContext?) : LogEntry {
    override val time: Instant = context?.time ?: Clock.System.now()
    override val actor: dev.klerkframework.klerk.ActorIdentity? = context?.actor
    override val source: LogSource = LogSource(MajorSource.Plugin, "BasicEmail")
    override val logEventName: String = this::class.simpleName!!
    override val headingTemplate: String = "Email was sent."
    override val contentTemplate: String? = null
    override val facts: List<Fact> = emptyList()
}

public class LogPluginBasicEmailFailed(willRetry: Boolean, context: KlerkContext?, status: HttpStatusCode, message: String) : LogEntry {
    override val time: Instant = context?.time ?: Clock.System.now()
    override val actor: dev.klerkframework.klerk.ActorIdentity? = context?.actor
    override val source: LogSource = LogSource(MajorSource.Plugin, "BasicEmail")
    override val logEventName: String = this::class.simpleName!!
    override val headingTemplate: String = "Email could not be sent. ${if (willRetry) "Will retry." else "Will not retry."}"
    override val contentTemplate: String = "HTTP-status: ${status}. Message: $message\"}"
    override val facts: List<Fact> = emptyList()
}

/**
 * According to https://emailregex.com/
 */
public val emailRfc5322Regex: Regex = Regex.fromLiteral(
    "(?:[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])"
)

public const val emailSimpleRegex: String = "^(.+)@(\\S+)$"

package dev.klerkframework.klerk

import dev.klerkframework.klerk.actions.JobId
import dev.klerkframework.klerk.command.Command

/*
These classes and interfaces are not used by core Klerk. The purpose of them is to form a foundation for plugins so
that one plugin can depend on another type of plugins rather than on a specific plugin. As an example, the
MagicLinkAuthentication plugin requires email sending functionality in order to work. But instead of building that
functionality into MagicLinkAuthentication, it depends on another plugin which implements the EmailSender interface.

There are several benefits to this approach:
1. It is less work to develop new plugins.
2. The application developer can choose between many email providers without worrying about which providers
MagicLinkAuthentication supports.
3. The emails sent will be integrated in the application. This means that there is no need for MagicLinkAuthentication
to add its own model to track its emails, which would overlap the Alerting plugins models that does more or less the
same thing. Instead, the emails will be handled uniformly, meaning they will appear the same way in the logs and in the
Admin UI. This also enables other plugins to add further utility, e.g. the RateLimit plugin can make sure we don't send
too many emails (logins and other emails) to a specific user.
4. Specialised email sending plugins like PostmarkEmailService provides a rich integration with the provider, which the
MagicLinkAuthentication plugin can benefit from without any effort. E.g. if a user calls the support and complains that
she doesn't receive any login email, the support can use the PostmarkEmailService AdminUI page to see if the email has
been delivered by Postmark or if Postmark reports any problem.

The classes and interfaces here should ge general and may even be quite basic. It is perfectly ok for a plugin to both
implement some of these interfaces while also providing more advanced functionality. E.g. the PostmarkEmailService could
conceivably also be able to send emails to a subscriber list, something that is not possible using the EmailSender
interface here. So if a hypothetical BroadcasterPlugin would require sending to a subscriber list, it should have an
explicit dependency to PostmarkEmailService.
*/

public data class BasicEmail(
    val from: EmailAndName,
    val to: List<EmailAndName>,
    val cc: List<EmailAndName> = emptyList(),
    val bcc: List<EmailAndName> = emptyList(),
    val subject: String,
    val htmlBody: String?,
    val textBody: String?,
    val replyTo: EmailAndName?,
) {
    private val maxRecipients = 50

    init {
        require(htmlBody != null || textBody != null) { "At least one of textBody and htmlBody must be set" }
        require(to.size <= maxRecipients) { "Too many recipients (max is ${maxRecipients})" }
        require(cc.size <= maxRecipients) { "Too many cc (max is ${maxRecipients})" }
        require(bcc.size <= maxRecipients) { "Too many bcc (max is ${maxRecipients})" }
    }

    public data class EmailAndName(val email: String, val name: String) {
        init {
            require(email.isNotEmpty())
            // more validation? Which??
        }

        override fun toString(): String {
            return if (name.isEmpty()) email else "$name <$email>"
        }

        public companion object {
            public fun fromEmailAndNameString(str: String): EmailAndName {
                val parts = str.split(" ")
                return when (parts.size) {
                    1 -> EmailAndName(parts.first(), "")
                    2 -> EmailAndName(parts.first(), parts.last())
                    else -> throw IllegalArgumentException("Could not parse email '$str'")
                }
            }
        }
    }
}

public interface EmailSender<C : KlerkContext, V> {
    public val defaultFromAddress: BasicEmail.EmailAndName

    public suspend fun sendEmail(email: BasicEmail, context: C): Result<JobId?>
    public fun getSendEmailCommand(email: BasicEmail): Command<out Any, out Any>?
}

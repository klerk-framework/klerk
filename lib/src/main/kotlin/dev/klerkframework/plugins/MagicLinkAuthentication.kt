package dev.klerkframework.plugins

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import java.security.SecureRandom
import kotlin.time.Duration.Companion.minutes

/**
 * Plugin for authenticating by sending an email with a login link.
 *
 * Important: If you chose to use this plugin, make sure you understand the security implications. Although magic links
 * are considered more secure than passwords, they do not provide the same security as e.g. passkeys.
 *
 * The link is only valid once, and it will only work for 10 minutes.
 *
 * Since some email clients automatically follows the links in the emails in order to generate a preview, the login call should not be
 * made directly from the page that is served when clicking the link in the email. Instead, the GET request should show
 * a form to POST the key. For convenience, it is recommended to include some javascript in the GET request so that the
 * form is submitted without any user action.
 */
public class MagicLinkAuthentication<C : KlerkContext, V>(private val url: String) : KlerkPlugin<C, V> {
    override val name: String = "MagicLinkAuthentication"
    override val description: String = "Provides authentication by sending an email with a unique login link."

    private val codeCollections = ModelCollections<PluginMagicLinkAuthenticationCode, C>()
    private val random = SecureRandom.getInstanceStrong()
    private lateinit var emailPlugin: EmailSender<C, V>
    private lateinit var _klerk: Klerk<C, V>

    override fun start(klerk: Klerk<C, V>) {
        _klerk = klerk
        emailPlugin = klerk.config.plugins.filterIsInstance<EmailSender<C, V>>().firstOrNull()
            ?: throw IllegalStateException("MagicLinkAuthentication requires an 'EmailSender' plugin")
    }

    private enum class CodeStates {
        Created
    }

    private fun createPluginMagicLinkAuthenticationStatemachine(): StateMachine<PluginMagicLinkAuthenticationCode, CodeStates, C, V> =
        stateMachine {
            event(StartMagicLinkAuthentication) {}

            event(LinkClicked) {
                validateWithParameters(::validateCode)
            }

            voidState {
                onEvent(StartMagicLinkAuthentication) {
                    createModel(CodeStates.Created, ::createCode)
                }
            }

            state(CodeStates.Created) {
                onEnter {
                    createCommands(::createSendEmailCommand)
                }

                after(10.minutes) {
                    delete()
                }

                onEvent(LinkClicked) {
                    delete()
                }
            }
        }

    private fun validateCode(args: ArgForInstanceEvent<PluginMagicLinkAuthenticationCode, LinkClickedParams, C, V>): Validity {
        if (args.command.params.user != args.model.props.user) {
            logger.error { "Wrong user in params. This indicates a bug in either Klerk or your code." }
            return Validity.Invalid()
        }
        if (args.command.params.code != args.model.props.code) {
            logger.info { "Failed login attempt" }
            return Validity.Invalid()
        }
        return Validity.Valid
    }

    private fun createSendEmailCommand(args: ArgForInstanceNonEvent<PluginMagicLinkAuthenticationCode, C, V>): List<Command<out Any, out Any>> {
        val urlWithCode = "$url?key=${LinkClickedParams.createKey(args.model)}"
        val email = BasicEmail(
            to = listOf(BasicEmail.EmailAndName.fromEmailAndNameString(args.model.props.email.string)),
            from = emailPlugin.defaultFromAddress,
            subject = "Click to login",
            htmlBody = """Please click <a href="$urlWithCode">this link</a> to log in.""",
            textBody = null,
            replyTo = null
        )
        return listOf(requireNotNull(emailPlugin.getSendEmailCommand(email)))
    }

    override fun mergeConfig(previous: Config<C, V>): Config<C, V> {
        val managedModels = previous.managedModels.toMutableSet()
        managedModels.add(
            ManagedModel(
                PluginMagicLinkAuthenticationCode::class,
                createPluginMagicLinkAuthenticationStatemachine(),
                codeCollections
            )
        )
        return previous.copy(managedModels = managedModels)
    }

    private fun createCode(args: ArgForVoidEvent<PluginMagicLinkAuthenticationCode, StartMagicLinkAuthenticationParams, C, V>): PluginMagicLinkAuthenticationCode {
        return PluginMagicLinkAuthenticationCode(
            args.command.params.user,
            PluginMagicLinkAuthenticationEmailAddress(args.command.params.email.toString()),
            PluginMagicLinkAuthenticationCodeContainer(random.nextLong(0, Long.MAX_VALUE))
        )
    }

    public suspend fun tryLogin(key: String, context: C): LoginResult {
        val (modelId, userRef, code) = LinkClickedParams.parseKey(key)
        return when (_klerk.handle(
            Command(
                event = LinkClicked, model = modelId, params = LinkClickedParams(
                    user = userRef, code = code
                )
            ), context = context, options = ProcessingOptions(CommandToken.simple())
        )) {
            is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Failure -> {
                LoginResult.Failure
            }

            is _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success -> LoginResult.Success(userRef)
        }

    }

    public companion object {
        public fun generateScript(path: String, key: String): String = """
const form = document.createElement('form');
form.method = 'post';
form.action = '$path';
const hiddenField = document.createElement('input');
hiddenField.type = 'hidden';
hiddenField.name = 'key';
hiddenField.value = '$key';
form.appendChild(hiddenField);
document.body.appendChild(form);
form.submit();
""".trimIndent()
    }       // do we need CSRF-token?

}

public sealed class LoginResult {
    public class Success(public val userRef: PluginMagicLinkAuthenticationUserRef) : LoginResult()
    public data object Failure : LoginResult()
}


public data class PluginMagicLinkAuthenticationCode(
    val user: PluginMagicLinkAuthenticationUserRef,
    val email: PluginMagicLinkAuthenticationEmailAddress,
    val code: PluginMagicLinkAuthenticationCodeContainer
)

public class PluginMagicLinkAuthenticationEmailAddress(value: String) : StringContainer(value) {
    override val minLength: Int = 3
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

public class PluginMagicLinkAuthenticationCodeContainer(value: Long) : LongContainer(value) {
    override val min: Long = Long.MIN_VALUE
    override val max: Long = Long.MAX_VALUE
}

public object StartMagicLinkAuthentication :
    VoidEventWithParameters<PluginMagicLinkAuthenticationCode, StartMagicLinkAuthenticationParams>(
        isExternal = true,
        forModel = PluginMagicLinkAuthenticationCode::class,
        parametersClass = StartMagicLinkAuthenticationParams::class
    )

public object LinkClicked : InstanceEventWithParameters<PluginMagicLinkAuthenticationCode, LinkClickedParams>(
    isExternal = true, forModel = PluginMagicLinkAuthenticationCode::class, parametersClass = LinkClickedParams::class
)

public data class StartMagicLinkAuthenticationParams(
    val email: PluginMagicLinkAuthenticationEmailAddress, val user: PluginMagicLinkAuthenticationUserRef
)

public data class LinkClickedParams(
    val user: PluginMagicLinkAuthenticationUserRef,
    val code: PluginMagicLinkAuthenticationCodeContainer,
) {
    public companion object {
        public fun parseKey(key: String): Triple<ModelID<PluginMagicLinkAuthenticationCode>, PluginMagicLinkAuthenticationUserRef, PluginMagicLinkAuthenticationCodeContainer> {
            val parts = key.split(":")
            require(parts.size == 3) { "Key must contain 3 parts separated by colon" }
            return Triple(
                ModelID.from(parts.first()),
                PluginMagicLinkAuthenticationUserRef(parts[1].toInt()),
                PluginMagicLinkAuthenticationCodeContainer(parts[2].toLong())
            )
        }

        public fun createKey(model: Model<PluginMagicLinkAuthenticationCode>): String =
            "${model.id}:${model.props.user}:${model.props.code}"
    }
}

public class PluginMagicLinkAuthenticationUserRef(value: Int) : IntContainer(value) {
    override val min: Int = 0
    override val max: Int = Int.MAX_VALUE
}

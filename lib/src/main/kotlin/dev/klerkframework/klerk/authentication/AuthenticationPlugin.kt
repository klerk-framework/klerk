package dev.klerkframework.klerk.authentication

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.webutils.isDevelopmentMode
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import mu.KotlinLogging
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

public class AuthenticationPlugin<C:KlerkContext, V> {

    private val log = KotlinLogging.logger {}
    internal val authenticationView = AuthenticationCollections<C>()
    private val redirects = mutableMapOf<String, String>()

    private val googleClientId =
        requireNotNull(System.getenv("GOOGLE_CLIENT_ID") ?: System.getProperty("GOOGLE_CLIENT_ID")) { "Env.var/property GOOGLE_CLIENT_ID must be set" }
    private val googleClientSecret =
        requireNotNull(System.getenv("GOOGLE_CLIENT_SECRET") ?: System.getProperty("GOOGLE_CLIENT_SECRET")) { "Env.var/property GOOGLE_CLIENT_SECRET must be set" }

    private object Paths {
        const val loginWithGoogle = "/_df/login-with-google"
    }

    init {
        if (isDevelopmentMode()) {
            log.info("The system is in development mode. Some security measures will be disabled.")
        }
    }

    public fun applyConfig(config: Config<C, V>): Config<C, V> {
        val newManagedModels = config.managedModels.toMutableSet()
        newManagedModels.add(ManagedModel(AuthenticationSession::class, sessionStateMachine, authenticationView))
        return config.copy(managedModels = newManagedModels)
    }

    public fun registerRoutes(data: Klerk<C, V>): Routing.() -> Unit = {
        authenticate("auth-oauth-google") {
            get(Paths.loginWithGoogle) {
                // Redirects to 'authorizeUrl' automatically
            }

        }
    }

    public fun installKtorPlugins(authUrl: String): Application.() -> Unit = {

        install(Sessions) {
            // Note that the forward headers Ktor plugin (https://ktor.io/docs/forward-headers.html) must be installed if deployed behind a reverse proxy
            val cookieName = if (isDevelopmentMode()) "auth_plugin_user_session" else "__Host-auth_plugin_user_session"
            cookie<UserSession>(cookieName) {
                cookie.secure = !isDevelopmentMode()
                cookie.httpOnly = true
                cookie.path = "/"
                cookie.extensions["SameSite"] =
                    "Lax"   // Strict? But this doesn't work when setting the cookie in the callback
            }
        }

        install(Authentication) {
            oauth("auth-oauth-google") {
                urlProvider = { authUrl }
                providerLookup = {
                    OAuthServerSettings.OAuth2ServerSettings(
                        name = "google",
                        authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                        accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
                        requestMethod = HttpMethod.Post,
                        clientId = googleClientId,
                        clientSecret = googleClientSecret,
                        //   defaultScopes = listOf("https://www.googleapis.com/auth/userinfo.profile", "https://www.googleapis.com/auth/userinfo.email"),
                        defaultScopes = listOf(
                            "openid",
                            "https://www.googleapis.com/auth/userinfo.profile",
                            "https://www.googleapis.com/auth/userinfo.email"
                        ),
                        extraAuthParameters = listOf("access_type" to "offline"),
                        onStateCreated = { call, state ->
                            call.request.queryParameters["redirectUrl"]?.let { redirects[state] = it }
                        }
                    )
                }
                client = HttpClient(CIO)
            }
        }

    }

    private val sessionStateMachine = stateMachine<AuthenticationSession, AuthenticationSessionStates, C, V> {

        event(CreateSession) {
            validateContext(::requireAuthenticationIdentity)
            validReferences(CreateSessionParams::target, null)
        }

        event(DeleteSession) {
            validateContext(::requireAuthenticationIdentity)
        }

        voidState {
            onEvent(CreateSession) {
                createModel(AuthenticationSessionStates.Active, ::createSession)
            }
        }

        state(AuthenticationSessionStates.Active) {
            onEvent(DeleteSession) {
                delete()
            }
        }

    }


    private enum class AuthenticationSessionStates {
        Active
    }

}

internal fun <C:KlerkContext, V> createSession(args: ArgForVoidEvent<AuthenticationSession, CreateSessionParams, C, V>): AuthenticationSession {
    val params = args.command.params
    return AuthenticationSession(params.code, ExpireTime(kotlinx.datetime.Clock.System.now().plus(90.days)), target = params.target)
}

internal data class CreateSessionParams(val code: Code = Code.new(), val target: ModelID<out Any>)

internal data class AddAuthenticationMethodParams(val openId: AuthenticationOpenID)

internal fun <C:KlerkContext, V> canOnlyBeTriggeredByAuthenticationIdentity(context: C, reader: Reader<C, V>): Validity {
    return if (context.actor == dev.klerkframework.klerk.AuthenticationIdentity) Validity.Valid else Validity.Invalid()
}

internal class AuthenticationCollections<C:KlerkContext> : ModelCollections<AuthenticationSession, C>() {}

internal data class AuthenticationSession(
    val sessionId: Code,
    val sessionIdExpireTime: ExpireTime,
    val target: ModelID<out Any>
) {
    override fun toString(): String {
        return "[secret]"
    }
}

internal data class AuthenticationWebauthn(
    val webauthnKeyId: Base64UrlEncoded,
    val webauthnUserId: Base64UrlEncoded,
    val webauthnPublicKey: Base64UrlEncoded
)

internal class Code(value: String) : StringContainer(value) {
    override val minLength = 10
    override val maxLength = 50
    override val maxLines: Int = 1

    companion object {
        fun new(): Code = Code(generateSessionId())
    }
}

internal class Base64UrlEncoded(encoded: String) : StringContainer(encoded) {
    override val minLength = 1
    override val maxLength = 1000
    override val maxLines: Int = 1

    fun decode(): com.yubico.webauthn.data.ByteArray {
        return com.yubico.webauthn.data.ByteArray.fromBase64(value)
    }

}

internal class ExpireTime(value: Long) : LongContainer(value) {
    override val min: Long = 0
    override val max: Long = Long.MAX_VALUE

    constructor(instant: Instant) : this(instant.toEpochMilliseconds())

    override fun toString(): String = instant.toString()

    val instant: Instant
        get() = Instant.fromEpochMilliseconds(value)
}

internal data class AuthenticationOpenID(val provider: OpenIdProvider, val subject: OpenIdSubject)

internal class OpenIdProvider(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

internal class OpenIdSubject(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

internal sealed class LoginResult {
    data class Failure(val problem: Problem) : LoginResult()
    data class Success(val session: Model<AuthenticationSession>) : LoginResult()
}

internal data class UserSession(val sessionSecret: String, val sessionId: String)

internal object DeleteSession : InstanceEventNoParameters<AuthenticationSession>(AuthenticationSession::class, true)

private fun <C:KlerkContext> requireAuthenticationIdentity(context: C): Validity {
    return if (context.actor == dev.klerkframework.klerk.AuthenticationIdentity) Validity.Valid else Validity.Invalid("Only AuthenticationIdentity can create a session")
}

internal object CreateSession : VoidEventWithParameters<AuthenticationSession, CreateSessionParams>(AuthenticationSession::class, true, CreateSessionParams::class)

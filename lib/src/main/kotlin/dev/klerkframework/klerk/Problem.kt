package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataContainer

import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.reflect

public abstract class Problem(public val endUserTranslatedMessage: String, public val code: KlerkErrorCode) {
    public abstract fun asException(): Exception
    public abstract val recommendedHttpCode: Int
    public abstract val violatedRule: RuleDescription?
    public override fun toString(): String = "[$code] $violatedRule"
}

public class InvalidPropertyCollectionProblem(
    endUserTranslatedMessage: String,
    public val fieldsMustBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    public val fieldsMustNotBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage, KlerkErrorCode.InvalidPropertyCollection) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
}

public class InvalidPropertyProblem(
    endUserTranslatedMessage: String,
    public val propertyName: String,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage, KlerkErrorCode.InvalidProperty) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400

    override fun toString(): String = endUserTranslatedMessage

}

public data class RuleDescription(val function: Function<Any>, val type: RuleType) {
    @OptIn(ExperimentalReflectionOnLambdas::class)
    public override fun toString(): String = "${type.name}: ${function.reflect()?.name}"
}

public enum class RuleType {
    ParametersValidation,
    ContextValidation,
    ParametersAndContextValidation,
    ModelValidation,
    Authorization
}

public class AuthorizationProblem(endUserTranslatedMessage: String, override val violatedRule: RuleDescription?, code: KlerkErrorCode) : Problem(endUserTranslatedMessage, code) {
    public override fun asException(): AuthorizationException = AuthorizationException(code, endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 403
}

public class InternalProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage, KlerkErrorCode.Internal) {
    public override fun asException(): InternalException = InternalException(code, endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 500
    public override val violatedRule: RuleDescription? = null
}

public class StateProblem(endUserTranslatedMessage: String, code: KlerkErrorCode, override val violatedRule: RuleDescription? = null) : Problem(endUserTranslatedMessage, code) {
    public override fun asException(): IllegalStateException = IllegalStateException(toString())
    public override val recommendedHttpCode: Int = 409
}

public class ServerStateProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage, KlerkErrorCode.Internal) {
    public override fun asException(): IllegalStateException = IllegalStateException(toString())
    public override val recommendedHttpCode: Int = 503
    public override val violatedRule: RuleDescription? = null
}

public class NotFoundProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage, KlerkErrorCode.NotFound) {
    public override fun asException(): NoSuchElementException = NoSuchElementException(toString())
    public override val recommendedHttpCode: Int = 404
    public override val violatedRule: RuleDescription? = null
}

public class BadRequestProblem(endUserTranslatedMessage: String, code: KlerkErrorCode) : Problem(endUserTranslatedMessage, code) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

public class IdempotenceProblem(endUserTranslatedMessage: String, code: KlerkErrorCode) : Problem(endUserTranslatedMessage, code) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

public class AuthorizationException(code: KlerkErrorCode, message: String? = null) : RuntimeException("[$code] $message")

/**
 * Indicates a bug in Klerk.
 */
public class InternalException(public val code: KlerkErrorCode = KlerkErrorCode.Internal, message: String? = null) : RuntimeException("[$code] $message")

public class IllegalConfigurationException(public val code: KlerkErrorCode, message: String) : RuntimeException("[$code] $message")

/**
 * Error codes for Klerk configuration errors. The error codes should never change, so if a code is
 * removed or modified, the old code should not be reused.
 */
public enum class KlerkErrorCode(public val code: String) {
    EventNotDeclared("ERROR-CONFIG-1"),
    MissingValidReferences("ERROR-CONFIG-2"),
    MissingSystemContextProvider("ERROR-CONFIG-3"),
    MissingPersistence("ERROR-CONFIG-4"),
    MissingAuthorization("ERROR-CONFIG-5"),
    MissingManagedModels("ERROR-CONFIG-6"),
    PropertyMustBeDataContainer("ERROR-CONFIG-7"),
    InvalidPropertyCollection("ERROR-VALIDATION-1"),
    InvalidProperty("ERROR-VALIDATION-2"),
    Internal("ERROR-INTERNAL-1"),
    NotFound("ERROR-USER-1"),
    CommandNegativeAuthorizationExist("ERROR-AUTH-1"),
    CommandPositiveAuthorizationMissing("ERROR-AUTH-2"),
    ReadNegativeAuthorizationExist("ERROR-AUTH-3"),
    ReadPositiveAuthorizationMissing("ERROR-AUTH-4"),
    AuditPositiveAuthorizationMissing("ERROR-AUTH-5"),
    AuditNegativeAuthorizationExist("ERROR-AUTH-6"),
    UnauthorizedPropertyRead("ERROR-AUTH-7"),
    EventNotPossibleInVoidState("ERROR-COMMAND-1"),
    EventNotPossibleInState("ERROR-COMMAND-2"),
    ModelTypeMismatch("ERROR-COMMAND-3"),
    EventVisibilityTooLow("ERROR-COMMAND-4"),
    CommandTokenAlreadyUsed("ERROR-COMMAND-5"),
    ModelModifiedSinceTokenCreation("ERROR-COMMAND-6"),
    CommandModelValidation("ERROR-COMMAND-7"),
    BrokenReference("ERROR-COMMAND-8");

    override fun toString(): String = code
}

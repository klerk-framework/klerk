package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataContainer

import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.reflect

/**
 * Base class for everything that can go wrong while processing a command, surfaced in
 * [dev.klerkframework.klerk.CommandResult.Failure.problems]. Each subtype maps to an [asException] and a
 * [recommendedHttpCode] for callers that want to translate a failure into a thrown exception or an HTTP response.
 */
public abstract class Problem(public val endUserTranslatedMessage: String, public val code: KlerkErrorCode) {
    /** The exception [dev.klerkframework.klerk.CommandResult.orThrow] throws for this problem. */
    public abstract fun asException(): Exception
    public abstract val recommendedHttpCode: Int

    /** The validation/authorization rule that caused this problem, if any. */
    public abstract val violatedRule: RuleDescription?
    public override fun toString(): String = "[$code] $violatedRule"
}

/**
 * A cross-property validation rule was violated (e.g. two mutually-exclusive properties were both non-null).
 * @param fieldsMustBeNull properties the rule requires to be null, if applicable.
 * @param fieldsMustNotBeNull properties the rule requires to be non-null, if applicable.
 */
public class InvalidPropertyCollectionProblem(
    endUserTranslatedMessage: String,
    public val fieldsMustBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    public val fieldsMustNotBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage, KlerkErrorCode.InvalidPropertyCollection) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
}

/** A single property's [DataContainer] rejected the value passed to it (e.g. failed its own validation). */
public class InvalidPropertyProblem(
    endUserTranslatedMessage: String,
    public val propertyName: String,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage, KlerkErrorCode.InvalidProperty) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400

    override fun toString(): String = endUserTranslatedMessage

}

/** Identifies the validation/authorization function that rejected a command or read, for diagnostics/logging. */
public data class RuleDescription(val function: Function<Any>, val type: RuleType) {
    @OptIn(ExperimentalReflectionOnLambdas::class)
    public override fun toString(): String = "${type.name}: ${function.reflect()?.name}"
}

/** Which kind of rule produced a [RuleDescription]. */
public enum class RuleType {
    ParametersValidation,
    ContextValidation,
    ParametersAndContextValidation,
    ModelValidation,
    Authorization
}

/** The actor was not authorized to submit this command. Maps to HTTP 403. */
public class AuthorizationProblem(
    endUserTranslatedMessage: String,
    override val violatedRule: RuleDescription?,
    code: KlerkErrorCode
) : Problem(endUserTranslatedMessage, code) {
    public override fun asException(): AuthorizationException = AuthorizationException(code, endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 403
}

/** A bug in Klerk itself, or in configured code, prevented processing. Maps to HTTP 500. */
public class InternalProblem(endUserTranslatedMessage: String) :
    Problem(endUserTranslatedMessage, KlerkErrorCode.Internal) {
    public override fun asException(): InternalException = InternalException(code, endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 500
    public override val violatedRule: RuleDescription? = null
}

/**
 * The command could not be applied given the model's current state (e.g. the event is not possible in the model's
 * current state machine state). Maps to HTTP 409.
 * @param internalDescription a non-translated, developer-facing description used in the thrown [IllegalStateException].
 */
public class StateProblem(
    endUserTranslatedMessage: String,
    public val internalDescription: String,
    code: KlerkErrorCode,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage, code) {
    public override fun asException(): IllegalStateException = IllegalStateException(internalDescription)
    public override val recommendedHttpCode: Int = 409
}

/** The server is temporarily unable to process the command (e.g. not started, or shutting down). Maps to HTTP 503. */
public class ServerStateProblem(endUserTranslatedMessage: String) :
    Problem(endUserTranslatedMessage, KlerkErrorCode.Internal) {
    public override fun asException(): IllegalStateException = IllegalStateException(toString())
    public override val recommendedHttpCode: Int = 503
    public override val violatedRule: RuleDescription? = null
}

/** The command referenced a model, or referenced data, that does not exist. Maps to HTTP 404. */
public class NotFoundProblem(endUserTranslatedMessage: String) :
    Problem(endUserTranslatedMessage, KlerkErrorCode.NotFound) {
    public override fun asException(): NoSuchElementException = NoSuchElementException(toString())
    public override val recommendedHttpCode: Int = 404
    public override val violatedRule: RuleDescription? = null
}

/** The command itself was malformed independent of model state (e.g. type mismatch between event and model). Maps to HTTP 400. */
public class BadRequestProblem(endUserTranslatedMessage: String, code: KlerkErrorCode) :
    Problem(endUserTranslatedMessage, code) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

/**
 * The [dev.klerkframework.klerk.command.CommandToken] was reused, or referenced a model that was modified since the
 * token was created. See [dev.klerkframework.klerk.command.ProcessingOptions.token]. Maps to HTTP 400.
 */
public class IdempotenceProblem(endUserTranslatedMessage: String, code: KlerkErrorCode) :
    Problem(endUserTranslatedMessage, code) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

/** Thrown by [AuthorizationProblem.asException] and other authorization failures throughout the read/write API. */
public class AuthorizationException(code: KlerkErrorCode, message: String? = null) :
    RuntimeException("[$code] $message")

/**
 * Indicates a bug in Klerk.
 */
public class InternalException(public val code: KlerkErrorCode = KlerkErrorCode.Internal, message: String? = null) :
    RuntimeException("[$code] $message")

public class IllegalConfigurationException(public val code: KlerkErrorCode, message: String) :
    RuntimeException("[$code] $message")

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
    BrokenReference("ERROR-COMMAND-8"),
    AttachedDataNotFound("ERROR-COMMAND-9"),
    AttachedDataAlreadyOwned("ERROR-COMMAND-10"),
    AttachedDataReadPositiveAuthorizationMissing("ERROR-AUTH-8"),
    AttachedDataReadNegativeAuthorizationExist("ERROR-AUTH-9"),
    AttachedDataWritePositiveAuthorizationMissing("ERROR-AUTH-10"),
    AttachedDataWriteNegativeAuthorizationExist("ERROR-AUTH-11");

    override fun toString(): String = code
}

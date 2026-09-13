package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.functionName
import kotlin.reflect.KProperty0

/**
 * Base class for everything that can go wrong while processing a command, surfaced in
 * [dev.klerkframework.klerk.CommandResult.Failure.problems]. Each subtype maps to an [asException] and a
 * [recommendedHttpCode] for callers that want to translate a failure into a thrown exception or an HTTP response.
 */
public sealed class Problem(public val endUserTranslatedMessage: String, public val code: KlerkErrorCode) {
    /** The exception [dev.klerkframework.klerk.CommandResult.getOrThrow] throws for this problem. */
    public abstract fun asException(): Exception
    public abstract val recommendedHttpCode: Int

    /** The validation/authorization rule that caused this problem, if any. */
    public abstract val violatedRule: RuleDescription?

    public override fun toString(): String =
        if (violatedRule == null) "[$code] $endUserTranslatedMessage" else "[$code] $endUserTranslatedMessage ($violatedRule)"
}

/**
 * A cross-property validation rule was violated (e.g. two mutually-exclusive properties were both non-null).
 * @param fieldsMustBeNull properties the rule requires to be null. Empty if not applicable.
 * @param fieldsMustNotBeNull properties the rule requires to be non-null. Empty if not applicable.
 */
public class InvalidPropertyCollectionProblem(
    endUserTranslatedMessage: String,
    public val fieldsMustBeNull: Set<KProperty0<DataContainer<*>?>> = emptySet(),
    public val fieldsMustNotBeNull: Set<KProperty0<DataContainer<*>?>> = emptySet(),
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
}

/** Identifies the validation/authorization function that rejected a command or read, for diagnostics/logging. */
public data class RuleDescription(val function: Function<Any>, val type: RuleType) {
    public override fun toString(): String = "${type.name}: ${functionName(function)}"
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
    Problem(endUserTranslatedMessage, KlerkErrorCode.ServerNotAvailable) {
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

/** Base class for the exceptions Klerk throws. The [code] identifies what went wrong. */
public sealed class KlerkException(public val code: KlerkErrorCode, message: String?) :
    RuntimeException("[$code] $message")

/** Thrown by [AuthorizationProblem.asException] and other authorization failures throughout the read/write API. */
public class AuthorizationException(code: KlerkErrorCode, message: String? = null) : KlerkException(code, message)

/**
 * Indicates a bug in Klerk.
 */
public class InternalException(code: KlerkErrorCode = KlerkErrorCode.Internal, message: String? = null) :
    KlerkException(code, message)

/** Thrown at startup when the specification or the settings are not valid. */
public class IllegalConfigurationException(code: KlerkErrorCode, message: String) : KlerkException(code, message)

/**
 * Thrown by `klerk.meta.start()` when a stored model does not match its model class, e.g. because a property has been
 * renamed, removed, added or has changed type since the model was stored. Register a
 * [dev.klerkframework.klerk.migration.MigrationStep] that makes the stored data match.
 *
 * @property modelType the model's simple class name, as stored
 * @property reason which properties do not match, and how. Never contains a stored value.
 */
public class PersistedModelMismatchException(
    public val modelType: String,
    public val modelId: Int,
    public val reason: String,
) : RuntimeException(
    "The stored $modelType with id $modelId does not match the model classes: $reason. Register a MigrationStep " +
            "that makes the stored data match."
)

/**
 * Thrown at startup when a stored model's properties no longer pass their [dev.klerkframework.klerk.datatypes.DataContainer.validate]
 * rules, e.g. because a rule (min, max, minLength, ...) was tightened after the model was stored. Register a
 * [dev.klerkframework.klerk.migration.MigrationStep] that fixes the stored data.
 *
 * @property modelType the model's simple class name, as stored
 * @property reason which properties are invalid, and why. Never contains a stored value.
 */
public class PersistedModelValidationException(
    public val modelType: String,
    public val modelId: Int,
    public val reason: String,
) : RuntimeException(
    "The stored $modelType with id $modelId no longer passes validation: $reason. Register a MigrationStep " +
            "that makes the stored data valid."
)

/**
 * Error codes for Klerk configuration errors. The error codes should never change, so if a code is
 * removed or modified, the old code should not be reused.
 */
public enum class KlerkErrorCode(public val code: String) {
    EventNotDeclared("ERROR-SPEC-1"),
    MissingValidReferences("ERROR-SPEC-2"),
    MissingSystemContextProvider("ERROR-SPEC-3"),
    MissingAuthorization("ERROR-SPEC-4"),
    MissingManagedModels("ERROR-SPEC-5"),
    PropertyMustBeDataContainer("ERROR-SPEC-6"),
    UnregisteredJobName("ERROR-SPEC-7"),
    UnloadableJobCursor("ERROR-SPEC-8"),
    MissingJobContextProvider("ERROR-SPEC-9"),
    BlobMustBeDeclaredInAContainer("ERROR-SPEC-10"),
    MissingPreAttachStep("ERROR-SPEC-11"),
    StringMustBeDeclaredInAContainer("ERROR-SPEC-12"),
    ValidationRuleForUnknownProperty("ERROR-SPEC-13"),
    RuleMustBeNamed("ERROR-SPEC-14"),
    InvalidStateMachine("ERROR-SPEC-15"),
    InvalidMigration("ERROR-SPEC-16"),

    MissingAttachedBlobStore("ERROR-SETTINGS-1"),
    AttachedBlobStoreMissingData("ERROR-SETTINGS-3"),

    InvalidPropertyCollection("ERROR-VALIDATION-1"),
    InvalidProperty("ERROR-VALIDATION-2"),

    Internal("ERROR-INTERNAL-1"),

    /** The server cannot process commands right now, e.g. because it has not been started or is shutting down. */
    ServerNotAvailable("ERROR-SERVER-1"),

    NotFound("ERROR-USER-1"),

    CommandNegativeAuthorizationExist("ERROR-AUTH-1"),
    CommandPositiveAuthorizationMissing("ERROR-AUTH-2"),
    ReadNegativeAuthorizationExist("ERROR-AUTH-3"),
    ReadPositiveAuthorizationMissing("ERROR-AUTH-4"),
    EventLogPositiveAuthorizationMissing("ERROR-AUTH-5"),
    EventLogNegativeAuthorizationExist("ERROR-AUTH-6"),
    UnauthorizedPropertyRead("ERROR-AUTH-7"),
    AttachedDataReadPositiveAuthorizationMissing("ERROR-AUTH-8"),
    AttachedDataReadNegativeAuthorizationExist("ERROR-AUTH-9"),
    AttachedDataWritePositiveAuthorizationMissing("ERROR-AUTH-10"),
    AttachedDataWriteNegativeAuthorizationExist("ERROR-AUTH-11"),
    JobReadPositiveAuthorizationMissing("ERROR-AUTH-12"),
    JobReadNegativeAuthorizationExist("ERROR-AUTH-13"),
    BypassAuthReadNotAllowed("ERROR-AUTH-14"),

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
    AttachedDataNotAcceptable("ERROR-COMMAND-11"),
    AttachedDataNotProcessed("ERROR-COMMAND-12"),

    /**
     * A new job was refused because the queue is not draining fast enough. Only ever produced for *new* work — yields,
     * retries, spawned children and end-of-life hooks are never refused.
     */
    JobQueueOverloaded("ERROR-JOB-1"),
    JobNotFound("ERROR-JOB-2"),
    JobAlreadyTerminal("ERROR-JOB-3");

    override fun toString(): String = code
}

/** Thrown by `attachedData.awaitProcessing` when a step refused the file, or when it is not what its property wants. */
public class BlobRejected(message: String) : RuntimeException(message)

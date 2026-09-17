package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.functionName
import kotlin.reflect.KProperty0

/**
 * Base class for everything that can go wrong while processing a command, surfaced in
 * [dev.klerkframework.klerk.CommandResult.Failure.problems]. Each subtype maps to an [asException] for callers
 * that want to translate a failure into a thrown exception.
 */
public sealed class Problem(public val endUserTranslatedMessage: String, public val code: KlerkErrorCode) {
    /**
     * The exception [dev.klerkframework.klerk.CommandResult.getOrThrow] throws for this problem. Subclasses narrow
     * the type: [AuthorizationProblem] and [InternalProblem] give a [KlerkException]; the others give the standard
     * exception for the situation — [IllegalArgumentException] for invalid input, [IllegalStateException] for a
     * state conflict or unavailable server, and [NoSuchElementException] for [NotFoundProblem].
     */
    public abstract fun asException(): Exception

    /** The validation/authorization rule that caused this problem, if any. */
    public abstract val violatedRule: RuleDescription?

    override fun toString(): String =
        if (violatedRule == null) {
            "[$code] $endUserTranslatedMessage"
        } else {
            "[$code] $endUserTranslatedMessage ($violatedRule)"
        }

    /** Everything that identifies this problem. Subclasses with more properties add them. */
    internal open val equalityKey: List<Any?> get() = listOf(this::class, endUserTranslatedMessage, code, violatedRule)

    /** Problems are equal if they are of the same class and have equal properties. */
    final override fun equals(other: Any?): Boolean = other is Problem && other.equalityKey == equalityKey
    final override fun hashCode(): Int = equalityKey.hashCode()
}

/**
 * A cross-property validation rule was violated (e.g. two mutually-exclusive properties were both non-null).
 */
public class InvalidPropertyCollectionProblem(
    endUserTranslatedMessage: String,
    /** Properties the rule requires to be null. Empty if not applicable. */
    public val fieldsMustBeNull: Set<KProperty0<DataContainer<*>?>> = emptySet(),
    /** Properties the rule requires to be non-null. Empty if not applicable. */
    public val fieldsMustNotBeNull: Set<KProperty0<DataContainer<*>?>> = emptySet(),
    override val violatedRule: RuleDescription? = null,
) : Problem(endUserTranslatedMessage, KlerkErrorCode.InvalidPropertyCollection) {
    override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    override val equalityKey: List<Any?> get() = super.equalityKey + listOf(fieldsMustBeNull, fieldsMustNotBeNull)
}

/** A rule attached to the event with `validateWithContext` refused the command, based on the context alone. */
public class PreventedByRuleProblem(
    endUserTranslatedMessage: String,
    override val violatedRule: RuleDescription? = null,
) : Problem(endUserTranslatedMessage, KlerkErrorCode.PreventedByRule) {
    override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
}

/** A single property's [DataContainer] rejected the value passed to it (e.g. failed its own validation). */
public class InvalidPropertyProblem(
    endUserTranslatedMessage: String,
    /** The name of the property whose value was rejected. */
    public val propertyName: String,
    override val violatedRule: RuleDescription? = null,
) : Problem(endUserTranslatedMessage, KlerkErrorCode.InvalidProperty) {
    override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    override val equalityKey: List<Any?> get() = super.equalityKey + propertyName
}

/** Identifies the validation/authorization function that rejected a command or read, for diagnostics/logging. */
public data class RuleDescription(val function: Function<Any>, val type: RuleType) {
    override fun toString(): String = "${type.name}: ${functionName(function)}"
}

/** Which kind of rule produced a [RuleDescription]. */
public enum class RuleType {
    /** A rule that validates the event parameters. */
    ParametersValidation,

    /** A `validateWithContext` rule, which sees the context alone. */
    ContextValidation,

    /** A rule that validates the event parameters together with the context. */
    ParametersAndContextValidation,

    /** A rule declared on the model props, checking the model the event would produce. */
    ModelValidation,

    /** An authorization rule. */
    Authorization,
}

/** The actor was not authorized to submit this command. */
public class AuthorizationProblem(
    endUserTranslatedMessage: String,
    override val violatedRule: RuleDescription?,
    code: KlerkErrorCode,
) : Problem(endUserTranslatedMessage, code) {
    override fun asException(): AuthorizationException = AuthorizationException(code, endUserTranslatedMessage)
}

/** A bug in Klerk itself, or in configured code, prevented processing. */
public class InternalProblem(endUserTranslatedMessage: String) :
    Problem(endUserTranslatedMessage, KlerkErrorCode.Internal) {
    override fun asException(): InternalException = InternalException(code, endUserTranslatedMessage)
    override val violatedRule: RuleDescription? = null
}

/**
 * The command could not be applied given the model's current state (e.g. the event is not possible in the model's
 * current state machine state).
 */
public class StateProblem(
    endUserTranslatedMessage: String,
    /** A non-translated, developer-facing description used in the thrown [IllegalStateException]. */
    public val internalDescription: String,
    code: KlerkErrorCode,
    override val violatedRule: RuleDescription? = null,
) : Problem(endUserTranslatedMessage, code) {
    override fun asException(): IllegalStateException = IllegalStateException(internalDescription)
    override val equalityKey: List<Any?> get() = super.equalityKey + internalDescription
}

/** The server is temporarily unable to process the command (e.g. not started, or shutting down). */
public class ServerStateProblem(endUserTranslatedMessage: String) :
    Problem(endUserTranslatedMessage, KlerkErrorCode.ServerNotAvailable) {
    override fun asException(): IllegalStateException = IllegalStateException(toString())
    override val violatedRule: RuleDescription? = null
}

/** The command referenced a model, or referenced data, that does not exist. */
public class NotFoundProblem(endUserTranslatedMessage: String) :
    Problem(endUserTranslatedMessage, KlerkErrorCode.NotFound) {
    override fun asException(): NoSuchElementException = NoSuchElementException(toString())
    override val violatedRule: RuleDescription? = null
}

/** The command itself was malformed independent of model state (e.g. type mismatch between event and model). */
public class BadRequestProblem(endUserTranslatedMessage: String, code: KlerkErrorCode) :
    Problem(endUserTranslatedMessage, code) {
    override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    override val violatedRule: RuleDescription? = null
}

/**
 * The [dev.klerkframework.klerk.command.CommandToken] was reused, or referenced a model that was modified since the
 * token was created. See [dev.klerkframework.klerk.command.ProcessingOptions.token].
 */
public class IdempotenceProblem(endUserTranslatedMessage: String, code: KlerkErrorCode) :
    Problem(endUserTranslatedMessage, code) {
    override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    override val violatedRule: RuleDescription? = null
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
 * Thrown by `JobManager.schedule` when the job was refused, by the admission policy or by the hard queue cap.
 * [problem] is the same one a command that scheduled the job would have failed with.
 */
public class JobRejectedException(public val problem: Problem) :
    KlerkException(problem.code, problem.endUserTranslatedMessage)

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
) : KlerkException(
    KlerkErrorCode.PersistedModelMismatch,
    "The stored $modelType with id $modelId does not match the model classes: $reason. Register a MigrationStep " +
            "that makes the stored data match.",
)

/**
 * Thrown at startup when a stored model's properties no longer pass their
 * [dev.klerkframework.klerk.datatypes.DataContainer.validate] rules, e.g. because a rule (min, max, minLength, ...) was
 * tightened after the model was stored. Register a [dev.klerkframework.klerk.migration.MigrationStep] that fixes the
 * stored data.
 *
 * @property modelType the model's simple class name, as stored
 * @property reason which properties are invalid, and why. Never contains a stored value.
 */
public class PersistedModelValidationException(
    public val modelType: String,
    public val modelId: Int,
    public val reason: String,
) : KlerkException(
    KlerkErrorCode.PersistedModelInvalid,
    "The stored $modelType with id $modelId no longer passes validation: $reason. Register a MigrationStep " +
            "that makes the stored data valid.",
)

/**
 * Error codes for Klerk configuration errors. The error codes should never change, so if a code is
 * removed or modified, the old code should not be reused.
 */
public enum class KlerkErrorCode(public val code: String) {
    /** An event was used in a command, but it is not declared in any state machine. */
    EventNotDeclared("ERROR-SPEC-1"),

    /** A property holding model references lacks a `validReferences` declaration. */
    MissingValidReferences("ERROR-SPEC-2"),

    /** The specification has no `systemContextProvider`. */
    MissingSystemContextProvider("ERROR-SPEC-3"),

    /** The specification has no `authorization` block. */
    MissingAuthorization("ERROR-SPEC-4"),

    /** The specification has no `managedModels`. */
    MissingManagedModels("ERROR-SPEC-5"),

    /** A model property is not a [dev.klerkframework.klerk.datatypes.DataContainer]. */
    PropertyMustBeDataContainer("ERROR-SPEC-6"),

    /** A job was scheduled by a name that is not registered in the `jobs` block. */
    UnregisteredJobName("ERROR-SPEC-7"),

    /** A stored job cursor cannot be deserialized into the type its job declares. */
    UnloadableJobCursor("ERROR-SPEC-8"),

    /** A job needs a context, but the specification has no `jobContextProvider`. */
    MissingJobContextProvider("ERROR-SPEC-9"),

    /** A model property is a bare [dev.klerkframework.klerk.AttachedBlobID] instead of an `AttachedBlobContainer`. */
    BlobMustBeDeclaredInAContainer("ERROR-SPEC-10"),

    /** An `AttachedBlobContainer` declares no pre-attach step. */
    MissingPreAttachStep("ERROR-SPEC-11"),

    /** A model property is a bare `AttachedStringID` instead of an `AttachedStringContainer`. */
    StringMustBeDeclaredInAContainer("ERROR-SPEC-12"),

    /** A validation rule refers to a property the model does not have. */
    ValidationRuleForUnknownProperty("ERROR-SPEC-13"),

    /** A rule was given as a lambda; it must be a named function reference (`::myRule`). */
    RuleMustBeNamed("ERROR-SPEC-14"),

    /** A state machine is not usable, e.g. it has an unreachable state or a duplicate event. */
    InvalidStateMachine("ERROR-SPEC-15"),

    /** A migration step is not usable. */
    InvalidMigration("ERROR-SPEC-16"),

    /** A view is not usable, e.g. it filters on a state belonging to another model. */
    InvalidView("ERROR-SPEC-17"),

    /** Blobs are used, but [dev.klerkframework.klerk.KlerkSettings] has no `attachedBlobStore`. */
    MissingAttachedBlobStore("ERROR-SETTINGS-1"),

    /** The configured `attachedBlobStore` does not have the bytes for blobs the database refers to. */
    AttachedBlobStoreMissingData("ERROR-SETTINGS-3"),

    /** A stored model does not match its model class. */
    PersistedModelMismatch("ERROR-STORAGE-1"),

    /** A stored model no longer passes validation. */
    PersistedModelInvalid("ERROR-STORAGE-2"),

    /** A cross-property validation rule was violated, e.g. two mutually-exclusive properties were both non-null. */
    InvalidPropertyCollection("ERROR-VALIDATION-1"),

    /** A property failed validation. */
    InvalidProperty("ERROR-VALIDATION-2"),

    /** A `validateWithContext` rule refused the command. No property was examined. */
    PreventedByRule("ERROR-VALIDATION-3"),

    /** A bug in Klerk itself, or in configured code, prevented processing. */
    Internal("ERROR-INTERNAL-1"),

    /** The server cannot process commands right now, e.g. because it has not been started or is shutting down. */
    ServerNotAvailable("ERROR-SERVER-1"),

    /** The command referenced a model, or referenced data, that does not exist. */
    NotFound("ERROR-USER-1"),

    /** A negative authorization rule refused the command. */
    CommandNegativeAuthorizationExist("ERROR-AUTH-1"),

    /** No positive authorization rule allowed the command. */
    CommandPositiveAuthorizationMissing("ERROR-AUTH-2"),

    /** A negative authorization rule refused the read. */
    ReadNegativeAuthorizationExist("ERROR-AUTH-3"),

    /** No positive authorization rule allowed the read. */
    ReadPositiveAuthorizationMissing("ERROR-AUTH-4"),

    /** No positive authorization rule allowed reading the event log. */
    EventLogPositiveAuthorizationMissing("ERROR-AUTH-5"),

    /** A negative authorization rule refused reading the event log. */
    EventLogNegativeAuthorizationExist("ERROR-AUTH-6"),

    /** The actor may read the model, but not this property. */
    UnauthorizedPropertyRead("ERROR-AUTH-7"),

    /** No positive authorization rule allowed reading the attached data. */
    AttachedDataReadPositiveAuthorizationMissing("ERROR-AUTH-8"),

    /** A negative authorization rule refused reading the attached data. */
    AttachedDataReadNegativeAuthorizationExist("ERROR-AUTH-9"),

    /** No positive authorization rule allowed writing attached data. */
    AttachedDataWritePositiveAuthorizationMissing("ERROR-AUTH-10"),

    /** A negative authorization rule refused writing attached data. */
    AttachedDataWriteNegativeAuthorizationExist("ERROR-AUTH-11"),

    /** No positive authorization rule allowed reading the job. */
    JobReadPositiveAuthorizationMissing("ERROR-AUTH-12"),

    /** A negative authorization rule refused reading the job. */
    JobReadNegativeAuthorizationExist("ERROR-AUTH-13"),

    /** A read bypassed authorization, but `KlerkSettings.allowBypassAuthRead` is off. */
    BypassAuthReadNotAllowed("ERROR-AUTH-14"),

    /** The event cannot create a model, i.e. it is not declared in the void state. */
    EventNotPossibleInVoidState("ERROR-COMMAND-1"),

    /** The model's current state does not handle the event. */
    EventNotPossibleInState("ERROR-COMMAND-2"),

    /** The command refers to a model of another type than the event expects. */
    ModelTypeMismatch("ERROR-COMMAND-3"),

    /** The event's visibility is lower than the command requires. */
    EventVisibilityTooLow("ERROR-COMMAND-4"),

    /** The [dev.klerkframework.klerk.command.CommandToken] has already been used. */
    CommandTokenAlreadyUsed("ERROR-COMMAND-5"),

    /** The model changed after the [dev.klerkframework.klerk.command.CommandToken] was created. */
    ModelModifiedSinceTokenCreation("ERROR-COMMAND-6"),

    /** The resulting model failed validation. */
    CommandModelValidation("ERROR-COMMAND-7"),

    /** A model cannot be deleted, because other models still reference it. */
    BrokenReference("ERROR-COMMAND-8"),

    /** The attached data the command refers to does not exist. */
    AttachedDataNotFound("ERROR-COMMAND-9"),

    /** The attached data is already owned by another model. */
    AttachedDataAlreadyOwned("ERROR-COMMAND-10"),

    /** A pre-attach step refused the file, or it is not what the property wants. */
    AttachedDataNotAcceptable("ERROR-COMMAND-11"),

    /** The attached data has not finished being checked by its pre-attach steps. */
    AttachedDataNotProcessed("ERROR-COMMAND-12"),

    /**
     * A new job was refused because the queue is not draining fast enough. Only ever produced for *new* work — yields,
     * retries, spawned children and end-of-life hooks are never refused.
     */
    JobQueueOverloaded("ERROR-JOB-1"),

    /** The job does not exist. */
    JobNotFound("ERROR-JOB-2"),

    /** The job has already finished, so it cannot be cancelled or modified. */
    JobAlreadyTerminal("ERROR-JOB-3");

    override fun toString(): String = code
}

/**
 * Thrown by `attachedData.awaitProcessing` when a step refused the file, or when it is not what its property wants.
 *
 * @property reason why the file was refused, suitable for showing to the user who uploaded it.
 */
public class BlobRejectedException(public val reason: String) :
    KlerkException(KlerkErrorCode.AttachedDataNotAcceptable, reason)

package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataContainer

import kotlin.reflect.KProperty0

public abstract class Problem(public val endUserTranslatedMessage: String) {

    public abstract fun asException(): Exception
    public abstract val recommendedHttpCode: Int
    public abstract val violatedRule: RuleDescription?
    public override fun toString(): String = endUserTranslatedMessage

}

public class InvalidPropertyCollectionProblem(
    endUserTranslatedMessage: String,
    public val fieldsMustBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    public val fieldsMustNotBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400
}

public class InvalidPropertyProblem(
    endUserTranslatedMessage: String,
    public val propertyName: String,
    override val violatedRule: RuleDescription? = null
) : Problem(endUserTranslatedMessage) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400

    override fun toString(): String = endUserTranslatedMessage

}

public data class RuleDescription(val function: Function<Any>, val type: RuleType) {
    public override fun toString(): String = "not implemented" // TODO: should use a translation
}

public enum class RuleType {
    ParametersValidation,
    ContextValidation,
    ParametersAndContextValidation,
    ModelValidation,
    Authorization
}

public class AuthorizationProblem(endUserTranslatedMessage: String, override val violatedRule: RuleDescription?) : Problem(endUserTranslatedMessage) {
    public override fun asException(): AuthorizationException = AuthorizationException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 403
}

public class InternalProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage) {
    public override fun asException(): InternalException = InternalException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 500
    public override val violatedRule: RuleDescription? = null
}

public class StateProblem(endUserTranslatedMessage: String, override val violatedRule: RuleDescription? = null) : Problem(endUserTranslatedMessage) {
    public override fun asException(): IllegalStateException = IllegalStateException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 409
}

public class ServerStateProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage) {
    public override fun asException(): IllegalStateException = IllegalStateException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 503
    public override val violatedRule: RuleDescription? = null
}

public class NotFoundProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage) {
    public override fun asException(): NoSuchElementException = NoSuchElementException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 404
    public override val violatedRule: RuleDescription? = null
}

public class BadRequestProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

public class IdempotenceProblem(endUserTranslatedMessage: String) : Problem(endUserTranslatedMessage) {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(endUserTranslatedMessage)
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

public class AuthorizationException(message: String? = null) : RuntimeException()

/**
 * Indicates a bug in Klerk.
 */
public class InternalException(private val msg: String? = null) : RuntimeException(msg)

public class IllegalConfigurationException(message: String) : RuntimeException(message)

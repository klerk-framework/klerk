package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.extractNameFromFunction
import kotlin.reflect.KProperty0

public abstract class Problem {

    public abstract fun asException(): Exception
    public abstract val recommendedHttpCode: Int
    public abstract val violatedRule: RuleDescription?

}

public class InvalidParametersProblem(
    internal val message: String? = null,
    public val parameterName: String? = null,
    public val fieldsMustBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    public val fieldsMustNotBeNull: Set<KProperty0<DataContainer<*>?>>? = null,
    public override val violatedRule: RuleDescription? = null
) : Problem() {

    internal var exception: Exception? = null

    internal constructor(exception: Exception) : this(message = exception.message) {
        this.exception = exception
    }

    public override fun asException(): IllegalArgumentException = IllegalArgumentException(toString())
    public override val recommendedHttpCode: Int = 400

    override fun toString(): String {
        if (message != null) {
            return message
        }
        if (fieldsMustBeNull?.isNotEmpty() == true) {
            return "${fieldsMustBeNull.first().returnType.javaClass.name} must be null"
        }
        if (fieldsMustNotBeNull?.isNotEmpty() == true) {
            return "${fieldsMustNotBeNull.first().returnType.javaClass.name} must be not null"
        }
        return "Unknown problem"
    }

}

public data class RuleDescription(val function: Function<Any>, val type: RuleType) {
    public override fun toString(): String = extractNameFromFunction(function)
}

public enum class RuleType {
    ParametersValidation,
    ContextValidation,
    ParametersAndContextValidation,
    ModelValidation,
}

public class AuthorizationProblem(private val message: String? = null) : Problem() {
    public override fun asException(): AuthorizationException = AuthorizationException(message)
    public override fun toString(): String = message ?: "Not authorized"
    public override val recommendedHttpCode: Int = 403
    public override val violatedRule: RuleDescription? = null
}

public class InternalProblem(private val message: String? = null) : Problem() {
    public override fun asException(): InternalException = InternalException(message)
    public override fun toString(): String = message ?: "Internal Problem"
    public override val recommendedHttpCode: Int = 500
    public override val violatedRule: RuleDescription? = null
}

public class StateProblem(private val message: String? = null, override val violatedRule: RuleDescription? = null) : Problem() {
    public override fun asException(): IllegalStateException = IllegalStateException(message)
    public override fun toString(): String = message ?: "Illegal State"
    public override val recommendedHttpCode: Int = 409
}

public class ServerStateProblem(private val message: String? = null) : Problem() {
    public override fun asException(): IllegalStateException = IllegalStateException(message)
    public override fun toString(): String = message ?: "Illegal State"
    public override val recommendedHttpCode: Int = 503
    public override val violatedRule: RuleDescription? = null
}

public class NotFoundProblem(private val message: String? = null) : Problem() {
    public override fun asException(): NoSuchElementException = NoSuchElementException(message)
    public override fun toString(): String = message ?: "Not found"
    public override val recommendedHttpCode: Int = 404
    public override val violatedRule: RuleDescription? = null
}

public class BadRequestProblem(private val message: String?) : Problem() {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException(message)
    public override fun toString(): String = message ?: "Bad Request"
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

public class IdempotenceProblem() : Problem() {
    public override fun asException(): IllegalArgumentException = IllegalArgumentException("CommandToken has already been used")
    public override fun toString(): String = "CommandToken has already been used"
    public override val recommendedHttpCode: Int = 400
    public override val violatedRule: RuleDescription? = null
}

public class AuthorizationException(message: String? = null) : RuntimeException()

/**
 * Indicates a bug in Klerk.
 */
public class InternalException(private val msg: String? = null) : RuntimeException(msg)

public class IllegalConfigurationException(message: String) : RuntimeException(message)

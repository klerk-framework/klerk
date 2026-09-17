package dev.klerkframework.klerk

import dev.klerkframework.klerk.statemachine.StateMachine
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * A reference to a specific event in a state machine
 */
public data class EventReference(val modelName: String, val eventName: String) {
    init {
        require("/" !in modelName)
        require("/" !in eventName)
        require(":" !in modelName)
        require(":" !in eventName)
    }

    override fun toString(): String = "$modelName:$eventName"

    public companion object {
        /** @throws IllegalArgumentException if [value] is not of the form `<modelName>:<eventName>` */
        public fun parse(value: String): EventReference {
            val splitted = value.split(":")
            require(splitted.size == 2)
            return EventReference(splitted.first(), splitted.last())
        }

        /** The reference in [value], or null if it is not one. */
        public fun parseOrNull(value: String): EventReference? = runCatching { parse(value) }.getOrNull()
    }
}

/**
 * The visibility level of an event. The higher levels expand on the lower levels, e.g. InterStateMachine can be
 * created in all places where StateMachineInternal is allowed.
 */
public enum class EventVisibility(internal val level: Int) {

    /**
     * Can only be created within the same statemachine.
     */
    StateMachineInternal(1),

    /**
     * Can be created in any statemachine.
     */
    InterStateMachine(2),

    /**
     * Can be created in any statemachine and in application code. This level can be used for events that are triggered
     * by the system, e.g. in a Job.
     */
    System(3),

    /**
     * Can be created in any statemachine and in application code.
     */
    Application(4),

    /**
     * Can be created in any statemachine and in application code. Klerk doesn't differentiate this from
     * [Application], but this level can be used as a signal to other code (e.g., auto-generated UI or API) that it
     * should handle this event.
     */
    External(5)
}

/**
 * Base class of the four event kinds ([VoidEventNoParameters], [VoidEventWithParameters],
 * [InstanceEventNoParameters], [InstanceEventWithParameters]). Application code declares events as `object`s
 * extending one of those four, then registers them in a [StateMachine] with `event(...)` / `onEvent(...)`.
 *
 * ```kotlin
 * object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(External)
 * object PublishBook : InstanceEventNoParameters<Book>(External)
 * ```
 *
 * The model class and the parameters class are read from the type arguments, so they are written once.
 */
public sealed class Event<T : Any, P>(public val visibility: EventVisibility) {

    /** The `T` of the event kind this was declared as. */
    internal val forModel: KClass<*> = typeArgument(0)

    /** Identifies this event: the model name and the event name. */
    public val id: EventReference
        get() = EventReference(forModel.simpleName!!, name)

    /** The name of this event, i.e. the simple name of the declaring object. */
    public val name: String
        get() = this::class.simpleName!!

    /**
     * The [index]th type argument of the event-kind supertype. An `object` (or a class) that names its type arguments
     * concretely — which an event declaration always does — records them in its supertype, so nothing has to be
     * passed to the constructor.
     */
    protected fun typeArgument(index: Int): KClass<*> {
        val supertype = this::class.allSupertypes.firstOrNull { (it.classifier as? KClass<*>) in eventKinds }
            ?: throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "${this::class.simpleName} does not extend one of the four event kinds directly",
            )
        return (supertype.arguments.getOrNull(index)?.type?.classifier as? KClass<*>)
            ?: throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "Could not work out the type arguments of the event '${this::class.simpleName}'. Declare it as an " +
                        "object (or a class) that names them concretely, e.g. " +
                        "'object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(External)'.",
            )
    }

    override fun toString(): String = id.toString()

}

private val eventKinds = setOf(
    VoidEventWithParameters::class,
    VoidEventNoParameters::class,
    InstanceEventWithParameters::class,
    InstanceEventNoParameters::class,
)

/** An event that creates a model, i.e. one handled in the void state. */
public sealed class VoidEvent<T : Any, P>(visibility: EventVisibility) : Event<T, P>(visibility)

/** An event that acts on an existing model. */
public sealed class InstanceEvent<T : Any, P>(visibility: EventVisibility) : Event<T, P>(visibility)

/**
 * A void event (creates a new model of type [T]) that takes parameters of type [P] when handled. Declare a
 * handler function `fun create(arg: VoidEventArgs<T, P, C, V>): T`.
 */
public abstract class VoidEventWithParameters<T : Any, P : Any>(visibility: EventVisibility) :
    VoidEvent<T, P>(visibility) {
    /** The class of the event's parameters. */
    @Suppress("UNCHECKED_CAST")
    public val parametersClass: KClass<P> = typeArgument(1) as KClass<P>
}

/**
 * A void event (creates a new model of type [T]) that takes no parameters. Declare a handler function
 * `fun create(arg: VoidEventArgs<T, Nothing?, C, V>): T`.
 */
public abstract class VoidEventNoParameters<T : Any>(visibility: EventVisibility) :
    VoidEvent<T, Nothing?>(visibility)

/**
 * An instance event (acts on an existing model of type [T]) that takes parameters of type [P] when handled.
 * Declare a handler function `fun update(arg: InstanceEventArgs<T, P, C, V>): T`.
 */
public abstract class InstanceEventWithParameters<T : Any, P : Any>(
    visibility: EventVisibility,
) : InstanceEvent<T, P>(visibility) {
    /** The class of the event's parameters. */
    @Suppress("UNCHECKED_CAST")
    public val parametersClass: KClass<P> = typeArgument(1) as KClass<P>
}

/**
 * An instance event (acts on an existing model of type [T]) that takes no parameters. Declare a handler function
 * `fun archive(arg: InstanceEventArgs<T, Nothing?, C, V>): T`.
 */
public abstract class InstanceEventNoParameters<T : Any>(visibility: EventVisibility) :
    InstanceEvent<T, Nothing?>(visibility)

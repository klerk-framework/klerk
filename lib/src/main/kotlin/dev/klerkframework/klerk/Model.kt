package dev.klerkframework.klerk

import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.time.Instant

/**
 * Wires a model's props class to its [StateMachine] and [ModelViews]. Registered via
 * `SpecificationBuilder.managedModels { model(...) }`; not normally constructed directly.
 */
public data class ManagedModel<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    val kClass: KClass<T>,
    val stateMachine: StateMachine<T, ModelStates, C, V>,
    val views: ModelViews<T, C>,
)

/**
 * A registered [ModelView] together with the model class it holds, as returned by `Specification.registeredViews`.
 */
public data class RegisteredView<C : KlerkContext>(val modelClass: KClass<out Any>, val view: ModelView<out Any, C>)

/**
 * A stored instance: metadata (id, timestamps, current [state]) plus the model's props of type [T].
 * Returned from reads (e.g. [ModelReader.get]); never constructed directly by application code.
 *
 * @property state the name of the current state in the model's [dev.klerkframework.klerk.statemachine.StateMachine].
 * Compare states with [isIn] and get the enum value with [stateAs] rather than comparing this name; it is the raw form,
 * meant for rendering, serializing and matching untyped input.
 */
public data class Model<T : Any>(
    val id: ModelID<T>,
    val createdAt: Instant,
    val lastPropsUpdatedAt: Instant,
    val lastStateTransitionAt: Instant,
    val state: String,
    val timeTrigger: Instant?,
    val props: T,
) {
    /**
     * The time when this model was last modified, either by a state transition or updating properties.
     */
    public val lastModifiedAt: Instant get() = maxOf(lastPropsUpdatedAt, lastStateTransitionAt)

    /**
     * True if [state] is any of [states]. The way to compare a state:
     *
     * ```
     * if (book.isIn(BookStates.Published, BookStates.Reprinted)) { ... }
     * ```
     */
    public fun isIn(vararg states: Enum<*>): Boolean = states.any { it.name == state }

    /** True if [state] is any of [states], e.g. a set of states declared once and used in several places. */
    public fun isIn(states: Collection<Enum<*>>): Boolean = states.any { it.name == state }

    /**
     * The current state as a value of [S], e.g. to handle every state in a `when`.
     *
     * @throws IllegalArgumentException if [S] has no constant named [state], which means it is not this model's state
     * enum.
     */
    public inline fun <reified S : Enum<S>> stateAs(): S =
        requireNotNull(enumEntries<S>().firstOrNull { it.name == state }) {
            "${S::class.simpleName} has no constant named '$state', so it is not the state enum of " +
                "${props::class.simpleName}"
        }

    override fun toString(): String = props.toString()
}

/**
 * Implemented by a props class to declare cross-property validation rules (rules spanning more than one property).
 * Each function returns a [PropertyCollectionValidity] evaluated after individual property validation passes.
 */
public fun interface Validatable {
    /** The cross-property rules. Each must be a named function reference. */
    public fun validators(): Set<() -> PropertyCollectionValidity>
}

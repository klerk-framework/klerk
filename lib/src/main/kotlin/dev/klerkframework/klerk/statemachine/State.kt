package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.statemachine.Block.*
import dev.klerkframework.klerk.statemachine.BlockType.*
import kotlin.time.Duration
import kotlin.time.Instant

/** A state in a [StateMachine]. */
@SpecificationMarker
public sealed class State<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    public val name: String, internal val modelName: String,
) {

    /** Identifies this state among all state machines. */
    public val id: StateID = StateID(modelName, name)
    internal abstract var enterBlock: Block<T, ModelStates, C, V>
    internal abstract var exitBlock: Block<T, ModelStates, C, V>

    internal fun canHandle(eventReference: EventReference): Boolean = getEvents().any { it.id == eventReference }

    internal fun onKlerkStart(specification: Specification<C, V>) {

    }

    internal abstract fun getEvents(): Set<Event<T, *>>
    internal abstract fun <P> getBlock(event: Event<T, P>): Block<T, ModelStates, C, V>

}

/** The state a model is in before it exists. Only void events are handled here, typically by creating the model. */
public class VoidState<T : Any, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
    name: String,
    modelName: String,
) : State<T, ModelStates, C, V>(name, modelName) {

    override var enterBlock: Block<T, ModelStates, C, V> = VoidLifecycleBlock("Enter block for state '$name'", Enter)
    override var exitBlock: Block<T, ModelStates, C, V> = VoidLifecycleBlock("Exit block for state '$name'", Exit)
    private val _onEventBlocks: MutableList<Pair<VoidEvent<T, *>, VoidEventBlock<T, *, ModelStates, C, V>>> =
        mutableListOf()

    /** The `onEvent` blocks declared for this state, in declaration order. */
    public val onEventBlocks: List<Pair<VoidEvent<T, *>, VoidEventBlock<T, *, ModelStates, C, V>>>
        get() = _onEventBlocks

    /**
     * Declares what happens when [event] is received while the model doesn't exist yet. [event] must already be
     * declared with `StateMachine.event(...)`. [init] should call `createModel` — that's the only executable that
     * makes sense here, since there is no model to `update`, `delete`, or `transitionTo` yet.
     */
    public fun <P : Any?> onEvent(event: VoidEvent<T, P>, init: VoidEventBlock<T, P, ModelStates, C, V>.() -> Unit) {
        require(event::class.objectInstance != null) { "Event ${event.name} must be declared as 'object'" }
        val onEventBlock =
            VoidEventBlock<T, P, ModelStates, C, V>("Event block (${event.name}) for initial state", BlockType.Event)
        onEventBlock.init()
        _onEventBlocks.add(event to onEventBlock)
    }


    override fun getEvents(): Set<Event<T, *>> = onEventBlocks.map { it.first }.toSet()

    @Suppress("UNCHECKED_CAST")
    override fun <P> getBlock(event: Event<T, P>): VoidEventBlock<T, P, ModelStates, C, V> =
        onEventBlocks.single { it.first == event }.second as VoidEventBlock<T, P, ModelStates, C, V>

    internal fun getBlockByEventReference(id: EventReference): VoidEventBlock<T, *, ModelStates, C, V> =
        onEventBlocks.single { it.first.id == id }.second

}

/** A state an existing model can be in. */
public class InstanceState<T : Any, ModelStates : Enum<*>, C : KlerkContext, V> internal constructor(
    name: String,
    modelName: String,
) : State<T, ModelStates, C, V>(name, modelName) {

    override var enterBlock: Block<T, ModelStates, C, V> =
        InstanceLifecycleBlock("Enter block for state '$name'", Enter)
    override var exitBlock: Block<T, ModelStates, C, V> = InstanceLifecycleBlock("Exit block for state '$name'", Exit)
    internal var timeBlock: InstanceLifecycleBlock<T, ModelStates, C, V>? = null
    private val _onEventBlocks: MutableList<Pair<InstanceEvent<T, *>, InstanceEventBlock<T, *, ModelStates, C, V>>> =
        mutableListOf()

    /** The `onEvent` blocks declared for this state, in declaration order. */
    public val onEventBlocks: List<Pair<InstanceEvent<T, *>, InstanceEventBlock<T, *, ModelStates, C, V>>>
        get() = _onEventBlocks
    internal var afterDuration: Duration? = null
    private var enterBlockDeclared = false
    private var exitBlockDeclared = false
    internal var atTimeFunction: ((args: LifecycleArgs<T, C, V>) -> Instant)? = null

    /**
     * Runs [init] whenever a model enters this state, whether via `createModel` (if this is the initial state) or
     * via `transitionTo`/`transitionWhen` from another state. At most one `onEnter` per state.
     */
    public fun onEnter(init: InstanceLifecycleBlock<T, ModelStates, C, V>.() -> Unit) {
        if (enterBlockDeclared) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state '$name' declares onEnter more than once",
            )
        }
        val b = InstanceLifecycleBlock<T, ModelStates, C, V>("Enter block for state '$name'", Enter)
        b.init()
        enterBlock = b
        enterBlockDeclared = true
    }

    /**
     * Runs [init] whenever a model leaves this state, right before the transition takes effect. At most one
     * `onExit` per state.
     */
    public fun onExit(init: InstanceLifecycleBlock<T, ModelStates, C, V>.() -> Unit) {
        if (exitBlockDeclared) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state '$name' declares onExit more than once",
            )
        }
        val b = InstanceLifecycleBlock<T, ModelStates, C, V>("Exit block for state '$name'", Exit)
        b.init()
        exitBlock = b
        exitBlockDeclared = true
    }

    /**
     * Declares what happens when [event] is received while the model is in this state. [event] must already be
     * declared with `StateMachine.event(...)`; an event not declared here for the model's current state is
     * rejected with a `StateProblem` when submitted.
     */
    public fun <P> onEvent(event: InstanceEvent<T, P>, init: InstanceEventBlock<T, P, ModelStates, C, V>.() -> Unit) {
        require(event::class.objectInstance != null) { "Event ${event.name} must be declared as 'object'" }
        val onEventBlock = InstanceEventBlock<T, P, ModelStates, C, V>(
            "Event block (${event.name}) for state '$name'", BlockType.Event,
        )
        onEventBlock.init()
        _onEventBlocks.add(event to onEventBlock)
    }

    /**
     * Tries to execute a block after a certain duration, counting from when the model entered this state.
     *
     * In case the block violates any rule (e.g. block says that the model should be deleted, but another model has
     * a relation to this and prevents deletion), the block will not be executed and no further attempts will be made.
     *
     * Be careful not to create time-triggered loops as they may cause significant stress on the system.
     *
     * There is no guarantee that the block will be executed exactly on the specified time as there is a variation of a
     * few seconds.
     */
    public fun after(duration: Duration, init: InstanceLifecycleBlock<T, ModelStates, C, V>.() -> Unit) {
        checkNoTimeBlock()
        val b = InstanceLifecycleBlock<T, ModelStates, C, V>("After duration block for state '$name'", Time)
        b.init()
        timeBlock = b
        afterDuration = duration
    }

    /**
     * Tries to execute a block at a certain time.
     *
     * In case the block violates any rule (e.g. block says that the model should be deleted, but another model has
     * a relation to this and prevents deletion), the block will not be executed and no further attempts will be made.
     *
     * Be careful not to create time-triggered loops as they may cause significant stress on the system.
     *
     * There is no guarantee that the block will be executed exactly on the specified time as there is a variation of a
     * few seconds.
     */
    public fun atTime(
        f: (args: LifecycleArgs<T, C, V>) -> Instant,
        init: InstanceLifecycleBlock<T, ModelStates, C, V>.() -> Unit,
    ) {
        checkNoTimeBlock()
        val b = InstanceLifecycleBlock<T, ModelStates, C, V>("At-time block for state '$name'", Time)
        b.init()
        timeBlock = b
        atTimeFunction = f
    }

    private fun checkNoTimeBlock() {
        if (timeBlock != null) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "The state '$name' declares more than one of after/atTime",
            )
        }
    }

    override fun getEvents(): Set<Event<T, *>> = onEventBlocks.map { it.first }.toSet()

    @Suppress("UNCHECKED_CAST")
    override fun <P> getBlock(event: Event<T, P>): InstanceEventBlock<T, P, ModelStates, C, V> =
        onEventBlocks.single { it.first == event }.second as InstanceEventBlock<T, P, ModelStates, C, V>

    internal fun getBlockByEventReference(id: EventReference): InstanceEventBlock<T, *, ModelStates, C, V> =
        onEventBlocks.single { it.first.id == id }.second

}

package dev.klerkframework.klerk.statemachine;

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.statemachine.Block.*
import dev.klerkframework.klerk.statemachine.BlockType.*
import kotlinx.datetime.Instant
import kotlin.time.Duration

@ConfigMarker
public sealed class State<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    internal val name: String, internal val modelName: String
) {

    public val id: StateId = StateId(modelName, name)
    internal abstract var enterBlock: Block<T, ModelStates, C, V>
    internal abstract var exitBlock: Block<T, ModelStates, C, V>

    internal fun canHandle(eventReference: EventReference): Boolean = getEvents().map { it.id }.contains(eventReference)

    internal fun onKlerkStart(config: Config<C, V>) {

    }

    internal abstract fun getEvents(): Set<Event<T, *>>
    internal abstract fun <P> getBlock(event: Event<T, P>): Block<T, ModelStates, C, V>

}

public class VoidState<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, modelName: String) :
    State<T, ModelStates, C, V>(name, modelName) {

    override var enterBlock: Block<T, ModelStates, C, V> = VoidNonEventBlock("Enter block for state '$name'", Enter)
    override var exitBlock: Block<T, ModelStates, C, V> = VoidNonEventBlock("Exit block for state '$name'", Exit)
    internal val onEventBlocks: MutableList<Pair<VoidEvent<T, *>, VoidEventBlock<T, *, ModelStates, C, V>>> =
        mutableListOf()

    public fun <P : Any?> onEvent(event: VoidEvent<T, P>, init: VoidEventBlock<T, P, ModelStates, C, V>.() -> Unit) {
        require(event::class.objectInstance != null) { "Event ${event.name} must be declared as 'object'" }
        val onEventBlock =
            VoidEventBlock<T, P, ModelStates, C, V>("Event block (${event.name}) for initial state", BlockType.Event)
        onEventBlock.init()
        onEventBlocks.add(Pair(event, onEventBlock))
    }


    override fun getEvents(): Set<Event<T, *>> = onEventBlocks.map { it.first }.toSet()

    @Suppress("UNCHECKED_CAST")
    override fun <P> getBlock(event: Event<T, P>): VoidEventBlock<T, P, ModelStates, C, V> =
        onEventBlocks.single { it.first == event }.second as VoidEventBlock<T, P, ModelStates, C, V>

    internal fun getBlockByEventReference(id: EventReference): VoidEventBlock<T, *, ModelStates, C, V> =
        onEventBlocks.single { it.first.id == id }.second

}

public class InstanceState<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(name: String, modelName: String) :
    State<T, ModelStates, C, V>(name, modelName) {

    override var enterBlock: Block<T, ModelStates, C, V> = InstanceNonEventBlock("Enter block for state '$name'", Enter)
    override var exitBlock: Block<T, ModelStates, C, V> = InstanceNonEventBlock("Exit block for state '$name'", Exit)
    internal var timeBlock: InstanceNonEventBlock<T, ModelStates, C, V>? = null
    internal val onEventBlocks: MutableList<Pair<InstanceEvent<T, *>, InstanceEventBlock<T, *, ModelStates, C, V>>> =
        mutableListOf()
    internal var afterDuration: Duration? = null
    internal var atTimeFunction: ((args: ArgForInstanceNonEvent<T, C, V>) -> Instant)? = null

    public fun onEnter(init: InstanceNonEventBlock<T, ModelStates, C, V>.() -> Unit) {
        val b = InstanceNonEventBlock<T, ModelStates, C, V>("Enter block for state '$name'", Enter)
        b.init()
        enterBlock = b
    }

    public fun onExit(init: InstanceNonEventBlock<T, ModelStates, C, V>.() -> Unit) {
        val b = InstanceNonEventBlock<T, ModelStates, C, V>("Exit block for state '$name'", Exit)
        b.init()
        exitBlock = b
    }

    public fun <P> onEvent(event: InstanceEvent<T, P>, init: InstanceEventBlock<T, P, ModelStates, C, V>.() -> Unit) {
        require(event::class.objectInstance != null) { "Event ${event.name} must be declared as 'object'" }
        val onEventBlock = InstanceEventBlock<T, P, ModelStates, C, V>(
            "Event block (${event.name}) for state '$name'", BlockType.Event
        )
        onEventBlock.init()
        onEventBlocks.add(Pair(event, onEventBlock))
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
    public fun after(duration: Duration, init: InstanceNonEventBlock<T, ModelStates, C, V>.() -> Unit) {
        check(timeBlock == null)
        val b = InstanceNonEventBlock<T, ModelStates, C, V>("After duration block for state '$name'", Time)
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
        f: (args: ArgForInstanceNonEvent<T, C, V>) -> Instant,
        init: InstanceNonEventBlock<T, ModelStates, C, V>.() -> Unit
    ) {
        check(timeBlock == null)
        val b = InstanceNonEventBlock<T, ModelStates, C, V>("At-time block for state '$name'", Time)
        b.init()
        timeBlock = b
        atTimeFunction = f
    }

    override fun getEvents(): Set<Event<T, *>> = onEventBlocks.map { it.first }.toSet()

    @Suppress("UNCHECKED_CAST")
    override fun <P> getBlock(event: Event<T, P>): InstanceEventBlock<T, P, ModelStates, C, V> =
        onEventBlocks.single { it.first == event }.second as InstanceEventBlock<T, P, ModelStates, C, V>

    internal fun getBlockByEventReference(id: EventReference): InstanceEventBlock<T, *, ModelStates, C, V> {
        return onEventBlocks.single { it.first.id == id }.second
    }

}

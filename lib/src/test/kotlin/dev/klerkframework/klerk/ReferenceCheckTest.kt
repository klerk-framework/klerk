package dev.klerkframework.klerk

import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A model must never be committed referring to a model that does not exist, also when the reference is created, or its
 * target deleted, by another command in the same run.
 */
class ReferenceCheckTest {

    @Test
    fun `a run cannot create a reference to a model it deletes`() = runBlocking<Unit> {
        val klerk = start()
        val target = create(klerk, "target")

        val problem = assertIs<CommandResult.Failure<*>>(klerk.handle(Command(AdoptAndDelete, target), Ctx.system()))
            .problems.single()
        assertEquals(KlerkErrorCode.BrokenReference, problem.code)
        assertNotNull(klerk.read(Ctx.system()) { getOrNull(target) }, "nothing may have been committed")
    }

    @Test
    fun `a model cannot be given a reference to a model that does not exist`() = runBlocking<Unit> {
        val klerk = start()
        val node = create(klerk, "node")

        val problem = assertIs<CommandResult.Failure<*>>(klerk.handle(Command(LinkToGhost, node), Ctx.system()))
            .problems.single()
        assertEquals(KlerkErrorCode.BrokenReference, problem.code, problem.toString())
        assertNull(klerk.read(Ctx.system()) { get(node) }.props.next)
    }

    @Test
    fun `a model can be deleted by a run that also removes the last reference to it`() = runBlocking<Unit> {
        val klerk = start()
        val target = create(klerk, "target")
        val referrer = create(klerk, "referrer", next = target)

        klerk.handle(Command(UnlinkAndDelete, target), Ctx.system()).getOrThrow()
        assertNull(klerk.read(Ctx.system()) { getOrNull(target) })
        assertNull(klerk.read(Ctx.system()) { get(referrer) }.props.next)
    }

    @Test
    fun `a model that is still referenced cannot be deleted`() = runBlocking<Unit> {
        val klerk = start()
        val target = create(klerk, "target")
        create(klerk, "referrer", next = target)

        val problem = assertIs<CommandResult.Failure<*>>(klerk.handle(Command(DeleteNode, target), Ctx.system()))
            .problems.single()
        assertEquals(KlerkErrorCode.BrokenReference, problem.code)
    }

    private suspend fun start(): Klerk<Ctx, NodeViews> {
        val views = NodeViews()
        val specification = SpecificationBuilder<Ctx, NodeViews>(views).build {
            eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
            managedModels { model(Node::class, nodeStateMachine(views), views) }
            authorization { allowEverythingInsecurely() }
            systemContextProvider { Ctx.system() }
        }
        val klerk = Klerk.create(specification, testSettings())
        klerk.meta.start(installShutdownHook = false)
        return klerk
    }

    private suspend fun create(klerk: Klerk<Ctx, NodeViews>, name: String, next: ModelID<Node>? = null): ModelID<Node> =
        requireNotNull(
            klerk.handle(Command(CreateNode, NodeParams(NodeName(name), next)), Ctx.system())
                .getOrThrow().primaryModel,
        )
}

class NodeName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

data class Node(val name: NodeName, val next: ModelID<Node>?)

data class NodeParams(val name: NodeName, val next: ModelID<Node>?)

class NodeViews : ModelViews<Node, Ctx>()

enum class NodeStates { Alive }

object CreateNode : VoidEventWithParameters<Node, NodeParams>(External)

object DeleteNode : InstanceEventNoParameters<Node>(External)

object Unlink : InstanceEventNoParameters<Node>(External)

/** Points the node at an id that no model has, without going through the parameters. */
object LinkToGhost : InstanceEventNoParameters<Node>(External)

/** Creates a node that refers to this one, then deletes this one. */
object AdoptAndDelete : InstanceEventNoParameters<Node>(External)

/** Removes every reference to this node, then deletes it. */
object UnlinkAndDelete : InstanceEventNoParameters<Node>(External)

fun nodeStateMachine(views: NodeViews) = stateMachine<Node, NodeStates, Ctx, NodeViews> {
    event(CreateNode) { validReferences(NodeParams::next, views.all) }
    event(DeleteNode) {}
    event(Unlink) {}
    event(LinkToGhost) {}
    event(AdoptAndDelete) {}
    event(UnlinkAndDelete) {}

    voidState {
        onEvent(CreateNode) { createModel(NodeStates.Alive, ::newNode) }
    }

    state(NodeStates.Alive) {
        onEvent(DeleteNode) { delete() }
        onEvent(Unlink) { update(::unlinked) }
        onEvent(LinkToGhost) { update(::linkedToGhost) }
        onEvent(AdoptAndDelete) { commands(::adoptThenDelete) }
        onEvent(UnlinkAndDelete) { commands(::unlinkReferrersThenDelete) }
    }
}

fun newNode(args: VoidEventArgs<Node, NodeParams, Ctx, NodeViews>): Node =
    Node(args.command.params.name, args.command.params.next)

fun unlinked(args: InstanceEventArgs<Node, Nothing?, Ctx, NodeViews>): Node = args.model.props.copy(next = null)

fun linkedToGhost(args: InstanceEventArgs<Node, Nothing?, Ctx, NodeViews>): Node =
    args.model.props.copy(next = ModelID(args.model.id.value + 1))

@Suppress("UNCHECKED_CAST")
fun adoptThenDelete(args: InstanceEventArgs<Node, Nothing?, Ctx, NodeViews>): List<Command<Any, Any>> = listOf(
    Command(CreateNode, NodeParams(NodeName("adopted"), args.model.id)),
    Command(DeleteNode, args.model.id),
) as List<Command<Any, Any>>

@Suppress("UNCHECKED_CAST")
fun unlinkReferrersThenDelete(args: InstanceEventArgs<Node, Nothing?, Ctx, NodeViews>): List<Command<Any, Any>> = (
    args.reader.referencing(Node::class, args.model.id).map { Command(Unlink, it.id) } +
        Command(DeleteNode, args.model.id)
    )
    as List<Command<Any, Any>>

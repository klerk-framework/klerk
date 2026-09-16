package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.ExperimentalKlerkApi
import kotlin.reflect.KFunction1

/**
 * A decision-tree algorithm taking parameters [P] and producing a result [R], expressed as a graph of [Decision]
 * nodes so it can be both executed and rendered as a diagram (see [dev.klerkframework.klerk.misc.generateFlowChart]).
 * Subclass and implement [configure] to declare the graph via [AlgorithmBuilder]; call [execute] to run it.
 */
@ExperimentalKlerkApi
public abstract class FlowChartAlgorithm<P, R>(public val name: String) {

    private val nodesAndStartNode: Pair<Set<Node<P, R>>, Node<P, R>> by lazy { initConfig() }

    internal val nodes: Set<Node<P, R>>
        get() = nodesAndStartNode.first

    private val startNode: Node<P, R>
        get() = nodesAndStartNode.second

    private fun initConfig(): Pair<Set<Node<P, R>>, Node<P, R>> {
        val algoBuilder = AlgorithmBuilder<P, R>(name)
        configure().invoke(algoBuilder)
        return algoBuilder.build()
    }

    /**
     * Declares the graph: register [Decision] nodes via [AlgorithmBuilder.booleanNode]/[AlgorithmBuilder.enumNode] and
     * set the entry point with [AlgorithmBuilder.start].
     */
    public abstract fun configure(): AlgorithmBuilder<P, R>.() -> Unit

    /** Runs the algorithm starting at the configured start node until a node terminates, and returns its result. */
    public fun execute(params: P): R = executeWithLogs(params).first

    internal fun executeWithLogs(params: P): Pair<R, String> = executeNode(startNode, params, "")

    private fun executeNode(node: Node<P, R>, params: P, logs: String): Pair<R, String> {
        return when (val execution = node.execute(params)) {
            is NodeExecutionResult.Next -> {
                val newLogs = logs.plus("${node.id}=${execution.functionResult} -> ")
                val next = nodes.single { it.id == execution.decision.toString() }
                executeNode(next, params, newLogs)
            }

            is NodeExecutionResult.Termination -> {
                val newLogs =
                    logs.plus("${node.id}=${execution.functionResult} -> Result: ${execution.terminationResult}")
                Pair(execution.terminationResult, newLogs)
            }
        }
    }

}

/** DSL receiver for [FlowChartAlgorithm.configure]. */
@ExperimentalKlerkApi
public class AlgorithmBuilder<P, R>(private val name: String) {

    private var startNodeId: Decision<*, P>? = null
    internal val nodes: MutableSet<Node<P, R>> = mutableSetOf()

    /**
     * Marks [decision] as the entry node of the graph. Required — [FlowChartAlgorithm.execute] fails if never called.
     */
    public fun start(decision: Decision<*, P>) {
        startNodeId = decision
    }

    /** Adds a node that branches on a boolean [decision]. Configure each branch with [BooleanNodeBuilder.on]. */
    public fun booleanNode(
        decision: Decision<Boolean, P>,
        init: BooleanNodeBuilder<Decision<Boolean, P>, P, R>.() -> Unit,
    ) {
        val builder = BooleanNodeBuilder<Decision<Boolean, P>, P, R>()
        builder.init()
        nodes.add(builder.build(decision))
    }

    /** Adds a node that branches on an enum-valued [decision]. Configure each branch with [EnumNodeBuilder.on]. */
    public fun <E : Enum<*>> enumNode(
        decision: Decision<E, P>,
        init: EnumNodeBuilder<E, Decision<E, P>, P, R>.() -> Unit,
    ) {
        val builder = EnumNodeBuilder<E, Decision<E, P>, P, R>()
        builder.init()
        nodes.add(builder.build(decision))
    }

    internal fun build(): Pair<Set<Node<P, R>>, Node<P, R>> {
        val start = requireNotNull(startNodeId) { "Start node must be defined" }
        val startNode = nodes.single { it.id == start.toString() }
        return Pair(nodes, startNode)
    }

}

/** A single node in a [FlowChartAlgorithm]'s graph. */
@ExperimentalKlerkApi
public sealed class Node<P, R> {
    /** Evaluates this node's decision on [params]. */
    public abstract fun execute(params: P): NodeExecutionResult<P, R>

    /** Identifies the node in a rendered diagram. */
    public abstract val id: String
    /** The node's label in a rendered diagram. */
    public abstract val humanReadable: String
    /** The next node for each decision outcome that continues. */
    public abstract val goTos: Map<*, Decision<out Any, P>>
    /** The result for each decision outcome that ends the algorithm. */
    public abstract val terminations: Map<*, R>

    internal data class BooleanNode<P, R>(
        val decision: Decision<Boolean, P>,
        override val goTos: Map<Boolean, Decision<out Any, P>>,
        override val terminations: Map<Boolean, R>,
    ) : Node<P, R>() {
        override fun execute(params: P): NodeExecutionResult<P, R> {
            val functionResult = decision.function.invoke(params)
            val finishResult = terminations[functionResult]
            if (finishResult != null) {
                return NodeExecutionResult.Termination(finishResult, functionResult.toString())
            }
            return NodeExecutionResult.Next(requireNotNull(goTos[functionResult]), functionResult.toString())
        }

        override val id: String = decision.toString()

        override val humanReadable: String = decision.name
    }

    internal data class EnumNode<E : Enum<*>, P, R>(
        val decision: Decision<E, P>,
        override val goTos: Map<E, Decision<out Any, P>>,
        override val terminations: Map<E, R>,
    ) : Node<P, R>() {
        override fun execute(params: P): NodeExecutionResult<P, R> {
            val functionResult = decision.function.invoke(params)
            val finishResult = terminations[functionResult]
            if (finishResult != null) {
                return NodeExecutionResult.Termination(finishResult, functionResult.toString())
            }
            return NodeExecutionResult.Next(requireNotNull(goTos[functionResult]), functionResult.toString())
        }

        override val id: String = decision.toString()

        override val humanReadable: String = decision.name
    }

    internal data class FacadeNode<P, R : Any>(val f: KFunction1<P, R>) : Node<P, R>() {
        override fun execute(params: P): NodeExecutionResult<P, R> {
            val result = f.invoke(params)
            return NodeExecutionResult.Termination(result, result.toString())
        }

        override val id: String = f.name

        override val humanReadable: String = extractNameFromFunction(f)

        override val goTos: Map<Any, Decision<out Any, P>> = emptyMap()

        override val terminations: Map<Any, R> = emptyMap()

    }
}

/** The outcome of evaluating one [Node] against a set of parameters. */
@ExperimentalKlerkApi
public sealed class NodeExecutionResult<P, R> {
    /** The algorithm is done: [terminationResult] is the final result. */
    public data class Termination<P, R>(val terminationResult: R, val functionResult: String) :
        NodeExecutionResult<P, R>()

    /** The algorithm continues at the node identified by [decision]. */
    public data class Next<P, R>(val decision: Decision<out Any, P>, val functionResult: String) :
        NodeExecutionResult<P, R>()
}

/** DSL receiver for [AlgorithmBuilder.booleanNode]. */
@ExperimentalKlerkApi
public class BooleanNodeBuilder<D : Decision<Boolean, P>, P, R> {
    private val goTos = mutableMapOf<Boolean, Decision<out Any, P>>()
    private val terminations = mutableMapOf<Boolean, R>()

    /**
     * Declares what happens when the decision function returns [option]: continue at [next], or terminate with
     * [terminateWith]. Exactly one of the two must be non-null.
     * @throws IllegalArgumentException if both [next] and [terminateWith] are null
     */
    public fun on(option: Boolean, next: Decision<out Any, P>? = null, terminateWith: R? = null) {
        if (next != null) {
            goTos[option] = next
            return
        }
        if (terminateWith != null) {
            this.terminations[option] = terminateWith
            return
        }
        throw IllegalArgumentException("Must declare either goTo or finish")
    }

    internal fun build(decision: D): Node<P, R> = Node.BooleanNode(decision, goTos, terminations)

}

/** DSL receiver for [AlgorithmBuilder.enumNode]. */
@ExperimentalKlerkApi
public class EnumNodeBuilder<E : Enum<*>, D : Decision<E, P>, P, R> {
    private val goTos = mutableMapOf<E, Decision<out Any, P>>()
    private val terminations = mutableMapOf<E, R>()

    /**
     * Declares what happens when the decision function returns [option]: continue at [next], or terminate with
     * [terminateWith]. Exactly one of the two must be non-null.
     * @throws IllegalArgumentException if both [next] and [terminateWith] are null
     */
    public fun on(option: E, next: Decision<out Any, P>? = null, terminateWith: R? = null) {
        if (next != null) {
            goTos[option] = next
            return
        }
        if (terminateWith != null) {
            this.terminations[option] = terminateWith
            return
        }
        throw IllegalArgumentException("Must declare either goTo or finish")
    }

    internal fun build(decision: D): Node<P, R> = Node.EnumNode(decision, goTos, terminations)

}

/**
 * A named decision function that inspects the algorithm's parameters [P] and returns a value of type [T] to branch on.
 */
@ExperimentalKlerkApi
public interface Decision<T, P> {
    /** The decision's name, as shown in a rendered diagram. */
    public val name: String
    /** Computes the value to branch on. */
    public val function: (P) -> T
}

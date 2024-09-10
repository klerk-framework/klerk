package dev.klerkframework.klerk.misc

import kotlin.reflect.KFunction1

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

    public abstract fun configure(): AlgorithmBuilder<P, R>.() -> Unit

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
                val newLogs = logs.plus("${node.id}=${execution.functionResult} -> Result: ${execution.terminationResult}")
                Pair(execution.terminationResult, newLogs)
            }
        }
    }

}

public class AlgorithmBuilder<P, R>(private val name: String) {

    private var startNodeId: Decision<*, P>? = null
    private val nodes = mutableSetOf<Node<P, R>>()

    public fun start(decision: Decision<*, P>) {
        startNodeId = decision
    }

    public fun booleanNode(decision: Decision<Boolean, P>, init: BooleanNodeBuilder<Decision<Boolean, P>, P, R>.() -> Unit) {
        val builder = BooleanNodeBuilder<Decision<Boolean, P>, P, R>()
        builder.init()
        nodes.add(builder.build(decision))
    }

    public fun <E:Enum<*>> enumNode(decision: Decision<E, P>, init: EnumNodeBuilder<E, Decision<E, P>, P, R>.() -> Unit) {
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

internal sealed class Node<P, R> {
    abstract fun execute(params: P): NodeExecutionResult<P, R>

    abstract val id: String
    abstract val humanReadable: String
    abstract val goTos: Map<*, Decision<out Any, P>>
    abstract val terminations: Map<*, R>

    internal data class BooleanNode<P, R>(
        val decision: Decision<Boolean, P>,
        override val goTos: Map<Boolean, Decision<out Any, P>>,
        override val terminations: Map<Boolean, R>
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

    internal data class EnumNode<E:Enum<*>, P, R>(
        val decision: Decision<E, P>,
        override val goTos: Map<E, Decision<out Any, P>>,
        override val terminations: Map<E, R>
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

    internal data class FacadeNode<P, R:Any>(val f: KFunction1<P, R>) : Node<P, R>() {
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

internal sealed class NodeExecutionResult<P, R> {
    data class Termination<P, R>(val terminationResult: R, val functionResult: String) : NodeExecutionResult<P, R>()
    data class Next<P, R>(val decision: Decision<out Any, P>, val functionResult: String) : NodeExecutionResult<P, R>()
}

public class BooleanNodeBuilder<D : Decision<Boolean, P>, P, R> {
    private val goTos = mutableMapOf<Boolean, Decision<out Any, P>>()
    private val terminations = mutableMapOf<Boolean, R>()

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

    internal fun build(decision: D): Node<P, R> {
        return Node.BooleanNode(decision, goTos, terminations)
    }

}

public class EnumNodeBuilder<E:Enum<*>, D : Decision<E, P>, P, R> {
    private val goTos = mutableMapOf<E, Decision<out Any, P>>()
    private val terminations = mutableMapOf<E, R>()

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

    internal fun build(decision: D): Node<P, R> {
        return Node.EnumNode(decision, goTos, terminations)
    }

}

public  interface Decision<T, P> {
    public val name: String
    public val function: (P) -> T
}

package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.ExperimentalKlerkApi

/**
 * Renders [algo]'s nodes as a [Mermaid](https://mermaid.js.org/) `flowchart TD` definition, for documentation/tooling.
 */
@ExperimentalKlerkApi
public fun generateFlowChart(algo: FlowChartAlgorithm<*, *>): String = buildString {
    appendLine("flowchart TD")
    for (node in algo.nodes) {
        appendNode(node)
    }
}

@OptIn(ExperimentalKlerkApi::class)
private fun StringBuilder.appendNode(node: Node<*, *>) {
    val label = "${node.id}[${node.humanReadable}]"
    for ((condition, target) in node.goTos) {
        appendLine("$label --> |$condition| $target")
    }
    for ((condition, result) in node.terminations) {
        appendLine("$label --> |$condition| $result(Result: $result)")
    }
}

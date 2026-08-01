package dev.klerkframework.klerk.misc

import mu.KotlinLogging

/**
 * Process-wide registry (a hack, per the original author) that records which [FlowChartAlgorithm]s are wired into
 * which state-machine blocks, so [dev.klerkframework.klerk.misc.generateStateDiagram]-style tooling can render them.
 * Not something application code calls directly.
 */
public object AlgorithmDocumenter {

    private val logger = KotlinLogging.logger {}

    internal val documentation: MutableSet<AlgorithmDocumentation> = mutableSetOf()
    public var algorithms: Set<FlowChartAlgorithm<*, *>> = emptySet()

    /** Records that a block named [blockName] runs an algorithm, if [functionToString] looks like an [FlowChartAlgorithm.execute] reference; otherwise a no-op. */
    public fun notify(blockName: String, executableType: String, functionToString: String) {
        if (!functionToString.startsWith("fun ") || !functionToString.contains(".execute(")) {
            return
        }
        val algoQualifiedName = functionToString.split(".execute(").first().substring(4)
        documentation.add(AlgorithmDocumentation(blockName, executableType, algoQualifiedName))
    }

    public fun setKnownAlgorithms(algorithms: Set<FlowChartAlgorithm<*, *>>) {
        this.algorithms = algorithms
    }

    /** @throws NoSuchElementException if no registered algorithm's qualified class name equals [algorithmName] */
    public fun getAlgorithm(algorithmName: String): FlowChartAlgorithm<*, *> {
        return algorithms.single { it::class.qualifiedName == algorithmName }
    }


}

internal data class AlgorithmDocumentation(val blockName: String, val executableType: String, val qualifiedName: String)

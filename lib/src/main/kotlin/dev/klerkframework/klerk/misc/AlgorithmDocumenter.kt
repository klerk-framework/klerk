package dev.klerkframework.klerk.misc

import mu.KotlinLogging

// a hack!
internal object AlgorithmDocumenter {

    private val logger = KotlinLogging.logger {}

    internal val documentation: MutableSet<AlgorithmDocumentation> = mutableSetOf()
    internal var algorithms: Set<FlowChartAlgorithm<*, *>> = emptySet()

    fun notify(blockName: String, executableType: String, functionToString: String) {
        if (!functionToString.startsWith("fun ") || !functionToString.contains(".execute(")) {
            return
        }
        val algoQualifiedName = functionToString.split(".execute(").first().substring(4)
        documentation.add(AlgorithmDocumentation(blockName, executableType, algoQualifiedName))
    }

    fun setKnownAlgorithms(algorithms: Set<FlowChartAlgorithm<*, *>>) {
        this.algorithms = algorithms
    }

    fun getAlgorithm(algorithmName: String): FlowChartAlgorithm<*, *> {
        return algorithms.single { it::class.qualifiedName == algorithmName }
    }


}

internal data class AlgorithmDocumentation(val blockName: String, val executableType: String, val qualifiedName: String)

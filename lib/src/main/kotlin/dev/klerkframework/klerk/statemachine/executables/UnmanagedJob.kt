package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.Executable
import dev.klerkframework.klerk.statemachine.UnmanagedJob

internal class RunUnmanagedJob<T : Any, A, C : KlerkContext, V>(
    val action: (args: A) -> Unit,
    override val onCondition: ((args: A) -> Boolean)?,
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        ProcessingData(
            unmanagedJobs = listOf(
                UnmanagedJob(
                    f = { action(args) },
                    function = action,
                    description = "Action: ${extractNameFromFunction(action)}",
                ),
            ),
            log = listOf("Adding action '${extractNameFromFunction(action)}'"),
        )

}

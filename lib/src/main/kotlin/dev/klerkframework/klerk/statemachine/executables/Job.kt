package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.job.PendingJob
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.Executable

/**
 * Gives every declared job an id while the command is still being processed, so that the ids can be reported back in
 * [CommandResult.Success.jobs] and the rows can be written in the command's own transaction. If the command fails,
 * nothing is written and the ids are simply never used.
 */
private fun <C : KlerkContext, V> List<DeclaredJob<C, V>>.withIds(
    processingOptions: EventProcessingOptions,
): List<PendingJob<C, V>> = map { PendingJob(processingOptions.idProvider.getNextJobID(), it) }

internal class ScheduleJobs<T : Any, A, C : KlerkContext, V>(
    val f: (args: A) -> List<DeclaredJob<C, V>>,
    override val onCondition: ((args: A) -> Boolean)?
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = f.invoke(args).withIds(processingOptions),
        log = listOf("Adding jobs using '${extractNameFromFunction(f)}'")
    )

}

internal class ScheduleJob<T : Any, A, C : KlerkContext, V>(
    val f: (args: A) -> DeclaredJob<C, V>,
    override val onCondition: ((args: A) -> Boolean)?
) : Executable<T, A, C, V> {

    override fun <Primary : Any> process(
        args: A,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = listOf(f.invoke(args)).withIds(processingOptions),
        log = listOf("Adding job using '${extractNameFromFunction(f)}'")
    )

}

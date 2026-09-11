package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.job.PendingJob
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import dev.klerkframework.klerk.statemachine.VoidEventExecutable

/**
 * Gives every declared job an id while the command is still being processed, so that the ids can be reported back in
 * [CommandResult.Success.jobs] and the rows can be written in the command's own transaction. If the command fails,
 * nothing is written and the ids are simply never used.
 */
private fun <C : KlerkContext, V> List<DeclaredJob<C, V>>.withIds(
    processingOptions: EventProcessingOptions,
): List<PendingJob<C, V>> = map { PendingJob(processingOptions.idProvider.getNextJobID(), it) }

internal class VoidEventJobs<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForVoidEvent<T, P, C, V>) -> List<DeclaredJob<C, V>>,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = f.invoke(args).withIds(processingOptions),
        log = listOf("Adding jobs using '${extractNameFromFunction(f)}'")
    )

}

internal class VoidEventJob<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForVoidEvent<T, P, C, V>) -> DeclaredJob<C, V>,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = listOf(f.invoke(args)).withIds(processingOptions),
        log = listOf("Adding job using '${extractNameFromFunction(f)}'")
    )

}

internal class InstanceNonEventJobs<T : Any, C : KlerkContext, V>(
    val f: (args: ArgForInstanceNonEvent<T, C, V>) -> List<DeclaredJob<C, V>>,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = f.invoke(args).withIds(processingOptions),
        log = listOf("Adding jobs using '${extractNameFromFunction(f)}'")
    )

}

internal class InstanceNonEventJob<T : Any, C : KlerkContext, V>(
    val f: (args: ArgForInstanceNonEvent<T, C, V>) -> DeclaredJob<C, V>,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = listOf(f.invoke(args)).withIds(processingOptions),
        log = listOf("Adding job using '${extractNameFromFunction(f)}'")
    )

}

internal class InstanceEventJobs<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForInstanceEvent<T, P, C, V>) -> List<DeclaredJob<C, V>>,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = f.invoke(args).withIds(processingOptions),
        log = listOf("Adding jobs using '${extractNameFromFunction(f)}'")
    )

}

internal class InstanceEventJob<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForInstanceEvent<T, P, C, V>) -> DeclaredJob<C, V>,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelViews<T, C>,
        specification: Specification<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> = ProcessingData(
        newJobs = listOf(f.invoke(args)).withIds(processingOptions),
        log = listOf("Adding job using '${extractNameFromFunction(f)}'")
    )

}

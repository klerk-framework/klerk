package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.*
import dev.klerkframework.klerk.statemachine.VoidEventExecutable

internal class VoidEventJob<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForVoidEvent<T, P, C, V>) -> List<Job<C, V>>,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        return ProcessingData(newJobs = f.invoke(args),
            log = listOf("Adding jobs using '${extractNameFromFunction(f, false)}'")
        )
    }

}

internal class InstanceNonEventJob<T : Any, C : KlerkContext, V>(
    val f: (args: ArgForInstanceNonEvent<T, C, V>) -> List<Job<C, V>>,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        return ProcessingData(newJobs = f.invoke(args),
            log = listOf("Adding jobs using '${extractNameFromFunction(f, false)}'")
        )
    }

}

internal class InstanceEventJob<T : Any, P, C : KlerkContext, V>(
    val f: (args: ArgForInstanceEvent<T, P, C, V>) -> List<Job<C, V>>,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> {
        return ProcessingData(newJobs = f.invoke(args),
            log = listOf("Adding jobs using '${extractNameFromFunction(f, false)}'")
        )
    }

}

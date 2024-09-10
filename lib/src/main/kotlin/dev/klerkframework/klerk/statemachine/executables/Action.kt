package dev.klerkframework.klerk.statemachine.executables

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.GeneralAction
import dev.klerkframework.klerk.statemachine.InstanceEventExecutable
import dev.klerkframework.klerk.statemachine.InstanceNonEventExecutable
import dev.klerkframework.klerk.statemachine.VoidEventExecutable

internal class VoidEventAction<T : Any, P, C : KlerkContext, V>(
    val action: (args: ArgForVoidEvent<T, P, C, V>) -> Unit,
    override val onCondition: ((args: ArgForVoidEvent<T, P, C, V>) -> Boolean)?
) : VoidEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForVoidEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        ProcessingData(
            actions = listOf(
                GeneralAction(
                    f = { action(args) },
                    description = "Action: ${extractNameFromFunction(action)}"
                )
            ),
            log = listOf("Adding action '${extractNameFromFunction(action, false)}'")
        )

}

internal class InstanceNonEventAction<T : Any, C : KlerkContext, V>(
    val action: (args: ArgForInstanceNonEvent<T, C, V>) -> Unit,
    override val onCondition: ((args: ArgForInstanceNonEvent<T, C, V>) -> Boolean)?
) : InstanceNonEventExecutable<T, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceNonEvent<T, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        ProcessingData(
            actions = listOf(
                GeneralAction(
                    f = { action(args) },
                    description = "Action: ${extractNameFromFunction(action)}"
                )
            ),
            log = listOf("Adding action '${extractNameFromFunction(action, false)}'")
        )

}

internal class InstanceEventAction<T : Any, P, C : KlerkContext, V>(
    val action: (args: ArgForInstanceEvent<T, P, C, V>) -> Unit,
    override val onCondition: ((args: ArgForInstanceEvent<T, P, C, V>) -> Boolean)?
) : InstanceEventExecutable<T, P, C, V> {

    override fun <Primary : Any> process(
        args: ArgForInstanceEvent<T, P, C, V>,
        processingOptions: EventProcessingOptions,
        view: ModelCollections<T, C>,
        config: Config<C, V>,
        processingDataSoFar: ProcessingData<Primary, C, V>,
    ): ProcessingData<Primary, C, V> =
        ProcessingData(
            actions = listOf(
                GeneralAction(
                    f = { action(args) },
                    description = "Action: ${extractNameFromFunction(action)}"
                )
            ),
            log = listOf("Adding action '${extractNameFromFunction(action, false)}'")
        )

}
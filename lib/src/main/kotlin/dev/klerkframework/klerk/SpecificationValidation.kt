package dev.klerkframework.klerk

import dev.klerkframework.klerk.misc.requireNamedRule
import dev.klerkframework.klerk.attacheddata.instantiateDeclaration
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.misc.*
import dev.klerkframework.klerk.statemachine.Block
import dev.klerkframework.klerk.statemachine.Executable
import dev.klerkframework.klerk.statemachine.InstanceState
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.klerk.statemachine.executables.Transition
import dev.klerkframework.klerk.statemachine.executables.TransitionWhen
import dev.klerkframework.klerk.storage.AttachedBlobStore
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.time.Duration

private fun <C : KlerkContext, V> Specification<C, V>.modelsAndParametersMustBeStorable() {
    for (managedModel in managedModels) {
        KlerkJson.requireStorable(managedModel.kClass)
    }
    managedModels
        .flatMap { it.stateMachine.eventReferences }
        .mapNotNull { parametersSchema(it)?.kClass }
        .forEach { KlerkJson.requireStorable(it) }
}

private fun <C : KlerkContext, V> Specification<C, V>.rulesMustBeNamed() {
    managedModels.flatMap { it.stateMachine.eventReferences }.forEach { reference ->
        for (rule in rulesOf(reference).allRules) {
            requireNamedRule(rule, "A validation rule of the event $reference")
        }
    }
    for ((state, decision) in transitionDecisions()) {
        requireNamedRule(decision, "A transitionWhen decision in the state $state")
    }
    with(authorization) {
        listOf(
            readModelPositiveRules, readModelNegativeRules, readPropertyPositiveRules, readPropertyNegativeRules,
            eventPositiveRules, eventNegativeRules, eventLogPositiveRules, eventLogNegativeRules,
            attachedDataReadPositiveRules, attachedDataReadNegativeRules, attachedDataWritePositiveRules,
            attachedDataWriteNegativeRules, jobPositiveRules, jobNegativeRules,
        ).flatten().forEach { requireNamedRule(it, "An authorization rule") }
    }
}

internal fun <C : KlerkContext, V> Specification<C, V>.validateMigrations() {
    for (step in migrationSteps) {
        if (step.migratesToVersion <= 1) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidMigration,
                "A migration step must migrate to version 2 or higher, but '${step.description}' migrates to " +
                    "${step.migratesToVersion}",
            )
        }
        if (step.description.length >= 200) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidMigration,
                "The description of a migration step must be shorter than 200 characters",
            )
        }
    }
    migrationSteps.fold(1) { acc, migrationStep ->
        if (acc + 1 != migrationStep.migratesToVersion) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidMigration,
                "Missing migration to version ${acc + 1}",
            )
        }
        migrationStep.migratesToVersion
    }
}

internal fun <C : KlerkContext, V> Specification<C, V>.validate(settings: KlerkSettings) {
    modelsAndParametersMustBeStorable()
    rulesMustBeNamed()
    parametersWithReferencesMustHaveValidReferences()
    stateMachinesMustBeComplete()
    viewStateFiltersMustUseTheModelsStateEnum()
    allEventsMustBeDeclared()
    noTransitionToCurrentState()
    checkContextProviderExistIfConfigContainsTimeTriggers()
    schedulerJobsMustHaveAJobContextProvider()
    attachedBlobStoreMustMatchDeclarations(settings.attachedBlobStore)
    require(eraseEventLogAfterModelDeletion == null || eraseEventLogAfterModelDeletion == Duration.ZERO) {
        "eraseEventLogAfterModelDeletion can only be null or zero"
    }
    blobContainersMustDeclareAPreAttachStep()
    stringsMustBeDeclaredInAContainer()
    for (plugin in plugins) {
        require(" " !in plugin.name) { "Plugin name cannot contain space: ${plugin.name}" }
    }
}

/**
 * A `filterStates` on a view takes enum values, but nothing stops them being some other model's states — which
 * would silently match nothing.
 */
private fun <C : KlerkContext, V> Specification<C, V>.viewStateFiltersMustUseTheModelsStateEnum() {
    for (registered in registeredViews) {
        val expected = getStateMachine(registered.modelClass).statesEnumClass ?: continue
        val foreign = registered.view.allFilteredStates().filterNot { it.declaringClass() == expected }
        if (foreign.isNotEmpty()) {
            throw IllegalConfigurationException(
                KlerkErrorCode.InvalidView,
                "The view '${registered.view.registeredId}' filters ${registered.modelClass.simpleName} on " +
                    foreign.joinToString(", ") { "${it.declaringClass().simpleName}.${it.name}" } +
                    ", which is not a state of ${expected.simpleName}",
            )
        }
    }
}

/**
 * A job running as [dev.klerkframework.klerk.job.JobAgent.Scheduler] needs a context for an actor other than the
 * system, and Klerk cannot construct one for an application-defined context type on its own.
 */
private fun <C : KlerkContext, V> Specification<C, V>.schedulerJobsMustHaveAJobContextProvider() {
    if (jobContextProvider != null) {
        return
    }
    val needsOne = jobs.allTypes.values.filter { it.agent == JobAgent.Scheduler }
    if (needsOne.isEmpty()) {
        return
    }
    throw IllegalConfigurationException(
        KlerkErrorCode.MissingJobContextProvider,
        "The job type(s) ${needsOne.joinToString(", ") { "'${it.name.value}'" }} run as JobAgent.Scheduler, " +
                "which means their commands are applied as the actor that scheduled them. Klerk therefore needs " +
                "'jobContextProvider(...)' in the specification to build a context for that actor.",
    )
}

/**
 * Where blob bytes are kept decides what a database backup contains, and Klerk cannot guess it. An application
 * that declares a blob anywhere must say; one that declares none needs no store at all.
 */
private fun <C : KlerkContext, V> Specification<C, V>.attachedBlobStoreMustMatchDeclarations(
    attachedBlobStore: AttachedBlobStore?,
) {
    val bare = declaredAttachedDataProperties(AttachedDataDeclaration.BareBlobID)
    if (bare.isNotEmpty()) {
        throw IllegalConfigurationException(
            KlerkErrorCode.BlobMustBeDeclaredInAContainer,
            """
            |${bare.sorted().joinToString(", ")} is an AttachedBlobID. Declare an AttachedBlobContainer subclass
            |for it instead, the way every other property has a DataContainer:
            |
            |    class Portrait(id: AttachedBlobID) : AttachedBlobContainer(id) {
            |        override val accept = setOf("image/png", "image/jpeg")
            |        override val maxSize = 5_000_000L
            |        override val preAttachSteps = listOf(::stripExif)
            |    }
            |
            |That is what says which files are acceptable, how large they may be, and whether they may be read by
            |anyone — and it is checked when a command attaches the file, whichever caller sent it.
            """.trimMargin(),
        )
    }

    val declarations = declaredAttachedDataProperties(AttachedDataDeclaration.BlobContainerDeclaration)
    if (declarations.isEmpty()) {
        return
    }
    val where = declarations.sorted().joinToString(", ")
    if (attachedBlobStore == null) {
        throw IllegalConfigurationException(
            KlerkErrorCode.MissingAttachedBlobStore,
            "$where holds a blob, so 'attachedBlobStore' is required in KlerkSettings. Choose " +
                    "AttachedBlobStore.Database to keep the bytes in the database, or FileBlobStore(path) to " +
                    "keep them on disk. Pick before you have data: Klerk does not move blobs between stores.",
        )
    }
}

private enum class AttachedDataDeclaration { BareBlobID, BareStringID, BlobContainerDeclaration }

/** Descriptions of every place attached data of [kind] is declared: model properties and event parameters. */
private fun <C : KlerkContext, V> Specification<C, V>.declaredAttachedDataProperties(
    kind: AttachedDataDeclaration,
): List<String> {
    val found = mutableListOf<String>()
    for (managed in managedModels) {
        for (property in attachedDataPropertyNames(managed.kClass, kind)) {
            found.add("${managed.kClass.simpleName}.$property")
        }
        managed.stateMachine.states.flatMap { it.getEvents() }.forEach { event ->
            val parameters = when (event) {
                is InstanceEventWithParameters<*, *> -> event.parametersClass
                is VoidEventWithParameters<*, *> -> event.parametersClass
                else -> null
            }
            parameters?.let { kClass ->
                for (property in attachedDataPropertyNames(kClass, kind)) {
                    found.add("${event.id.eventName}.$property")
                }
            }
        }
    }
    return found.distinct()
}

private fun <C : KlerkContext, V> Specification<C, V>.attachedDataPropertyNames(
    kClass: KClass<*>,
    kind: AttachedDataDeclaration,
): List<String> =
    ObjectSchema.of(kClass).leafFields().filter { leaf ->
        when (kind) {
            AttachedDataDeclaration.BareBlobID -> leaf.shape == Shape.BlobID
            AttachedDataDeclaration.BareStringID -> leaf.shape == Shape.StringID
            AttachedDataDeclaration.BlobContainerDeclaration ->
                (leaf.shape as? Shape.Container)?.kind == ContainerKind.AttachedBlob
        }
    }.map { it.path }

/**
 * A string, like a blob, has to be declared in a container — the way every other property has a DataContainer —
 * rather than left as a bare id with nothing saying what it may be or who may read it.
 */
private fun <C : KlerkContext, V> Specification<C, V>.stringsMustBeDeclaredInAContainer() {
    val bare = declaredAttachedDataProperties(AttachedDataDeclaration.BareStringID)
    if (bare.isEmpty()) {
        return
    }
    throw IllegalConfigurationException(
        KlerkErrorCode.StringMustBeDeclaredInAContainer,
        """
        |${bare.sorted().joinToString(", ")} is an AttachedStringID. Declare an AttachedStringContainer subclass
        |for it instead, the way every other property has a DataContainer:
        |
        |    class BookNotes(id: AttachedStringID) : AttachedStringContainer(id) {
        |        override val accept = setOf("text/plain")
        |        override val maxSize = 10_000L
        |    }
        |
        |That is what says what is acceptable, how large it may be, and whether it may be read by anyone — and it
        |is checked when a command attaches the value, whichever caller sent it.
        """.trimMargin(),
    )
}

/**
 * An uploaded file has to be looked at before it is kept, so a [AttachedBlobContainer] must declare at least one
 * preAttachStep — [dev.klerkframework.klerk.datatypes.noPreAttachProcessing] if it truly wants none. Checked here,
 * since a property that is only reached from an upload page would otherwise not complain until someone uploads a
 * file.
 */
private fun <C : KlerkContext, V> Specification<C, V>.blobContainersMustDeclareAPreAttachStep() {
    for ((kClass, where) in declaredAttachedBlobContainers()) {
        val container = try {
            instantiateDeclaration(kClass, AttachedBlobID(0))
        } catch (e: IllegalArgumentException) {
            throw IllegalConfigurationException(
                KlerkErrorCode.BlobMustBeDeclaredInAContainer,
                "$where: ${e.message}",
            )
        }
        try {
            container.stepNames
        } catch (e: IllegalArgumentException) {
            throw IllegalConfigurationException(KlerkErrorCode.MissingPreAttachStep, "$where: ${e.message}")
        }
    }
}

/** Every [AttachedBlobContainer] class a model property or event parameter uses, and where it was found. */
internal fun <C : KlerkContext, V> Specification<C, V>.declaredAttachedBlobContainers():
    Map<KClass<out AttachedBlobContainer>, String> {
    val found = mutableMapOf<KClass<out AttachedBlobContainer>, String>()

    fun collect(kClass: KClass<*>, describe: (String) -> String) {
        for (leaf in ObjectSchema.of(kClass).leafFields()) {
            val container = leaf.shape as? Shape.Container ?: continue
            if (container.kind == ContainerKind.AttachedBlob) {
                @Suppress("UNCHECKED_CAST")
                found.putIfAbsent(container.kClass as KClass<out AttachedBlobContainer>, describe(leaf.path))
            }
        }
    }

    for (managed in managedModels) {
        collect(managed.kClass) { "${managed.kClass.simpleName}.$it" }
        managed.stateMachine.states.flatMap { it.getEvents() }.forEach { event ->
            val parameters = when (event) {
                is InstanceEventWithParameters<*, *> -> event.parametersClass
                is VoidEventWithParameters<*, *> -> event.parametersClass
                else -> null
            }
            parameters?.let { kClass -> collect(kClass) { "${event.id.eventName}.$it" } }
        }
    }
    return found
}

private fun <C : KlerkContext, V> Specification<C, V>.checkContextProviderExistIfConfigContainsTimeTriggers() {
    // TODO("Not yet implemented")
}

private fun Executable<*, *, *, *>.transitionsTo(state: StateID): Boolean =
    this is Transition<*, *, *, *, *> && targetState.name == state.stateName ||
        this is TransitionWhen<*, *, *, *, *> && branches.values.any { it.name == state.stateName }

/**
 * Checks that all transitions lead to another state
 */
private fun <C : KlerkContext, V> Specification<C, V>.noTransitionToCurrentState() {
    fun checkBlock(block: Block<*, *, *, *>, state: StateID) {
        val problem = when (block) {
            is Block.InstanceEventBlock<*, *, *, *, *> -> block.executables.any { it.transitionsTo(state) }

            is Block.InstanceLifecycleBlock -> block.executables.any { it.transitionsTo(state) }

            is Block.VoidEventBlock<*, *, *, *, *> -> false         // there can be no transitions in void-states
            is Block.VoidLifecycleBlock -> false                     // there can be no transitions in void-states
        }
        check(!problem) { "State ${state.withoutPrefix()} has a transition to itself" }
    }
    for (managed in managedModels) {
        for (state in managed.stateMachine.states) {
            checkBlock(state.enterBlock, state.id)
            checkBlock(state.exitBlock, state.id)
            when (state) {
                is VoidState -> for ((_, block) in state.onEventBlocks) {
                    checkBlock(block, state.id)
                }
                is InstanceState -> for ((_, block) in state.onEventBlocks) {
                    checkBlock(block, state.id)
                }
            }
        }
    }
}

private fun <C : KlerkContext, V> Specification<C, V>.stateMachinesMustBeComplete() {
    for (managedModel in managedModels) {
        managedModel.stateMachine.validateStatesAreComplete()
    }
}

/** Every decision function passed to a `transitionWhen { on(...) }`, with the state it was declared in. */
private fun <C : KlerkContext, V> Specification<C, V>.transitionDecisions(): List<Pair<StateID, Function<*>>> {
    fun decisionsIn(block: Block<*, *, *, *>): List<Function<*>> = when (block) {
        is Block.InstanceEventBlock<*, *, *, *, *> -> block.executables
            .filterIsInstance<TransitionWhen<*, *, *, *, *>>().flatMap { it.branches.keys }

        is Block.InstanceLifecycleBlock -> block.executables
            .filterIsInstance<TransitionWhen<*, *, *, *, *>>().flatMap { it.branches.keys }

        else -> emptyList()
    }
    return managedModels.flatMap { managed ->
        managed.stateMachine.states.flatMap { state ->
            val blocks = listOf(state.enterBlock, state.exitBlock) + when (state) {
                is VoidState -> state.onEventBlocks.map { it.second }
                is InstanceState -> state.onEventBlocks.map { it.second }
            }
            blocks.flatMap { decisionsIn(it) }.map { state.id to it }
        }
    }
}

/**
 * Makes sure that events have been declared before used in `onEvent()`: we want all validation rules to be
 * explicit, and an undeclared event does not make it visible that it has none.
 */
private fun <C : KlerkContext, V> Specification<C, V>.allEventsMustBeDeclared() {
    managedModels.map { it.stateMachine }.forEach { sm ->
        sm.states.flatMap { state ->
            when (state) {
                is VoidState -> state.onEventBlocks.map { it.first }
                is InstanceState -> state.onEventBlocks.map { it.first }
            }
        }
            .forEach {
                if (it !in sm.declaredEvents) {
                    throw IllegalConfigurationException(
                        KlerkErrorCode.EventNotDeclared,
                        "The event '${it.id}' must be declared before used in state",
                    )
                }
            }
    }
}

private fun <C : KlerkContext, V> Specification<C, V>.parametersWithReferencesMustHaveValidReferences() {
    for (sm in managedModels.map { it.stateMachine }) {
        val events = sm.states.flatMap { state ->
            when (state) {
                is VoidState -> state.onEventBlocks.map { it.first }
                is InstanceState -> state.onEventBlocks.map { it.first }
            }
        }
        for (event in events) {
            val parametersClass = when (event) {
                is InstanceEventWithParameters -> event.parametersClass
                is VoidEventWithParameters -> event.parametersClass
                else -> continue
            }
            val rules = sm.rulesFor(event.id)
            checkRefParam(parametersClass, rules.validRefs.keys, rules.validEnums.keys, event)
        }
    }
}

/**
 * Every ModelID in the parameters, also in collections and nested classes, must have `validReferences`, and
 * `validReferences`/`validEnums` must only be declared for properties that are in the parameters.
 */
private fun <C : KlerkContext, V> Specification<C, V>.checkRefParam(
    parametersClass: KClass<*>,
    validRefs: Set<PropertyKey>,
    validEnums: Set<PropertyKey>,
    event: Event<*, *>,
) {
    val leaves = ObjectSchema.of(parametersClass).leafFields()
    val references = leaves.filter { it.shape == Shape.Reference }
    references.firstOrNull { it.field.key !in validRefs }?.let {
        throw IllegalConfigurationException(
            KlerkErrorCode.MissingValidReferences, """
            '${it.path}' in '${parametersClass.simpleName}' for '$event' is a ModelID, but there is no
            'validReferences' declared for it in the state machine. Declare which view the ids must be in, e.g.:
            event(${event.name}) {
                validReferences(${it.field.key}, views.the-view-of-the-referenced-model.all)
            }
            Pass null instead of a view to accept any id.
            """.trimIndent(),
        )
    }
    val enums = leaves.filter { (it.shape as? Shape.Container)?.kind == ContainerKind.Enum }
    val unknown = (validRefs - references.map { it.field.key }.toSet()).map { "validReferences($it, ...)" } +
            (validEnums - enums.map { it.field.key }.toSet()).map { "validEnums($it, ...)" }
    if (unknown.isNotEmpty()) {
        throw IllegalConfigurationException(
            KlerkErrorCode.ValidationRuleForUnknownProperty,
            "${unknown.joinToString(", ")} is declared for '$event', but the property is not a matching property " +
                    "of ${parametersClass.simpleName} or a class nested in it",
        )
    }
}

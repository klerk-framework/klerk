package dev.klerkframework.klerk

import dev.klerkframework.klerk.misc.requireNamedRule
import dev.klerkframework.klerk.attacheddata.ContentTypeDetector
import dev.klerkframework.klerk.attacheddata.DefaultContentTypeDetector
import dev.klerkframework.klerk.attacheddata.instantiateDeclaration
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.job.*
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.misc.*
import dev.klerkframework.klerk.statemachine.DeclaredEventRules
import dev.klerkframework.klerk.statemachine.Block
import dev.klerkframework.klerk.statemachine.InstanceState
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceEventTransitionWhen
import dev.klerkframework.klerk.statemachine.executables.InstanceLifecycleTransition
import dev.klerkframework.klerk.statemachine.executables.InstanceLifecycleTransitionWhen
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.ModelCacheSettings
import dev.klerkframework.klerk.storage.Persistence
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KotlinLogging
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal val logger = KotlinLogging.logger {}

/**
 * What the application *is*: its models and state machines, the events they accept, who is allowed to do what, the
 * jobs it runs and how its data evolves. Built via [SpecificationBuilder.build] (typically through
 * `SpecificationBuilder(views).build { ... }`), then passed to [Klerk.Companion.create] together with a
 * [KlerkSettings].
 *
 * Nothing here varies between deployments: two instances of the same application share a specification and differ
 * only in their settings. Where the data is stored, what the clock is and how hard the job dispatcher works are
 * therefore in [KlerkSettings], not here.
 *
 * Most of the functions on this class are introspection helpers used internally by the framework (validation,
 * the JSON serializer, `klerk-web`/`klerk-graphql`) to look up state machines, events and views by reference.
 * Application code normally doesn't need to call them directly.
 */
public data class Specification<C : KlerkContext, V>(
    public val views: V,
    public val authorization: AuthorizationConfig<C, V>,
    val managedModels: Set<ManagedModel<*, *, C, V>>,
    val migrationSteps: SortedSet<MigrationStep>,
    val plugins: List<KlerkPlugin<C, V>> = listOf(),
    val systemContextProvider: (() -> C),
    /** The job types and cron schedules this application declares, built by `SpecificationBuilder.jobs { ... }`. */
    val jobs: JobsSpecification<C, V> = JobsSpecification.empty(),
    /**
     * Builds the context a job step runs under. Optional; when absent, [systemContextProvider] is used.
     *
     * Configure it if you use [dev.klerkframework.klerk.job.JobAgent.Scheduler] (which needs a context for an actor
     * other than the system), or if you want a job step's `context.time` to come from [KlerkSettings.clock] — which
     * is what makes a job's own view of time controllable in tests.
     */
    val jobContextProvider: ((JobContextRequest) -> C)? = null,
    /**
     * Whether the event log of a model is erased when the model is deleted. A requirement about the application (a
     * privacy promise, typically), which is why it lives here and not in [KlerkSettings].
     *
     * Only `null` (never erase, the default) and [Duration.ZERO] (erase immediately on model deletion) are
     * currently supported; any other value is rejected on startup.
     */
    val eraseEventLogAfterModelDeletion: Duration? = null,
) {
    internal fun initialize(settings: KlerkSettings): Unit {
        validate(settings)
        validateMigrations()
        managedModels.map { it.stateMachine.onKlerkStart(this) }
    }

    private fun modelsAndParametersMustBeStorable() {
        managedModels.forEach { KlerkJson.requireStorable(it.kClass) }
        managedModels
            .flatMap { it.stateMachine.getAllEvents() }
            .mapNotNull { parametersSchema(it)?.kClass }
            .forEach { KlerkJson.requireStorable(it) }
    }

    private fun rulesMustBeNamed() {
        managedModels.flatMap { it.stateMachine.getAllEvents() }.forEach { reference ->
            rulesOf(reference).allRules.forEach {
                requireNamedRule(it, "A validation rule of the event $reference")
            }
        }
        transitionDecisions().forEach { (state, decision) ->
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

    private fun validateMigrations() {
        migrationSteps.forEach {
            if (it.migratesToVersion <= 1) {
                throw IllegalConfigurationException(
                    KlerkErrorCode.InvalidMigration,
                    "A migration step must migrate to version 2 or higher, but '${it.description}' migrates to ${it.migratesToVersion}"
                )
            }
            if (it.description.length >= 200) {
                throw IllegalConfigurationException(
                    KlerkErrorCode.InvalidMigration,
                    "The description of a migration step must be shorter than 200 characters"
                )
            }
        }
        migrationSteps.fold(1) { acc, migrationStep ->
            if (acc + 1 != migrationStep.migratesToVersion) {
                throw IllegalConfigurationException(
                    KlerkErrorCode.InvalidMigration,
                    "Missing migration to version ${acc + 1}"
                )
            }
            migrationStep.migratesToVersion
        }
    }

    private fun validate(settings: KlerkSettings) {
        modelsAndParametersMustBeStorable()
        rulesMustBeNamed()
        parametersWithReferencesMustHaveValidReferences()
        stateMachinesMustBeComplete()
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
        plugins.forEach { require(!it.name.contains(" ")) { "Plugin name cannot contain space: ${it.name}" } }
    }

    /**
     * A job running as [dev.klerkframework.klerk.job.JobAgent.Scheduler] needs a context for an actor other than the
     * system, and Klerk cannot construct one for an application-defined context type on its own.
     */
    private fun schedulerJobsMustHaveAJobContextProvider() {
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
                    "'jobContextProvider(...)' in the specification to build a context for that actor."
        )
    }

    /**
     * Where blob bytes are kept decides what a database backup contains, and Klerk cannot guess it. An application
     * that declares a blob anywhere must say; one that declares none needs no store at all.
     */
    private fun attachedBlobStoreMustMatchDeclarations(attachedBlobStore: AttachedBlobStore?) {
        val bare = declaredAttachedDataProperties(AttachedDataDeclaration.BareBlobId)
        if (bare.isNotEmpty()) {
            throw IllegalConfigurationException(
                KlerkErrorCode.BlobMustBeDeclaredInAContainer,
                "${
                    bare.sorted().joinToString(", ")
                } is an AttachedBlobID. Declare an AttachedBlobContainer subclass for it " +
                        "instead, the way every other property has a DataContainer:\n\n" +
                        "    class Portrait(id: AttachedBlobID) : AttachedBlobContainer(id) {\n" +
                        "        override val accept = setOf(\"image/png\", \"image/jpeg\")\n" +
                        "        override val maxSize = 5_000_000L\n" +
                        "        override val preAttachSteps = listOf(::stripExif)\n" +
                        "    }\n\n" +
                        "That is what says which files are acceptable, how large they may be, and whether they may " +
                        "be read by anyone — and it is checked when a command attaches the file, whichever caller " +
                        "sent it."
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
                        "keep them on disk. Pick before you have data: Klerk does not move blobs between stores."
            )
        }
    }

    private enum class AttachedDataDeclaration { BareBlobId, BareStringId, BlobContainerDeclaration }

    /** Descriptions of every place attached data of [kind] is declared: model properties and event parameters. */
    private fun declaredAttachedDataProperties(kind: AttachedDataDeclaration): List<String> {
        val found = mutableListOf<String>()
        managedModels.forEach { managed ->
            attachedDataPropertyNames(managed.kClass, kind).forEach { found.add("${managed.kClass.simpleName}.$it") }
            managed.stateMachine.mutableStates.flatMap { it.getEvents() }.forEach { event ->
                val parameters = when (event) {
                    is InstanceEventWithParameters<*, *> -> event.parametersClass
                    is VoidEventWithParameters<*, *> -> event.parametersClass
                    else -> null
                }
                parameters?.let { kClass ->
                    attachedDataPropertyNames(kClass, kind).forEach { found.add("${event.id.eventName}.$it") }
                }
            }
        }
        return found.distinct()
    }

    private fun attachedDataPropertyNames(kClass: KClass<*>, kind: AttachedDataDeclaration): List<String> =
        ObjectSchema.of(kClass).leafFields().filter { leaf ->
            when (kind) {
                AttachedDataDeclaration.BareBlobId -> leaf.shape == Shape.BlobId
                AttachedDataDeclaration.BareStringId -> leaf.shape == Shape.StringId
                AttachedDataDeclaration.BlobContainerDeclaration ->
                    (leaf.shape as? Shape.Container)?.kind == ContainerKind.AttachedBlob
            }
        }.map { it.path }

    /**
     * A string, like a blob, has to be declared in a container — the way every other property has a DataContainer —
     * rather than left as a bare id with nothing saying what it may be or who may read it.
     */
    private fun stringsMustBeDeclaredInAContainer() {
        val bare = declaredAttachedDataProperties(AttachedDataDeclaration.BareStringId)
        if (bare.isEmpty()) {
            return
        }
        throw IllegalConfigurationException(
            KlerkErrorCode.StringMustBeDeclaredInAContainer,
            "${bare.sorted().joinToString(", ")} is an AttachedStringID. Declare an AttachedStringContainer " +
                    "subclass for it instead, the way every other property has a DataContainer:\n\n" +
                    "    class BookNotes(id: AttachedStringID) : AttachedStringContainer(id) {\n" +
                    "        override val accept = setOf(\"text/plain\")\n" +
                    "        override val maxSize = 10_000L\n" +
                    "    }\n\n" +
                    "That is what says what is acceptable, how large it may be, and whether it may be read by " +
                    "anyone — and it is checked when a command attaches the value, whichever caller sent it."
        )
    }

    /**
     * An uploaded file has to be looked at before it is kept, so a [AttachedBlobContainer] must declare at least one
     * preAttachStep — [dev.klerkframework.klerk.datatypes.noPreAttachProcessing] if it truly wants none. Checked here,
     * since a property that is only reached from an upload page would otherwise not complain until someone uploads a
     * file.
     */
    private fun blobContainersMustDeclareAPreAttachStep() {
        declaredAttachedBlobContainers().forEach { (kClass, where) ->
            val container = try {
                instantiateDeclaration(kClass, AttachedBlobID(0))
            } catch (e: IllegalArgumentException) {
                throw IllegalConfigurationException(
                    KlerkErrorCode.BlobMustBeDeclaredInAContainer,
                    "$where: ${e.message}"
                )
            }
            try {
                container.stepNames
            } catch (e: IllegalArgumentException) {
                throw IllegalConfigurationException(KlerkErrorCode.MissingPreAttachStep, "$where: ${e.message}")
            }
        }
    }

    /** Every [AttachedBlobContainer] class a model property or event parameter uses, by qualified name. */
    internal val attachedBlobContainers: Map<String, KClass<out AttachedBlobContainer>> by lazy {
        declaredAttachedBlobContainers().keys.mapNotNull { kClass -> kClass.qualifiedName?.let { it to kClass } }.toMap()
    }

    /** Every [AttachedBlobContainer] class a model property or event parameter uses, and where it was found. */
    private fun declaredAttachedBlobContainers(): Map<KClass<out AttachedBlobContainer>, String> {
        val found = mutableMapOf<KClass<out AttachedBlobContainer>, String>()

        fun collect(kClass: KClass<*>, describe: (String) -> String) {
            ObjectSchema.of(kClass).leafFields().forEach { leaf ->
                val container = leaf.shape as? Shape.Container ?: return@forEach
                if (container.kind == ContainerKind.AttachedBlob) {
                    @Suppress("UNCHECKED_CAST")
                    found.putIfAbsent(container.kClass as KClass<out AttachedBlobContainer>, describe(leaf.path))
                }
            }
        }

        managedModels.forEach { managed ->
            collect(managed.kClass) { "${managed.kClass.simpleName}.$it" }
            managed.stateMachine.mutableStates.flatMap { it.getEvents() }.forEach { event ->
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

    private fun checkContextProviderExistIfConfigContainsTimeTriggers() {
        // TODO("Not yet implemented")
    }

    /**
     * Checks that all transitions lead to another state
     */
    private fun noTransitionToCurrentState() {
        fun checkBlock(block: Block<*, *, *, *>, state: StateId) {
            val problem = when (block) {
                is Block.InstanceEventBlock<*, *, *, *, *> -> block.executables.any {
                    it is InstanceEventTransition<*, *, *, *, *> && it.targetState.name == state.stateName ||
                            it is InstanceEventTransitionWhen<*, *, *, *, *> && it.branches.any { branch -> branch.value.name == state.stateName }
                }

                is Block.InstanceLifecycleBlock -> block.executables.any {
                    it is InstanceLifecycleTransition<*, *, *, *> && it.targetState.name == state.stateName ||
                            it is InstanceLifecycleTransitionWhen<*, *, *, *> && it.branches.any { branch -> branch.value.name == state.stateName }
                }

                is Block.VoidEventBlock<*, *, *, *, *> -> false         // there can be no transitions in void-states
                is Block.VoidLifecycleBlock -> false                     // there can be no transitions in void-states
            }
            check(!problem) { "State ${state.withoutPrefix()} has a transition to itself" }
        }
        managedModels.forEach { managed ->
            managed.stateMachine.mutableStates.forEach { state ->
                checkBlock(state.enterBlock, state.id)
                checkBlock(state.exitBlock, state.id)
                when (state) {
                    is VoidState -> state.onEventBlocks.forEach { checkBlock(it.second, state.id) }
                    is InstanceState -> state.onEventBlocks.forEach { checkBlock(it.second, state.id) }
                }
            }
        }
    }

    private fun stateMachinesMustBeComplete() {
        managedModels.forEach { it.stateMachine.validateStatesAreComplete() }
    }

    /** Every decision function passed to a `transitionWhen { on(...) }`, with the state it was declared in. */
    private fun transitionDecisions(): List<Pair<StateId, Function<*>>> {
        fun decisionsIn(block: Block<*, *, *, *>): List<Function<*>> = when (block) {
            is Block.InstanceEventBlock<*, *, *, *, *> -> block.executables
                .filterIsInstance<InstanceEventTransitionWhen<*, *, *, *, *>>().flatMap { it.branches.keys }

            is Block.InstanceLifecycleBlock -> block.executables
                .filterIsInstance<InstanceLifecycleTransitionWhen<*, *, *, *>>().flatMap { it.branches.keys }

            else -> emptyList()
        }
        return managedModels.flatMap { managed ->
            managed.stateMachine.mutableStates.flatMap { state ->
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
    private fun allEventsMustBeDeclared() {
        managedModels.map { it.stateMachine }.forEach { sm ->
            sm.mutableStates.flatMap { state ->
                when (state) {
                    is VoidState -> state.onEventBlocks.map { it.first }
                    is InstanceState -> state.onEventBlocks.map { it.first }
                }
            }
                .forEach {
                    if (!sm.declaredEvents.contains(it)) {
                        throw IllegalConfigurationException(
                            KlerkErrorCode.EventNotDeclared,
                            "The event '${it.id}' must be declared before used in state"
                        )
                    }
                }
        }
    }

    private fun parametersWithReferencesMustHaveValidReferences() {
        managedModels.map { it.stateMachine }.forEach { sm ->
            sm.mutableStates.flatMap { state ->
                when (state) {
                    is VoidState -> state.onEventBlocks.map { it.first }
                    is InstanceState -> state.onEventBlocks.map { it.first }
                }
            }
                .forEach { event ->
                    val parametersClass = when (event) {
                        is InstanceEventWithParameters -> event.parametersClass
                        is VoidEventWithParameters -> event.parametersClass
                        else -> return@forEach
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
    private fun checkRefParam(
        parametersClass: KClass<*>,
        validRefs: Set<PropertyKey>,
        validEnums: Set<PropertyKey>,
        event: Event<*, *>
    ) {
        val leaves = ObjectSchema.of(parametersClass).leafFields()
        val references = leaves.filter { it.shape == Shape.Reference }
        references.firstOrNull { it.field.key !in validRefs }?.let {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingValidReferences, """
                '${it.path}' in '${parametersClass.simpleName}' for '$event' is a ModelID, but there is no 'validReferences' declared for it in the state machine. Declare which view the ids must be in, e.g.:
                event(${event.name}) {
                    validReferences(${it.field.key}, views.the-view-of-the-referenced-model.all)
                }
                Pass null instead of a view to accept any id.
                """.trimIndent()
            )
        }
        val enums = leaves.filter { (it.shape as? Shape.Container)?.kind == ContainerKind.Enum }
        val unknown = (validRefs - references.map { it.field.key }.toSet()).map { "validReferences($it, ...)" } +
                (validEnums - enums.map { it.field.key }.toSet()).map { "validEnums($it, ...)" }
        if (unknown.isNotEmpty()) {
            throw IllegalConfigurationException(
                KlerkErrorCode.ValidationRuleForUnknownProperty,
                "${unknown.joinToString(", ")} is declared for '$event', but the property is not a matching property " +
                        "of ${parametersClass.simpleName} or a class nested in it"
            )
        }
    }

    /**
     * The model classes registered via `managedModels { model(...) }` when the specification was built.
     */
    public fun getManagedClasses(): Set<KClass<out Any>> {
        return managedModels.map { it.kClass }.toSet()
    }

    /**
     * The [ModelViews] registered for the model class [clazz] (i.e. the same instance exposed as a property on [V]).
     *
     * @throws NoSuchElementException if [clazz] is not a managed model
     */
    public fun <T : Any> getModelViews(clazz: KClass<T>): ModelViews<T, C> {
        val mm = managedModels.find { it.kClass == clazz }
            ?: throw NoSuchElementException("Cannot find views for ${clazz.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return mm.views as ModelViews<T, C>
    }

    /** Every [ModelView] declared on any managed model's [ModelViews], with the model class it belongs to. */
    public fun getViews(): List<RegisteredView<C>> =
        managedModels.flatMap { managed ->
            managed.views.getViews().map { RegisteredView(managed.kClass, it) }
        }

    /**
     * Looks up a single [ModelView] by its [ViewId].
     *
     * @throws NoSuchElementException if no view matches [id]
     */
    public fun getView(id: ViewId): ModelView<out Any, C> =
        getViews().firstOrNull { it.view.id == id }?.view
            ?: throw NoSuchElementException("Cannot find view '$id'")

    /**
     * The [ModelView] declared with `validReferences(...)` for [parameter] of the event [eventReference], or null if
     * the event has no parameters, or none was declared for that parameter (see `validReferences` in
     * [dev.klerkframework.klerk.statemachine.InstanceEventRulesWithParameters] /
     * [dev.klerkframework.klerk.statemachine.VoidEventRulesWithParameters]).
     */
    public fun validReferencesFor(
        eventReference: EventReference,
        parameter: SchemaField
    ): ModelView<out Any, C>? {
        val parametersClass = parametersClassOf(eventReference) ?: return null
        return validReferencesOf(eventReference)[PropertyKey(parametersClass, parameter.name)]
    }

    /** What the `event(...)` block declared for [eventReference], looked up in the state machine that declared it. */
    internal fun rulesOf(eventReference: EventReference): DeclaredEventRules =
        getStateMachine(eventReference).rulesFor(eventReference)

    /**
     * The rules declared with `validateWithContext(...)` for [eventReference] — the ones that run against the
     * context alone, before anything is read. Mainly for tooling that documents an event.
     */
    public fun contextRulesFor(eventReference: EventReference): Set<(C) -> PropertyCollectionValidity> =
        rulesOf(eventReference).forContext()

    /** The views declared with `validReferences` for the event, by property. A null view accepts any id. */
    @Suppress("UNCHECKED_CAST")
    internal fun validReferencesOf(eventReference: EventReference): Map<PropertyKey, ModelView<out Any, C>?> =
        rulesOf(eventReference).validRefs as Map<PropertyKey, ModelView<out Any, C>?>

    /** The values declared with `validEnums` for the event, by property. */
    internal fun validEnumsOf(eventReference: EventReference): Map<PropertyKey, Set<Enum<*>>> =
        rulesOf(eventReference).validEnums

    private fun parametersClassOf(eventReference: EventReference): KClass<*>? =
        when (val event = getEvent(eventReference)) {
            is InstanceEventWithParameters<*, *> -> event.parametersClass
            is VoidEventWithParameters<*, *> -> event.parametersClass
            else -> null
        }

    /**
     * The set of allowed values declared with `validEnums(...)` for [parameter] of the event [eventReference], or
     * null if none was declared (in which case all enum values are allowed).
     */
    public fun validEnumsFor(
        eventReference: EventReference,
        parameter: SchemaField
    ): Set<Enum<*>>? {
        val parametersClass = parametersClassOf(eventReference) ?: return null
        return validEnumsOf(eventReference)[PropertyKey(parametersClass, parameter.name)]
    }

    /**
     * Looks up the declared [Event] for [eventId].
     *
     * @throws NoSuchElementException if no managed model declares an event with this reference
     */
    public fun getEvent(eventId: EventReference): Event<Any, Any?> {
        @Suppress("UNCHECKED_CAST")
        return getStateMachine(eventId).mutableStates.flatMap { it.getEvents() }
            .first { it.id == eventId } as Event<Any, Any?>
    }

    internal fun getStateMachine(eventReference: EventReference): StateMachine<out Any, out Enum<*>, C, V> {
        return managedModels.find { it.stateMachine.type.simpleName == eventReference.modelName }?.stateMachine
            ?: throw RuntimeException("Can't find state machine for event '$eventReference'")
    }

    internal fun <T : Any> getStateMachine(model: Model<T>): StateMachine<T, out Enum<*>, C, V> {
        val sm = managedModels.find { it.kClass == model.props::class }?.stateMachine
            ?: throw InternalException()
        @Suppress("UNCHECKED_CAST")
        return sm as StateMachine<T, out Enum<*>, C, V>
    }

    internal fun <T : Any> getStateMachine(clazz: KClass<T>): StateMachine<out Any, out Enum<*>, C, V> {
        val sm = managedModels.find { it.kClass == clazz }?.stateMachine
            ?: throw InternalException()
        return sm
    }

    /**
     * The [ObjectSchema] of the parameters class of the event [eventReference], or null if the event takes no
     * parameters.
     */
    public fun parametersSchema(eventReference: EventReference): ObjectSchema<*>? =
        parametersClassOf(eventReference)?.let { ObjectSchema.of(it) }

    /**
     * The void events (i.e. events that create a new instance of [clazz]) declared at or above [visibility], without
     * consulting the validation rules. Internal, because that last part makes it the wrong answer for everyone except
     * [dev.klerkframework.klerk.read.Reader.getPossibleVoidEvents], which adds validation on top.
     */
    internal fun <T : Any> getPossibleVoidEvents(
        clazz: KClass<T>,
        context: C,
        visibility: EventVisibility = EventVisibility.Code
    ): Set<EventReference> = getStateMachine(clazz).getEventsForVoidState(context, visibility)

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any, P> getStateMachineForEvent(event: Event<T, P>): StateMachine<T, out Enum<*>, C, V> =
        getStateMachine(event.id) as StateMachine<T, out Enum<*>, C, V>

    /**
     * The same configuration with a plugin's own job types and crons added, for use from
     * [KlerkPlugin.mergeSpecification]:
     *
     * ```kotlin
     * override fun mergeSpecification(previous: Specification<C, V>): Specification<C, V> =
     *     previous.withJobs {
     *         register(sweepStagingArea)
     *         cron(sweepStagingArea, "0 * * * *") { cursor = "" }
     *     }
     * ```
     *
     * A plugin can add work, not change how the job module runs: how many steps run at once, how often the dispatcher
     * polls and what happens to an unloadable job stay the application's decisions.
     *
     * @throws IllegalArgumentException if a job name is already registered — prefix names with the plugin's own.
     */
    public fun withJobs(init: PluginJobsBlock<C, V>.() -> Unit): Specification<C, V> {
        val block = JobsBlock<C, V>()
        block.seedFrom(jobs)
        PluginJobsBlock(block).init()
        return copy(jobs = jobs.with(block.types(), block.crons()))
    }

    public fun withPlugin(plugin: KlerkPlugin<C, V>): Specification<C, V> {
        val updatedPlugins = plugins.toMutableList()
        updatedPlugins.add(plugin)
        return plugin.mergeSpecification(this).copy(plugins = updatedPlugins)
    }

}

/**
 * The assembled authorization rule sets, one property per category. Built by [SpecificationBuilder.authorization]; not
 * meant to be constructed directly by application code.
 */
public data class AuthorizationConfig<C : KlerkContext, V>(
    val readModelPositiveRules: Set<(ModelReadRuleArgs<C, V>) -> PositiveAuthorization>,
    val readModelNegativeRules: Set<(ModelReadRuleArgs<C, V>) -> NegativeAuthorization>,
    val readPropertyPositiveRules: Set<(PropertyReadRuleArgs<C, V>) -> PositiveAuthorization>,
    val readPropertyNegativeRules: Set<(PropertyReadRuleArgs<C, V>) -> NegativeAuthorization>,
    val eventPositiveRules: Set<(CommandRuleArgs<*, C, V>) -> PositiveAuthorization>,
    val eventNegativeRules: Set<(CommandRuleArgs<*, C, V>) -> NegativeAuthorization>,
    val eventLogPositiveRules: Set<(args: EventLogRuleArgs<C, V>) -> PositiveAuthorization>,
    val eventLogNegativeRules: Set<(args: EventLogRuleArgs<C, V>) -> NegativeAuthorization>,
    val attachedDataReadPositiveRules: Set<(AttachedDataReadRuleArgs<C, V>) -> PositiveAuthorization> = emptySet(),
    val attachedDataReadNegativeRules: Set<(AttachedDataReadRuleArgs<C, V>) -> NegativeAuthorization> = emptySet(),
    val attachedDataWritePositiveRules: Set<(AttachedDataWriteRuleArgs<C, V>) -> PositiveAuthorization> = emptySet(),
    val attachedDataWriteNegativeRules: Set<(AttachedDataWriteRuleArgs<C, V>) -> NegativeAuthorization> = emptySet(),
    val jobPositiveRules: Set<(JobReadRuleArgs<C, V>) -> PositiveAuthorization> = emptySet(),
    val jobNegativeRules: Set<(JobReadRuleArgs<C, V>) -> NegativeAuthorization> = emptySet(),
)

@DslMarker
internal annotation class SpecificationMarker

/**
 * DSL entry point for building a [Specification]. Typical usage:
 * ```
 * val specification = SpecificationBuilder<MyContext, MyViews>(views).build {
 *     systemContextProvider { MyContext(SystemIdentity) }
 *     managedModels { model(MyModel::class, myModelStateMachine, myModelViews) }
 *     authorization { ... }
 * }
 * ```
 * [systemContextProvider], [managedModels] and [authorization] are all required; [build] throws
 * [IllegalConfigurationException] if any is missing. [migrations] and [jobs] are optional.
 *
 * Where the data is stored, what the clock is and how the job dispatcher is tuned are not part of the specification
 * — they go in [KlerkSettings].
 */
@SpecificationMarker
public class SpecificationBuilder<C : KlerkContext, V>(private val views: V) {

    /**
     * Runs [init] against this builder and assembles the resulting [Specification].
     *
     * @throws IllegalConfigurationException if [systemContextProvider], [authorization] or [managedModels] was not
     * called inside [init]
     */
    public fun build(init: SpecificationBuilder<C, V>.() -> Unit): Specification<C, V> {
        this.init()
        runCatching { systemContextProviderValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingSystemContextProvider,
                "'systemContextProvider' is missing in the specification"
            )
        }
        runCatching { authorizationRulesBlock }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingAuthorization,
                "'authorization' is missing in the specification"
            )
        }
        runCatching { managedModelsValue }.onFailure {
            throw IllegalConfigurationException(
                KlerkErrorCode.MissingManagedModels,
                "'managedModels' is missing in the specification"
            )
        }

        return Specification(
            views = views,
            authorization = AuthorizationConfig(
                readModelPositiveRules = authorizationRulesBlock.readModelPositiveRules,
                readModelNegativeRules = authorizationRulesBlock.readModelNegativeRules,
                readPropertyPositiveRules = authorizationRulesBlock.readPropertyPositiveRules,
                readPropertyNegativeRules = authorizationRulesBlock.readPropertyNegativeRules,
                eventPositiveRules = authorizationRulesBlock.eventPositiveRules,
                eventNegativeRules = authorizationRulesBlock.eventNegativeRules,
                eventLogPositiveRules = authorizationRulesBlock.eventLogPositiveRules,
                eventLogNegativeRules = authorizationRulesBlock.eventLogNegativeRules,
                attachedDataReadPositiveRules = authorizationRulesBlock.attachedDataReadPositiveRules,
                attachedDataReadNegativeRules = authorizationRulesBlock.attachedDataReadNegativeRules,
                attachedDataWritePositiveRules = authorizationRulesBlock.attachedDataWritePositiveRules,
                attachedDataWriteNegativeRules = authorizationRulesBlock.attachedDataWriteNegativeRules,
                jobPositiveRules = authorizationRulesBlock.jobPositiveRules,
                jobNegativeRules = authorizationRulesBlock.jobNegativeRules,
            ),
            managedModels = managedModelsValue,
            migrationSteps = migrationStepsValue,
            systemContextProvider = systemContextProviderValue,
            jobs = jobsValue,
            jobContextProvider = jobContextProviderValue,
            eraseEventLogAfterModelDeletion = eraseEventLogValue,
        ).let { spec -> pluginsValue.fold(spec) { acc, plugin -> acc.withPlugin(plugin) } }
    }

    private var migrationStepsValue: SortedSet<MigrationStep> = sortedSetOf()
    private var pluginsValue: List<KlerkPlugin<C, V>> = emptyList()
    private var jobsValue: JobsSpecification<C, V> = JobsSpecification.empty()
    private var jobContextProviderValue: ((JobContextRequest) -> C)? = null
    private var eraseEventLogValue: Duration? = null
    private lateinit var authorizationRulesBlock: AuthorizationRulesBlock<C, V>
    private lateinit var managedModelsValue: Set<ManagedModel<*, *, C, V>>
    private lateinit var systemContextProviderValue: (() -> C)

    /**
     * Erases the event log of a model when the model is deleted. Only [Duration.ZERO] (erase immediately) is
     * currently supported; the default is to never erase.
     */
    public fun eraseEventLogAfterModelDeletion(after: Duration) {
        eraseEventLogValue = after
    }

    /**
     * Registers [KlerkPlugin]s. Each plugin contributes its own models, events, rules and jobs, and is started and
     * stopped together with Klerk. They are merged in the order given, so a plugin that builds on another must come
     * after it.
     *
     * ```kotlin
     * val images = ImagesPlugin()
     * SpecificationBuilder<Ctx, Views>(views).build {
     *     plugins(images, AssetsPlugin(setOf(css), images = images))
     *     // ...
     * }
     * ```
     */
    public fun plugins(vararg plugin: KlerkPlugin<C, V>) {
        pluginsValue = pluginsValue + plugin
    }

    /**
     * Registers the [MigrationStep]s used to evolve model shapes across schema versions. Steps are reordered by
     * [MigrationStep.migratesToVersion]; [Specification] additionally requires them to form a contiguous chain starting at
     * version 2 (version 1 is implicit). Optional — omit if the schema has never changed.
     */
    public fun migrations(vararg migrationSteps: MigrationStep) {
        migrationStepsValue =
            sortedSetOf(Comparator.comparingInt(MigrationStep::migratesToVersion), *migrationSteps)
    }

    /**
     * Klerk may execute commands in the background (e.g. if you have a statemachine with after(5.minutes) {...}). And
     * since a context is required when executing a command, Klerk needs a way to create such a context with the
     * SystemIdentity.
     */
    public fun systemContextProvider(provider: () -> C) {
        systemContextProviderValue = provider
    }

    /**
     * Registers the models Klerk manages. Call [ManagedModelsBlock.model] once per model class inside [init].
     * Required — at least the models used by the application must be declared here.
     */
    public fun managedModels(init: ManagedModelsBlock<C, V>.() -> Unit) {
        val block = ManagedModelsBlock<C, V>()
        block.init()
        managedModelsValue = block.value
    }

    /**
     * Declares the authorization rules that govern reads, commands, the event log, and attached data. Required — see
     * [AuthorizationRulesBlock] for the sub-blocks, or [AuthorizationRulesBlock.allowEverythingInsecurely] to opt out
     * of authorization entirely (development/testing only).
     */
    public fun authorization(init: AuthorizationRulesBlock<C, V>.() -> Unit) {
        authorizationRulesBlock = AuthorizationRulesBlock<C, V>()
        authorizationRulesBlock.init()
    }

    /**
     * Registers job types and cron schedules, and configures the job module. Optional — omit it if the application has
     * no jobs.
     *
     * ```
     * jobs {
     *     register(ImportBooks)
     *     cron(NightlyCleanup, "0 3 * * *") { cursor = CleanupCursor() }
     * }
     * ```
     */
    public fun jobs(init: JobsBlock<C, V>.() -> Unit) {
        val block = JobsBlock<C, V>()
        block.init()
        jobsValue = block.build()
    }

    /**
     * Builds the context each job step runs under, from the job's agent, the configured clock and the job itself.
     * Optional; without it, [systemContextProvider] is used and the job runs as the system.
     *
     * Required if any registered job type declares [dev.klerkframework.klerk.job.JobAgent.Scheduler], since Klerk
     * cannot construct a context for an arbitrary actor on its own.
     *
     * ```
     * jobContextProvider(::jobContext)
     *
     * fun jobContext(request: JobContextRequest): Ctx = Ctx(actor = request.actor, time = request.time)
     * ```
     *
     * The actor is rebuilt from storage, so an actor identified by a model arrives as a [ModelReferenceIdentity] — the
     * id, not the model — and this function has no reader with which to load it. Rules that a job's commands must pass
     * should therefore compare the actor's id rather than a model the context only carries on a request.
     */
    public fun jobContextProvider(provider: (JobContextRequest) -> C) {
        jobContextProviderValue = provider
    }

    @SpecificationMarker
    public class ManagedModelsBlock<C : KlerkContext, V> {

        internal val value = mutableSetOf<ManagedModel<*, *, C, V>>()

        /**
         * Registers [clazz] as a managed model with its [stateMachine] and [view] (the [ModelViews] holding its
         * Kotlin collections). [clazz] must be a data class that Klerk can handle (see [ObjectSchema]); every managed model's
         * simple name must be unique within the specification.
         *
         * @throws IllegalArgumentException if [clazz] is not a data class, or another managed model already has the
         * same simple name
         * @throws IllegalConfigurationException if [clazz] has a `var`, or a property Klerk cannot store
         */
        public fun <T : Any, ModelStates : Enum<*>> model(
            clazz: KClass<T>,
            stateMachine: StateMachine<T, ModelStates, C, V>,
            view: ModelViews<T, C>
        ) {
            validateModelClass(clazz)
            require(!value.map { it.kClass.simpleName!! }
                .contains(clazz.simpleName!!)) { "Managed models must have unique simpleNames ('${clazz.simpleName!!}' is not unique)" }
            value.add(ManagedModel(clazz, stateMachine, view))
        }

    }

    /**
     * The six independent authorization categories, each with a `positive { rule(...) }` / `negative { rule(...) }`
     * pair. A category with no rules denies everything in it. See the "Authorization" doc for how positive/negative
     * rules combine, or [allowEverythingInsecurely] to disable authorization for development.
     *
     * Every rule must be a named function reference, e.g. `rule(::myRule)`. A lambda is rejected when Klerk starts.
     */
    @SpecificationMarker
    public class AuthorizationRulesBlock<C : KlerkContext, V> {

        internal val readModelPositiveRules =
            mutableSetOf<(ModelReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val readModelNegativeRules =
            mutableSetOf<(ModelReadRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val readPropertyPositiveRules =
            mutableSetOf<(PropertyReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val readPropertyNegativeRules =
            mutableSetOf<(PropertyReadRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val eventPositiveRules =
            mutableSetOf<(CommandRuleArgs<*, C, V>) -> PositiveAuthorization>()
        internal val eventNegativeRules =
            mutableSetOf<(CommandRuleArgs<*, C, V>) -> NegativeAuthorization>()
        internal val eventLogPositiveRules =
            mutableSetOf<(args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()
        internal val eventLogNegativeRules =
            mutableSetOf<(args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()
        internal val attachedDataReadPositiveRules =
            mutableSetOf<(AttachedDataReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val attachedDataReadNegativeRules =
            mutableSetOf<(AttachedDataReadRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val attachedDataWritePositiveRules =
            mutableSetOf<(AttachedDataWriteRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val attachedDataWriteNegativeRules =
            mutableSetOf<(AttachedDataWriteRuleArgs<C, V>) -> NegativeAuthorization>()
        internal val jobPositiveRules = mutableSetOf<(JobReadRuleArgs<C, V>) -> PositiveAuthorization>()
        internal val jobNegativeRules = mutableSetOf<(JobReadRuleArgs<C, V>) -> NegativeAuthorization>()


        /**
         * Rules deciding who may read a model as a whole (returned by `get`/`list`/views).
         */
        public fun readModels(init: AuthorizationReadRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationReadRulesBlock<C, V>()
            block.init()
            readModelPositiveRules.addAll(block.positiveBlock.rules)
            readModelNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may read individual model properties. Evaluated per property, independently of [readModels].
         */
        public fun readProperties(init: AuthorizationReadPropertiesRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationReadPropertiesRulesBlock<C, V>()
            block.init()
            readPropertyPositiveRules.addAll(block.positiveBlock.rules)
            readPropertyNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may trigger which events/commands, i.e. who is allowed to call [Klerk.handle] for a
         * given command.
         */
        public fun commands(init: AuthorizationEventsRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationEventsRulesBlock<C, V>()
            block.init()
            eventPositiveRules.addAll(block.positiveBlock.rules)
            eventNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may read the event log, i.e. [dev.klerkframework.klerk.read.Reader.eventLog].
         */
        public fun eventLog(init: AuthorizationEventLogRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationEventLogRulesBlock<C, V>()
            block.init()
            eventLogPositiveRules.addAll(block.positiveBlock.rules)
            eventLogNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may read attached data, i.e. `klerk.attachedData.get(...)` and
         * `klerk.attachedData.getMetadata(...)`.
         *
         * Note that these rules are only consulted for private data. Data prepared as
         * [dev.klerkframework.klerk.AttachedDataVisibility.Public] is readable by anyone, and not even a negative rule
         * here will stop that — the point of public data is that the decision cannot change over time.
         * See [dev.klerkframework.klerk.KlerkAttachedData].
         */
        public fun readAttachedData(init: AuthorizationAttachedDataReadRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationAttachedDataReadRulesBlock<C, V>()
            block.init()
            attachedDataReadPositiveRules.addAll(block.positiveBlock.rules)
            attachedDataReadNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may prepare attached data, i.e. `klerk.attachedData.prepare(...)`.
         *
         * Note that this is weak by construction: at that point there is no model and no command. The real gate on
         * *attaching* data to a model is the normal event authorization of the command that claims it. What these
         * rules are good at is the visibility: they can let anyone upload while allowing only some actors to publish
         * something the whole world may read. See [dev.klerkframework.klerk.KlerkAttachedData].
         */
        public fun writeAttachedData(init: AuthorizationAttachedDataWriteRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationAttachedDataWriteRulesBlock<C, V>()
            block.init()
            attachedDataWritePositiveRules.addAll(block.positiveBlock.rules)
            attachedDataWriteNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Rules deciding who may see a job's metadata — its status, progress and log — via
         * [JobManager.get]/[JobManager.all]/[JobManager.subscribe].
         *
         * The same rules gate [JobManager.cancel], so a user who can watch their own progress bar can also cancel
         * their own job. [JobReadRuleArgs.isOwnedBy] answers "did this actor schedule it?".
         */
        public fun jobs(init: AuthorizationJobRulesBlock<C, V>.() -> Unit) {
            val block = AuthorizationJobRulesBlock<C, V>()
            block.init()
            jobPositiveRules.addAll(block.positiveBlock.rules)
            jobNegativeRules.addAll(block.negativeBlock.rules)
        }

        /**
         * Allows every actor to do everything: read all models/properties/event log/attached data and trigger all
         * commands. Logs a warning when applied.
         *
         * ```kotlin
         * authorization { allowEverythingInsecurely() }
         * ```
         *
         * For development/testing only — never use in production.
         */
        public fun allowEverythingInsecurely() {
            logger.warn { "The authorization rules allows everything. The application is insecure!" }
            readModels {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadModels)
                }
                negative {
                }
            }

            readProperties {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadAllProperties)
                }
                negative {
                }
            }
            commands {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanDoEverything)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadEventLog)
                }
                negative {}
            }
            readAttachedData {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanReadAllAttachedData)
                }
                negative {}
            }
            writeAttachedData {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanWriteAttachedData)
                }
                negative {}
            }
            jobs {
                positive {
                    rule(this@AuthorizationRulesBlock::everybodyCanSeeAllJobs)
                }
                negative {}
            }
        }

        private fun everybodyCanSeeAllJobs(args: JobReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadModels(args: ModelReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadAllAttachedData(args: AttachedDataReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanWriteAttachedData(args: AttachedDataWriteRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadAllProperties(args: PropertyReadRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanDoEverything(args: CommandRuleArgs<*, C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

        private fun everybodyCanReadEventLog(args: EventLogRuleArgs<C, V>): PositiveAuthorization =
            PositiveAuthorization.Allow

    }


    @SpecificationMarker
    public class AuthorizationReadRulesBlock<C : KlerkContext, V> {

        internal val positiveBlock: AuthorizationReadPositiveRulesBlock<C, V> = AuthorizationReadPositiveRulesBlock()
        internal val negativeBlock: AuthorizationReadNegativeRulesBlock<C, V> = AuthorizationReadNegativeRulesBlock()

        public fun positive(init: AuthorizationReadPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationReadNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }

    }

    @SpecificationMarker
    public class AuthorizationReadPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ModelReadRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (ModelReadRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationReadNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(ModelReadRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (ModelReadRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    // properties
    @SpecificationMarker
    public class AuthorizationReadPropertiesRulesBlock<C : KlerkContext, V> {

        internal val positiveBlock: AuthorizationReadPropertyPositiveRulesBlock<C, V> = AuthorizationReadPropertyPositiveRulesBlock()
        internal val negativeBlock: AuthorizationReadPropertyNegativeRulesBlock<C, V> = AuthorizationReadPropertyNegativeRulesBlock()

        public fun positive(init: AuthorizationReadPropertyPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationReadPropertyNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }

    }

    @SpecificationMarker
    public class AuthorizationReadPropertyPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(PropertyReadRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (PropertyReadRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationReadPropertyNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(PropertyReadRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (PropertyReadRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    // attached data
    @SpecificationMarker
    public class AuthorizationAttachedDataReadRulesBlock<C : KlerkContext, V> {

        internal val positiveBlock: AuthorizationAttachedDataReadPositiveRulesBlock<C, V> = AuthorizationAttachedDataReadPositiveRulesBlock()
        internal val negativeBlock: AuthorizationAttachedDataReadNegativeRulesBlock<C, V> = AuthorizationAttachedDataReadNegativeRulesBlock()

        public fun positive(init: AuthorizationAttachedDataReadPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationAttachedDataReadNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }
    }

    @SpecificationMarker
    public class AuthorizationAttachedDataReadPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(AttachedDataReadRuleArgs<C, V>) -> PositiveAuthorization>()

        public fun rule(function: (AttachedDataReadRuleArgs<C, V>) -> PositiveAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationAttachedDataReadNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(AttachedDataReadRuleArgs<C, V>) -> NegativeAuthorization>()

        public fun rule(function: (AttachedDataReadRuleArgs<C, V>) -> NegativeAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationAttachedDataWriteRulesBlock<C : KlerkContext, V> {

        internal val positiveBlock: AuthorizationAttachedDataWritePositiveRulesBlock<C, V> = AuthorizationAttachedDataWritePositiveRulesBlock()
        internal val negativeBlock: AuthorizationAttachedDataWriteNegativeRulesBlock<C, V> = AuthorizationAttachedDataWriteNegativeRulesBlock()

        public fun positive(init: AuthorizationAttachedDataWritePositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationAttachedDataWriteNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }
    }

    @SpecificationMarker
    public class AuthorizationAttachedDataWritePositiveRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(AttachedDataWriteRuleArgs<C, V>) -> PositiveAuthorization>()

        public fun rule(function: (AttachedDataWriteRuleArgs<C, V>) -> PositiveAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationAttachedDataWriteNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(AttachedDataWriteRuleArgs<C, V>) -> NegativeAuthorization>()

        public fun rule(function: (AttachedDataWriteRuleArgs<C, V>) -> NegativeAuthorization) {
            rules.add(function)
        }
    }

    // jobs
    @SpecificationMarker
    public class AuthorizationJobRulesBlock<C : KlerkContext, V> {

        internal val positiveBlock: AuthorizationJobPositiveRulesBlock<C, V> = AuthorizationJobPositiveRulesBlock()
        internal val negativeBlock: AuthorizationJobNegativeRulesBlock<C, V> = AuthorizationJobNegativeRulesBlock()

        public fun positive(init: AuthorizationJobPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationJobNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }
    }

    @SpecificationMarker
    public class AuthorizationJobPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(JobReadRuleArgs<C, V>) -> PositiveAuthorization>()

        public fun rule(function: (JobReadRuleArgs<C, V>) -> PositiveAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationJobNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules = mutableSetOf<(JobReadRuleArgs<C, V>) -> NegativeAuthorization>()

        public fun rule(function: (JobReadRuleArgs<C, V>) -> NegativeAuthorization) {
            rules.add(function)
        }
    }

    // events
    @SpecificationMarker
    public class AuthorizationEventsRulesBlock<C : KlerkContext, V> {
        internal val positiveBlock: AuthorizationEventsPositiveRulesBlock<C, V> = AuthorizationEventsPositiveRulesBlock()
        internal val negativeBlock: AuthorizationEventsNegativeRulesBlock<C, V> = AuthorizationEventsNegativeRulesBlock()

        public fun positive(init: AuthorizationEventsPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationEventsNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }
    }

    @SpecificationMarker
    public class AuthorizationEventsPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(CommandRuleArgs<*, C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (args: CommandRuleArgs<*, C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }

    }

    @SpecificationMarker
    public class AuthorizationEventsNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(CommandRuleArgs<*, C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (CommandRuleArgs<*, C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationEventLogRulesBlock<C : KlerkContext, V> {
        internal val positiveBlock: AuthorizationEventLogPositiveRulesBlock<C, V> = AuthorizationEventLogPositiveRulesBlock()
        internal val negativeBlock: AuthorizationEventLogNegativeRulesBlock<C, V> = AuthorizationEventLogNegativeRulesBlock()

        public fun positive(init: AuthorizationEventLogPositiveRulesBlock<C, V>.() -> Unit) {
            positiveBlock.init()
        }

        public fun negative(init: AuthorizationEventLogNegativeRulesBlock<C, V>.() -> Unit) {
            negativeBlock.init()
        }
    }

    @SpecificationMarker
    public class AuthorizationEventLogPositiveRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization>()

        public fun rule(function: (args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.PositiveAuthorization) {
            rules.add(function)
        }
    }

    @SpecificationMarker
    public class AuthorizationEventLogNegativeRulesBlock<C : KlerkContext, V> {
        internal val rules =
            mutableSetOf<(args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization>()

        public fun rule(function: (args: EventLogRuleArgs<C, V>) -> dev.klerkframework.klerk.NegativeAuthorization) {
            rules.add(function)
        }
    }

}

private fun <T : Any> validateModelClass(clazz: KClass<T>) {
    require(clazz.isData) { "${clazz.qualifiedName} must be a data class" }
    ObjectSchema.of(clazz)
}

/**
 * How *this instance* runs: where it stores its data, what it reads the time from, where it publishes metrics and how
 * hard the job dispatcher works. Passed to [Klerk.Companion.create] alongside the [Specification].
 *
 * Two deployments of the same application share a [Specification] and differ here. Nothing in this class changes what
 * the application does, only how it is operated — the one thing that does, whether the event log is erased on
 * deletion, is [Specification.eraseEventLogAfterModelDeletion].
 */
public data class KlerkSettings(

    /**
     * The storage backend this instance uses, e.g. [dev.klerkframework.klerk.storage.SqlPersistence] in production
     * and [dev.klerkframework.klerk.storage.RamStorage] in a test.
     */
    val persistence: Persistence,

    /**
     * Where the bytes of attached blobs are kept: [AttachedBlobStore.Database] or
     * [dev.klerkframework.klerk.storage.FileBlobStore]. Null means the application has no blobs.
     *
     * Required as soon as any model property or event parameter is an [AttachedBlobID] — the choice decides what a
     * database backup contains, so Klerk will not pick one for you. Attached *strings* are unaffected; they always
     * live in the database.
     *
     * Choose before the application has data: Klerk does not move blobs between stores, and refuses to start if the
     * configured store does not have the bytes it expects.
     */
    val attachedBlobStore: AttachedBlobStore? = null,

    /**
     * Where *background* work gets the current time from: job scheduling, retry backoff, cron, delay-based admission
     * and state-machine time triggers.
     *
     * Actor-driven work reads its time from the caller's [KlerkContext.time] instead and is unaffected by this. The
     * split is deliberate: a test can control actor-driven time simply by constructing a context, and this clock is
     * how it controls everything else. See [dev.klerkframework.klerk.misc.MutableClock].
     */
    val clock: Clock = Clock.System,

    /**
     * The [MeterRegistry] Klerk publishes metrics to. Defaults to a private [SimpleMeterRegistry] that isn't exported
     * anywhere, so set this to integrate with your application's metrics backend.
     */
    val meterRegistry: MeterRegistry = SimpleMeterRegistry(),

    /** How the job module is operated: parallelism, polling, retention and retry backoff. */
    val jobs: JobSettings = JobSettings(),

    /**
     * How much model data is kept in memory.
     */
    val modelCache: ModelCacheSettings = ModelCacheSettings(),

    /**
     * Gates the "escape hatch" functions on [Klerk.unsafe] ([KlerkUnsafe.create], [KlerkUnsafe.update],
     * [KlerkUnsafe.delete]), which bypass the state machine, validation and authorization entirely. Off by
     * default; enable only if you understand the risk.
     */
    val allowUnsafeOperations: Boolean = false,

    /**
     * Whether [dev.klerkframework.klerk.datatypes.DataContainer.valueWithoutAuthorization] may be used on the models
     * returned by a read or a command result. When false (the default) it throws there, so that application code
     * cannot bypass the `readProperties` rules. Reads made by the system, and containers you create yourself, are not
     * affected.
     */
    val allowBypassAuthRead: Boolean = false,

    /**
     * The lease [KlerkAttachedData.prepare] grants when the caller does not ask for one: how long prepared data
     * survives before a command claims it. Mainly here so that tests do not have to wait a minute.
     */
    val defaultAttachedDataLease: Duration = 1.minutes,

    /**
     * The longest lease [KlerkAttachedData.prepare] will grant. A lease keeps storage occupied by data that no model
     * refers to, so there is an upper bound; who may ask for a long one is decided by the `writeAttachedData` rules.
     */
    val maxAttachedDataLease: Duration = 24.hours,

    /**
     * How Klerk recognises the content type of an attached value from its first bytes (see
     * [dev.klerkframework.klerk.attacheddata.ContentTypeDetector]). Defaults to
     * [dev.klerkframework.klerk.attacheddata.DefaultContentTypeDetector], a small dependency-free set of magic-byte
     * signatures; replace it to recognise more formats, e.g. with a detector backed by Apache Tika.
     */
    val contentTypeDetector: ContentTypeDetector = DefaultContentTypeDetector,
) {

    /**
     * The current time for background work, at the precision Klerk persists timestamps with (see
     * [makeExactSerializable]), so that a value read here survives a round-trip through storage unchanged.
     */
    internal fun now(): Instant = makeExactSerializable(clock.now())

    public companion object {

        /**
         * Builds a [KlerkSettings] from environment variables, falling back to the regular default for any variable
         * that is unset. Only the settings with simple (boolean/[Duration]) types are read this way — `KLERK_` plus
         * the property name in `SCREAMING_SNAKE_CASE`, e.g. [maxAttachedDataLease] from `KLERK_MAX_ATTACHED_DATA_LEASE`.
         * A [Duration] variable is parsed with [Duration.parse], so both `"24h"` and `"PT24H"` work. [jobs] and
         * [modelCache] default to [JobSettings.fromEnvVars] and [ModelCacheSettings.fromEnvVars], so their settings
         * are read from `KLERK_JOBS_`- and `KLERK_MODEL_CACHE_`-prefixed variables unless passed explicitly.
         *
         * The remaining settings ([persistence], [attachedBlobStore], [clock], [meterRegistry],
         * [contentTypeDetector]) are not derived from the environment, since they carry objects rather than
         * primitive values; pass them as ordinary parameters.
         */
        public fun fromEnvVars(
            persistence: Persistence,
            attachedBlobStore: AttachedBlobStore? = null,
            clock: Clock = Clock.System,
            meterRegistry: MeterRegistry = SimpleMeterRegistry(),
            jobs: JobSettings = JobSettings.fromEnvVars(),
            modelCache: ModelCacheSettings = ModelCacheSettings.fromEnvVars(),
            contentTypeDetector: ContentTypeDetector = DefaultContentTypeDetector,
        ): KlerkSettings {
            val defaults = KlerkSettings(persistence = persistence)
            return KlerkSettings(
                persistence = persistence,
                attachedBlobStore = attachedBlobStore,
                clock = clock,
                meterRegistry = meterRegistry,
                jobs = jobs,
                modelCache = modelCache,
                allowUnsafeOperations = envBoolean("KLERK_ALLOW_UNSAFE_OPERATIONS") ?: defaults.allowUnsafeOperations,
                allowBypassAuthRead = envBoolean("KLERK_ALLOW_BYPASS_AUTH_READ") ?: defaults.allowBypassAuthRead,
                defaultAttachedDataLease = envDuration("KLERK_DEFAULT_ATTACHED_DATA_LEASE")
                    ?: defaults.defaultAttachedDataLease,
                maxAttachedDataLease = envDuration("KLERK_MAX_ATTACHED_DATA_LEASE") ?: defaults.maxAttachedDataLease,
                contentTypeDetector = contentTypeDetector,
            )
        }
    }
}

/**
 * Converts a camelCase identifier (e.g. a validation rule or function name) to a human-readable phrase, e.g.
 * `"mustBeEven"` -> `"Must be even"`. Not currently wired into [DefaultKlerkTranslation] or any other framework code.
 */
public fun defaultTranslator(rule: String): String {
    return camelCaseToPretty(rule)
}

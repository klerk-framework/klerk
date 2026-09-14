package dev.klerkframework.klerk

import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyKey
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.misc.functionName
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.StateMachine
import kotlinx.serialization.Serializable
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSupertypes
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Identifies a single state within a model's state machine, e.g. `s.Book.Published`.
 */
public data class StateId(val modelName: String, val stateName: String) {
    override fun toString(): String = "s.$modelName.$stateName"
    public fun withoutPrefix(): String = toString().substring(2)
}

/**
 * Identifies a [ModelView][dev.klerkframework.klerk.view.ModelView] within a [ModelViews][dev.klerkframework.klerk.view.ModelViews] container, e.g. `v.Book.all`.
 */
public data class ViewId(val modelName: String, val shortId: String) {
    override fun toString(): String = "v.$modelName.$shortId"

    public companion object {
        /**
         * @throws IllegalArgumentException if [string] is not of the form `v.<modelName>.<shortId>`
         */
        public fun parse(string: String): ViewId {
            val parts = string.split(".")
            require(parts.size == 3) { "ViewId must contain three parts separated by dots" }
            require(parts.first() == "v") { "ViewId must start with 'v.'" }
            return ViewId(parts[1], parts[2])
        }

        /** The id in [string], or null if it is not one. */
        public fun parseOrNull(string: String): ViewId? = runCatching { parse(string) }.getOrNull()
    }
}

/**
 * Wires a model's props class to its [StateMachine] and [ModelViews]. Registered via
 * `SpecificationBuilder.managedModels { model(...) }`; not normally constructed directly.
 */
public data class ManagedModel<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    val kClass: KClass<T>,
    val stateMachine: StateMachine<T, ModelStates, C, V>,
    val views: ModelViews<T, C>,
)

/**
 * A registered [ModelView] together with the model class it holds, as returned by `Specification.getViews()`.
 */
public data class RegisteredView<C : KlerkContext>(
    val modelClass: KClass<out Any>,
    val view: ModelView<out Any, C>,
)

/**
 * A stored instance: metadata (id, timestamps, current [state]) plus the model's props of type [T].
 * Returned from reads (e.g. [ModelReader.get]); never constructed directly by application code.
 */
public data class Model<T : Any>(
    val id: ModelID<T>,
    val createdAt: Instant,
    val lastPropsUpdateAt: Instant,
    val lastStateTransitionAt: Instant,
    val state: String,
    val timeTrigger: Instant?,
    val props: T,
) {
    /**
     * The time when this model was last modified, either by a state transition or updating properties.
     */
    public val lastModifiedAt: Instant get() = if (lastPropsUpdateAt > lastStateTransitionAt) lastPropsUpdateAt else lastStateTransitionAt

    override fun toString(): String {
        return props.toString()
    }
}


/**
 * Implemented by a props class to declare cross-property validation rules (rules spanning more than one property).
 * Each function returns a [PropertyCollectionValidity] evaluated after individual property validation passes.
 */
public fun interface Validatable {
    public fun validators(): Set<() -> PropertyCollectionValidity>
}

/** An event together with the [ObjectSchema] of its parameters class, e.g. to build a form for it. */
public data class EventWithParameters<T : Any>(val eventReference: EventReference, val parameters: ObjectSchema<T>) {
    public constructor(eventReference: EventReference, parametersClass: KClass<T>) :
            this(eventReference, ObjectSchema.of(parametersClass))
}

/**
 * A reference to a specific event in a state machine
 */
public data class EventReference(val modelName: String, val eventName: String) {
    init {
        require(!modelName.contains("/"))
        require(!eventName.contains("/"))
        require(!modelName.contains(":"))
        require(!eventName.contains(":"))
    }

    override fun toString(): String = "$modelName:$eventName"

    public companion object {
        /** @throws IllegalArgumentException if [eventId] is not of the form `<modelName>:<eventName>` */
        public fun parse(eventId: String): EventReference {
            val splitted = eventId.split(":")
            require(splitted.size == 2)
            return EventReference(splitted.first(), splitted.last())
        }

        /** The reference in [eventId], or null if it is not one. */
        public fun parseOrNull(eventId: String): EventReference? = runCatching { parse(eventId) }.getOrNull()
    }
}

/**
 * The visibility level of an event. The higher levels expand on the lower levels, e.g. InterStateMachine can be
 * created in all places where StateMachineInternal is allowed.
 */
public enum class EventVisibility(internal val level: Int) {

    /**
     * Can only be created within the same statemachine.
     */
    StateMachineInternal(1),

    /**
     * Can be created in any statemachine.
     */
    InterStateMachine(2),

    /**
     * Can be created in any statemachine and in application code. This level can be used for events that are triggered
     * by the system, e.g. in a Job.
     */
    System(3),

    /**
     * Can be created in any statemachine and in application code.
     */
    Code(4),

    /**
     * Can be created in any statemachine and in application code. Klerk doesn't differentiate this from Code, but this
     * level can be used as a signal to other code (e.g., auto-generated UI or API) that it should handle this event.
     */
    External(5)
}

/**
 * Base class of the four event kinds ([VoidEventNoParameters], [VoidEventWithParameters],
 * [InstanceEventNoParameters], [InstanceEventWithParameters]). Application code declares events as `object`s
 * extending one of those four, then registers them in a [StateMachine] with `event(...)` / `onEvent(...)`.
 *
 * ```kotlin
 * object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(External)
 * object PublishBook : InstanceEventNoParameters<Book>(External)
 * ```
 *
 * The model class and the parameters class are read from the type arguments, so they are written once.
 */
public sealed class Event<T : Any, P>(public val visibility: EventVisibility) {

    /** The `T` of the event kind this was declared as. */
    internal val forModel: KClass<*> = typeArgument(0)

    public val id: EventReference
        get() = EventReference(forModel.simpleName!!, name)

    public val name: String
        get() = this::class.simpleName!!

    /**
     * The [index]th type argument of the event-kind supertype. An `object` (or a class) that names its type arguments
     * concretely — which an event declaration always does — records them in its supertype, so nothing has to be
     * passed to the constructor.
     */
    protected fun typeArgument(index: Int): KClass<*> {
        val supertype = this::class.allSupertypes.firstOrNull { (it.classifier as? KClass<*>) in eventKinds }
            ?: throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "${this::class.simpleName} does not extend one of the four event kinds directly"
            )
        return (supertype.arguments.getOrNull(index)?.type?.classifier as? KClass<*>)
            ?: throw IllegalConfigurationException(
                KlerkErrorCode.InvalidStateMachine,
                "Could not work out the type arguments of the event '${this::class.simpleName}'. Declare it as an " +
                        "object (or a class) that names them concretely, e.g. " +
                        "'object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(External)'."
            )
    }

    override fun toString(): String = id.toString()

}

private val eventKinds = setOf(
    VoidEventWithParameters::class,
    VoidEventNoParameters::class,
    InstanceEventWithParameters::class,
    InstanceEventNoParameters::class,
)

public sealed class VoidEvent<T : Any, P>(visibility: EventVisibility) : Event<T, P>(visibility)

public sealed class InstanceEvent<T : Any, P>(visibility: EventVisibility) : Event<T, P>(visibility)

/**
 * A void event (creates a new model of type [T]) that takes parameters of type [P] when handled. Declare a
 * handler function `fun create(arg: ArgForVoidEvent<T, P, C, V>): T`.
 */
public abstract class VoidEventWithParameters<T : Any, P : Any>(visibility: EventVisibility) :
    VoidEvent<T, P>(visibility) {
    @Suppress("UNCHECKED_CAST")
    public val parametersClass: KClass<P> = typeArgument(1) as KClass<P>
}

/**
 * A void event (creates a new model of type [T]) that takes no parameters. Declare a handler function
 * `fun create(arg: ArgForVoidEvent<T, Nothing?, C, V>): T`.
 */
public abstract class VoidEventNoParameters<T : Any>(visibility: EventVisibility) :
    VoidEvent<T, Nothing?>(visibility)

/**
 * An instance event (acts on an existing model of type [T]) that takes parameters of type [P] when handled.
 * Declare a handler function `fun update(arg: ArgForInstanceEvent<T, P, C, V>): T`.
 */
public abstract class InstanceEventWithParameters<T : Any, P : Any>(visibility: EventVisibility) : InstanceEvent<T, P>(visibility) {
    @Suppress("UNCHECKED_CAST")
    public val parametersClass: KClass<P> = typeArgument(1) as KClass<P>
}

/**
 * An instance event (acts on an existing model of type [T]) that takes no parameters. Declare a handler function
 * `fun archive(arg: ArgForInstanceEvent<T, Nothing?, C, V>): T`.
 */
public abstract class InstanceEventNoParameters<T : Any>(visibility: EventVisibility) :
    InstanceEvent<T, Nothing?>(visibility)


/**
 * Arguments handed to context-only rules, e.g. rules deciding void events not tied to a model instance.
 */
public data class ArgContextReader<C : KlerkContext, V>(val context: C, val reader: ModelReader<C, V>)

/**
 * Arguments handed to rules that need to inspect the [command] being processed (e.g. event authorization rules).
 */
public data class ArgCommandContextReader<P, C : KlerkContext, V>(
    val command: Command<out Any, P>,
    val context: C,
    val reader: ModelReader<C, V>
)

/**
 * Arguments handed to rules that evaluate against an existing [model], e.g. read/authorization rules for instance
 * events.
 */
public data class ArgModelContextReader<C : KlerkContext, V>(
    val model: Model<out Any>,
    val context: C,
    val reader: ModelReader<C, V>
)

/**
 * Arguments handed to property-level authorization rules deciding whether [property] on [model] may be read.
 */
public data class ArgsForPropertyAuth<C : KlerkContext, V>(
    val property: DataContainer<*>,
    val model: Model<out Any>,
    val context: C,
    val reader: ModelReader<C, V>,
)

/**
 * @property reader Note that the reader will give you access to the data as it was _before_ the current event was
 * executed. I.e. if the current event has modified data in a previous step, the updated data will __not__ be accessible
 * through the reader.
 */
public data class ArgForVoidEvent<T : Any, P, C : KlerkContext, V>(
    val command: Command<T, P>,
    val context: C,
    val reader: ModelReader<C, V>,
)

/**
 * @param model The model as it is in the un-committed state. I.e. the model you see may differ from the model as it was
 * before the current processing (of an event or time-trigger).
 */
public data class ArgForInstanceEvent<T : Any, P, C : KlerkContext, V>(
    val model: Model<T>,
    val command: Command<T, P>,
    val context: C,
    val reader: ModelReader<C, V>
)

/**
 * @param model The model as it is in the un-committed state. I.e. the model you see may differ from the model as it was
 * before the current processing (of an event or time-trigger).
 */
public data class LifecycleArgs<T : Any, C : KlerkContext, V>(
    val model: Model<T>,
    val time: Instant,
    val reader: ModelReader<C, V>
)


/**
 * An identifier of a model of type [T].
 *
 * Model IDs are represented internally using Int but only the positive part, so the maximum amount of simultaneous
 * models is about 2 billion (we should find a way to use UInt).
 *
 * Implementation details: We first used UInt, but it seems that there is a problem when making this @JvmInline and
 * value class in combination with ULong and UInt (see KT-69674).
 *
 * The `@Serializable` annotation lets a job cursor hold a [ModelID] without the job author doing anything.
 */
@Serializable(with = ModelIDSerializer::class)
@JvmInline
public value class ModelID<T : Any>(public val value: Int) {

    override fun toString(): String = value.toString()
}

/**
 * A reference to a large blob attached to a model (see [KlerkAttachedData]).
 *
 * Obtain one from [KlerkAttachedData.prepare] and store it in a model property. The data is owned exclusively by the
 * first model that references it in a committed command, and is deleted when no property of that model refers to it
 * any more.
 *
 * Blobs and strings share one id space, so an id identifies a piece of attached data on its own — see
 * [AttachedDataKind].
 *
 * Implementation details: see the note on [ModelID] regarding @JvmInline and serialization.
 */
@Serializable(with = AttachedBlobIDSerializer::class)
@JvmInline
public value class AttachedBlobID(public val value: Int) {
    override fun toString(): String = value.toString()

    /** The same reference, with the kind forgotten — see [AttachedDataID]. */
    public fun untyped(): AttachedDataID = AttachedDataID(value)
}

/**
 * A reference to a large string attached to a model (see [KlerkAttachedData]).
 *
 * Obtain one from [KlerkAttachedData.prepare] and store it in a model property. The data is owned exclusively by the
 * first model that references it in a committed command, and is deleted when no property of that model refers to it
 * any more.
 *
 * Blobs and strings share one id space, so an id identifies a piece of attached data on its own — see
 * [AttachedDataKind].
 *
 * Implementation details: see the note on [ModelID] regarding @JvmInline and serialization.
 */
@Serializable(with = AttachedStringIDSerializer::class)
@JvmInline
public value class AttachedStringID(public val value: Int) {
    override fun toString(): String = value.toString()

    /** The same reference, with the kind forgotten — see [AttachedDataID]. */
    public fun untyped(): AttachedDataID = AttachedDataID(value)
}

/**
 * A reference to attached data whose kind is not known yet — what an HTTP route such as `/attached/{id}/{hash}`
 * holds.
 *
 * Blobs and strings share one id space, so this identifies a value on its own. Ask
 * [KlerkAttachedData.getMetadata] what it is: [AttachedDataMetadata.kind] says which kind it turned out to be, and
 * [asBlob]/[asString] then give the typed id needed to read the value.
 *
 * ```kotlin
 * val meta = klerk.attachedData.getMetadata(id, context)
 * val stream = when (meta.kind) {
 *     AttachedDataKind.Blob -> klerk.attachedData.get(id.asBlob(), context)
 *     AttachedDataKind.String -> klerk.attachedData.getStream(id.asString(), context)
 * }
 * ```
 */
@JvmInline
public value class AttachedDataID(public val value: Int) {
    override fun toString(): String = value.toString()

    /** This reference as a blob id. Reading a value that is a string through it throws. */
    public fun asBlob(): AttachedBlobID = AttachedBlobID(value)

    /** This reference as a string id. Reading a value that is a blob through it throws. */
    public fun asString(): AttachedStringID = AttachedStringID(value)

    public companion object {
        /** @throws IllegalArgumentException if [value] is not an id */
        public fun parse(value: String): AttachedDataID =
            AttachedDataID(requireNotNull(value.toIntOrNull()) { "Not an attached data id: '$value'" })

        /** The id in [value], or null if it is not one. For parsing a path parameter. */
        public fun parseOrNull(value: String?): AttachedDataID? = value?.toIntOrNull()?.let { AttachedDataID(it) }
    }
}

/**
 * Whether a piece of attached data is a blob or a string.
 *
 * The two are stored the same way (a string is its UTF-8 bytes) and share one id space; the kind is what decides
 * whether an id may be used as an [AttachedBlobID] or an [AttachedStringID]. Reading through the wrong one throws.
 *
 * It is reported by [AttachedDataMetadata.kind] so that a handler which is given nothing but an id — an HTTP route
 * such as `/attached/{id}/{hash}`, say — can tell what it is about to serve.
 */
public enum class AttachedDataKind {
    Blob,
    String,
}

/**
 * Who may read a piece of attached data. Declared by the [dev.klerkframework.klerk.datatypes.AttachedDataContainer]
 * the value is prepared for, and fixed for the life of the value.
 *
 * The point of [Public] is that it is a *static* property of the data. Authorization rules answer "may this actor read
 * this right now", which says nothing about the next request, so a rule-based decision can never be cached. A value
 * that is public at upload time stays public for its whole life, which is what makes it safe to hand to a CDN.
 */
public enum class AttachedDataVisibility {
    /** Only actors allowed by the `readAttachedData` rules may read the data. */
    Private,

    /** Anyone may read the data. No read rule is evaluated, not even a negative one. */
    Public,
}

/**
 * What is known about a piece of attached data apart from the value itself (see [KlerkAttachedData.getMetadata]).
 *
 * All of it is fixed when the data is uploaded and never changes.
 *
 * @property id what this describes. A URL needs it together with [hash], so it is carried here rather than having to
 * be threaded alongside.
 * @property kind whether the value is a blob or a string.
 * @property hash SHA-256 of the value, as lowercase hex. Put it in URLs: ids are recycled after the data they refer to
 * has been deleted, hashes are not, so an id alone is not a safe cache key.
 * @property size the size in bytes (for a string, the length of its UTF-8 encoding).
 * @property custom whatever was provided as metadata to [KlerkAttachedData.prepare], e.g. a content type.
 */
public data class AttachedDataMetadata(
    val id: AttachedDataID,
    val kind: AttachedDataKind,
    val visibility: AttachedDataVisibility,
    val createdAt: Instant,
    val size: Long,
    val hash: String,
    val custom: Map<String, String>,

    /**
     * What the value actually is, as recognised from its first bytes by Klerk — never what a client claimed it was
     * uploading. Null when the bytes match no known format, which is the normal state of affairs for CSV and for
     * anything Klerk does not have a signature for.
     *
     * Recognising a format is not the same as vouching for it: a file can satisfy two formats at once, so this says
     * "plausibly a PNG", never "safe to serve as one". Serve user-supplied values as a download unless you have a
     * specific reason not to.
     */
    val contentType: String? = null,

    /**
     * The names of the [dev.klerkframework.klerk.datatypes.AttachedBlobContainer.preAttachSteps] of [preparedFor]
     * that have run against this value, in the order they ran.
     */
    val completedSteps: List<String> = emptyList(),

    /**
     * The qualified name of the [dev.klerkframework.klerk.datatypes.AttachedBlobContainer] the value was prepared
     * for, or null if it was prepared without one.
     *
     * A name rather than a `KClass`, because it is read back from storage: a value prepared for a container the
     * application has since renamed or removed must still be readable, not a `ClassNotFoundException`.
     */
    val preparedFor: String? = null,
)

/**
 * The arguments given to the rules deciding who may read attached data (see [KlerkAttachedData.get]).
 *
 * Note that these rules are only consulted for [AttachedDataVisibility.Private] data — public data is readable by anyone.
 *
 * @property owner the model that owns the data. A rule can use this to express model-relative policies (e.g. "the
 * actor may read the file if it belongs to a project the actor is a member of").
 */
public data class ArgsForAttachedDataRead<C : KlerkContext, V>(
    val owner: Model<out Any>,
    val context: C,
    val reader: ModelReader<C, V>,
)

/**
 * The arguments given to the rules deciding who may prepare attached data (see [KlerkAttachedData.prepare]).
 *
 * Note that there is no model at this point since the data has not been attached to anything yet. The meaningful
 * checks here are the actor in the [context], the [kind] and the [lease] — a rule can allow uploads in general but
 * restrict who may upload a blob as opposed to a string, or who may keep unclaimed data around for hours. The real
 * gate on *attaching* data to a model is the normal event authorization of the command that claims it.
 */
public data class ArgsForAttachedDataWrite<C : KlerkContext, V>(
    val kind: AttachedDataKind,

    val context: C,
    val reader: ModelReader<C, V>,

    /**
     * How long the value may stay unclaimed. Long leases keep storage occupied by data no model refers to, so this is
     * the place to decide who may ask for one.
     */
    val lease: Duration,
)

/**
 * The arguments given to the rules deciding who may see a job's metadata (see [JobManager.getJob]).
 *
 * The same rules gate cancellation, so allowing an actor to watch a job also lets them stop it.
 *
 * @property job everything Klerk knows about the job, including who scheduled it — see [isOwnedBy] for the common
 * case of "may an actor see their own jobs".
 */
/**
 * What `SpecificationBuilder.jobContextProvider` is given when a job step is about to run.
 *
 * @property actor `SystemIdentity` for a [dev.klerkframework.klerk.job.JobAgent.System] job, or the actor that
 * scheduled the job for a [dev.klerkframework.klerk.job.JobAgent.Scheduler] one. Note that the latter is rebuilt from
 * what was persisted, so an actor identified by a model arrives as a [ModelReferenceIdentity].
 * @property time the current time according to the configured clock. Use it as the context's time so that job steps,
 * and the event log entries of the commands they emit, follow the clock a test controls.
 */
public data class JobContextRequest(
    val actor: ActorIdentity,
    val time: Instant,
    val job: JobInfo,
)

public data class ArgsForJobRead<C : KlerkContext, V>(
    val job: JobInfo,
    val context: C,
    val reader: ModelReader<C, V>,
) {
    /**
     * True if [context]'s actor is the one that scheduled the job.
     *
     * Compares the actor's model id (or external id) rather than the identity object, since the actor that scheduled
     * the job may have been read from storage under a different identity implementation since.
     */
    public fun isOwnedBy(actor: ActorIdentity): Boolean {
        val owner = job.owner
        // ModelIdentity and ModelReferenceIdentity are the same actor, so the id decides whenever there is one.
        if (owner.id != null || actor.id != null) {
            return owner.id == actor.id
        }
        if (owner.externalId != null || actor.externalId != null) {
            return owner.externalId == actor.externalId
        }
        return owner.type == actor.type
    }

    /** True if [context]'s own actor scheduled the job. */
    public fun isOwnedByActor(): Boolean = isOwnedBy(context.actor)
}

/**
 * The EventProducer is used to process events where the subsequent events are dependent on the results of the previous
 * events. The processing happens in a transaction, i.e. if any of the events are rejected, all events will be
 * rejected.
 *
 * When processed, init() is first called and thereafter produceNextEvent() will be called until it returns null.
 *
 * It is important that the EventProducer produces the same events no matter how many times init() and
 * subsequently produceNextEvent() has been called. This means that init() should be idempotent (except for timestamps).
 * It is recommended to have unit tests making sure that the implementation is idempotent.
 */
/*
interface CommandProducer<C:IContext, V> {


    /**
     * Called when it is time to prepare for processing. Can be called many times, so it should be idempotent.
     */
    fun init(): Reader<C, V>.() -> Unit

    /**
     * Produce the next event. Return null when there are no more events.
     * Note that you may not do any reading in this function.
     */
    fun produceNextEvent(previousResults: List<CommandResult.Success<*>>): Command<*>?
}
 */


/**
 * Implemented by the application to carry who/when/how-translated for every read, command, and rule evaluation.
 * See the `context` documentation for a full walkthrough and an example implementation.
 */
public interface KlerkContext {
    /** Who is performing the operation; what authorization and business rules key off of. */
    public val actor: ActorIdentity

    /** Optional free-text stored alongside the event log entry for whatever command uses this context. */
    public val eventLogExtra: String?
    public val translation: Translation

    /** The instant the operation is considered to happen at. Business logic should read time from here, not `Clock.System.now()`. */
    public val time: Instant
}

/** Supplies human-readable text for validation, property, and event names. Implement to support additional languages. */
public interface Translation {
    public val klerk: KlerkTranslation
}

/** Built-in framework-level strings (used by [DefaultKlerkTranslation] unless overridden). */
public interface KlerkTranslation {
    public fun property(property: KProperty1<*, *>): String
    /** A description of [property], e.g. shown as a tooltip, or null if it has none. */
    public fun propertyDescription(property: KProperty1<*, *>): String?
    public fun event(event: EventReference): String
    /** The name of the rule [f], a named function reference, e.g. used as the message when it fails without one. */
    public fun function(f: Function<Any>): String
    public fun mustBeAtLeast(value: Number): String
    public fun mustBeAtMost(value: Number): String
    public fun invalidProperty(propertyName: String, functionName: String, translationInfo: String?): String
    public val mustBeProvided: String
    public fun tooShort(minLength: Int): String
    public fun tooLong(maxLength: Int): String
    public fun tooManyLines(maxLines: Int): String
    public val invalid: String
    public val internalError: String
    public val unauthorized: String
    public val noAllowingRule: String
}

/**
 * The DefaultTranslator can be used when you don't want to translate your application
 */
public object DefaultTranslation : Translation {
    override val klerk: KlerkTranslation = DefaultKlerkTranslation
}

public object DefaultKlerkTranslation : KlerkTranslation {

    override fun property(property: KProperty1<*, *>): String {
        return camelCaseToPretty(property.name)
    }

    override fun propertyDescription(property: KProperty1<*, *>): String? = null

    override fun event(event: EventReference): String {
        return camelCaseToPretty(event.eventName)
    }

    override fun function(f: Function<Any>): String = functionName(f)?.let { camelCaseToPretty(it) } ?: invalid


    override fun invalidProperty(
        propertyName: String,
        functionName: String,
        translationInfo: String?
    ): String {
        return camelCaseToPretty(functionName)
    }

    override val mustBeProvided: String = "Must be provided"
    override fun tooShort(minLength: Int): String = "Must be at least $minLength characters"
    override fun tooLong(maxLength: Int): String = "Must be at most $maxLength characters"
    override fun tooManyLines(maxLines: Int): String = "Must be at most $maxLines lines"
    override fun mustBeAtLeast(value: Number): String = "Must be at least $value"
    override fun mustBeAtMost(value: Number): String = "Must be at most $value"
    override val invalid: String = "Invalid"
    override val internalError: String = "Internal error"
    override val unauthorized: String = "Unauthorized"
    override val noAllowingRule: String = "No policy explicitly allowed the request"

}

/**
 * Describes the validity of a collection of properties (i.e. a class) given that each individual property is valid.
 * E.g. for a class containing two properties x: EvenIntContainer and y: OddIntContainer where x and y are valid,
 * a PropertyCollectionValidity can express that {x, y} is not valid since x > y.
 */
public sealed class PropertyCollectionValidity {
    public data object Valid : PropertyCollectionValidity()
    public class Invalid(
        public val endUserTranslatedMessage: String? = null,
        public val fieldMustBeNull: KProperty0<DataContainer<*>?>? = null,
        public val fieldMustNotBeNull: KProperty0<DataContainer<*>?>? = null
    ) : PropertyCollectionValidity() {
        internal fun toProblem(f: Function<Any>, translation: Translation): InvalidPropertyCollectionProblem =
            toProblem(this.endUserTranslatedMessage ?: translation.klerk.function(f))

        internal fun toProblem(message: String): InvalidPropertyCollectionProblem =
            InvalidPropertyCollectionProblem(
                endUserTranslatedMessage = message,
                fieldsMustBeNull = if (fieldMustBeNull == null) emptySet() else setOf(fieldMustBeNull),
                fieldsMustNotBeNull = if (fieldMustNotBeNull == null) emptySet() else setOf(fieldMustNotBeNull)
            )
    }
}

/**
 * Returns microseconds since 1970.
 * It only works for instants between years -290308 and +294247.
 */
internal fun Instant.to64bitMicroseconds(): Long {
    if (this <= klerkInstantMin) return Long.MIN_VALUE
    if (this >= klerkInstantMax) return Long.MAX_VALUE
    return BigInteger.valueOf(this.epochSeconds).multiply(ONE_MILLION)
        .plus(BigInteger.valueOf(this.nanosecondsOfSecond.toLong()).divide(ONE_THOUSAND))
        .toLong()
}

private val klerkInstantMin = decode64bitMicroseconds(Long.MIN_VALUE)
private val klerkInstantMax = decode64bitMicroseconds(Long.MAX_VALUE)

private val ONE_MILLION = BigInteger.valueOf(1000000)
private val ONE_THOUSAND = BigInteger.valueOf(1000)

internal fun decode64bitMicroseconds(microsecondsSince1970: Long): Instant =
    Instant.fromEpochSeconds(
        Math.floorDiv(microsecondsSince1970, 1_000_000L),
        Math.floorMod(microsecondsSince1970, 1_000_000L) * 1000
    )

/**
 * A packaged extension that contributes specification (managed models, events, rules, ...) to a host application.
 * [mergeSpecification] should return previous augmented with the plugin's own configuration; [start] is called once
 * after [KlerkMeta.start], and [stop] once during [KlerkMeta.stop].
 */
public interface KlerkPlugin<C : KlerkContext, V> {
    public val name: String
    public val description: String
    public fun mergeSpecification(previous: Specification<C, V>): Specification<C, V>

    /** Called once after Klerk has started. Use it to kick off whatever background work the plugin needs. */
    public suspend fun start(klerk: Klerk<C, V>): Unit

    /**
     * Called once when Klerk stops, before Klerk shuts down its own machinery and in reverse plugin order.
     * Override it if the plugin has background work to wind down. Must not block for long.
     */
    public fun stop(): Unit {}
}

/**
 * A [dev.klerkframework.klerk.datatypes.DataContainer] wrapping a [JobId], so that a model can hold a reference to a
 * job it started.
 */
public class JobIdContainer(value: Long) : LongContainer(value) {
    public constructor(id: JobId) : this(id.value.toLong())

    override val min: Long = 0
    override val max: Long = Int.MAX_VALUE.toLong()

    /**
     * The wrapped value as a [JobId].
     *
     * @throws AuthorizationException if the actor that read the model is not allowed to read this property.
     */
    public val jobId: JobId get() = JobId(value.toInt())
}

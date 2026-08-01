package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.StateMachine
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.time.Instant

/**
 * Identifies a single state within a model's state machine, e.g. `s.Book.Published`.
 */
public data class StateId(val modelName: String, val stateName: String) {
    override fun toString(): String = "s.$modelName.$stateName"
    public fun withoutPrefix(): String = toString().substring(2)
}

/**
 * Identifies a [ModelView][dev.klerkframework.klerk.collection.ModelView] within a [ModelViews][dev.klerkframework.klerk.collection.ModelViews] collection, e.g. `c.Book.all`.
 */
public data class CollectionId(val modelName: String, val shortId: String) {
    override fun toString(): String = "c.$modelName.$shortId"

    public companion object {
        /**
         * @throws IllegalArgumentException if [string] is not of the form `c.<modelName>.<shortId>`
         */
        public fun from(string: String): CollectionId {
            val parts = string.split(".")
            require(parts.size == 3) { "CollectionId must contain three parts separated by dots" }
            require(parts.first() == "c") { "CollectionId must start with 'c.'" }
            return CollectionId(parts[1], parts[2])
        }
    }
}

/**
 * Wires a model's props class to its [StateMachine] and [ModelViews]. Registered via
 * `ConfigBuilder.managedModels { model(...) }`; not normally constructed directly.
 */
public data class ManagedModel<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    val kClass: KClass<T>,
    val stateMachine: StateMachine<T, ModelStates, C, V>,
    val collections: ModelViews<T, C>,
)

/**
 * A stored instance: metadata (id, timestamps, current [state]) plus the model's props of type [T].
 * Returned from reads (e.g. [Reader.get]); never constructed directly by application code.
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

public data class EventWithParameters<T : Any>(val eventReference: EventReference, val parameters: EventParameters<T>)

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

    public fun id(): EventId = "$modelName:$eventName"

    override fun toString(): String = id()

    public companion object {
        public fun from(eventId: EventId): EventReference {
            val splitted = eventId.split(":")
            require(splitted.size == 2)
            return EventReference(splitted.first(), splitted.last())
        }
    }
}

/**
 * The visibility level of an event. The higher levels expand on the lower levels, e.g. INTER_STATEMACHINE can be
 * created in all places where STATEMACHINE_INTERNAL is allowed.
 */
public enum class EventVisibility(internal val level: Int) {

    /**
     * Can only be created within the same statemachine.
     */
    STATEMACHINE_INTERNAL(1),

    /**
     * Can be created in any statemachine.
     */
    INTER_STATEMACHINE(2),

    /**
     * Can be created in any statemachine and in application code. This level can be used for events that are triggered
     * by the system, e.g. in a Job.
     */
    SYSTEM(3),

    /**
     * Can be created in any statemachine and in application code.
     */
    CODE(4),

    /**
     * Can be created in any statemachine and in application code. Klerk doesn't differentiate this from CODE, but this
     * level can be used as a signal to other code (e.g., auto-generated UI or API) that it should handle this event.
     */
    EXTERNAL(5)
}

/**
 * Base class of the four event kinds ([VoidEventNoParameters], [VoidEventWithParameters],
 * [InstanceEventNoParameters], [InstanceEventWithParameters]). Application code declares events as `object`s
 * extending one of those four, then registers them in a [StateMachine] with `event(...)` / `onEvent(...)`.
 */
public sealed class Event<T : Any, P>(private val forModel: KClass<T>, public val visibility: EventVisibility) {

    public val id: EventReference
        get() = EventReference(forModel.simpleName!!, name)

    public val name: String
        get() = this::class.simpleName!!

    /*
    It may seem pointless to have contextRules since barely any latency is saved by
    evaluating context rules before getting a Reader (getAvailableEvents will require
    a Reader). But that is not the reason! The point is that you can reuse rules
    in the state machine over different kind of events!
     */
    private var _contextRules: Set<(KlerkContext) -> PropertyCollectionValidity> = emptySet()

    public fun <C : KlerkContext> getContextRules(): Set<(C) -> PropertyCollectionValidity> =
        _contextRules

    internal fun <C : KlerkContext> setContextRules(rules: Set<(C) -> PropertyCollectionValidity>) {
        @Suppress("UNCHECKED_CAST")
        _contextRules = rules as Set<(KlerkContext) -> PropertyCollectionValidity>
    }

    override fun toString(): String = id.toString()

}

public sealed class VoidEvent<T : Any, P>(forModel: KClass<T>, visibility: EventVisibility) :
    Event<T, P>(forModel, visibility) {

    internal var noParamRules: Set<(ArgForVoidEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity> = setOf()

    internal fun <C : KlerkContext, V> getNoParamRulesForVoidEvent() =
        noParamRules as Set<(ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity>

}

public sealed class InstanceEvent<T : Any, P>(forModel: KClass<T>, visibility: EventVisibility) :
    Event<T, P>(forModel, visibility) {

    internal var noParamRules: Set<(ArgForInstanceEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity> = setOf()

    internal fun <C : KlerkContext, V> getNoParamRulesForInstanceEvent() =
        noParamRules as Set<(ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity>
}

/**
 * A void event (creates a new model of type [T]) that takes parameters of type [P] when handled. Declare a
 * handler function `fun create(arg: ArgForVoidEvent<T, P, C, V>): T`.
 */
public abstract class VoidEventWithParameters<T : Any, P : Any>(
    forModel: KClass<T>,
    visibility: EventVisibility,
    public val parametersClass: KClass<P>
) : VoidEvent<T, P>(forModel, visibility) {

    internal var paramRulesForVoidEvent: Set<(ArgForVoidEvent<T, P, *, *>) -> PropertyCollectionValidity> = setOf()
    internal var validRefs: Map<String, ModelView<out Any, *>?> = mapOf()
    internal var validEnums: Map<String, Set<Enum<*>>> = mapOf()

    internal fun <C : KlerkContext, V> getParamRules() =
        paramRulesForVoidEvent as Set<(ArgForVoidEvent<T, P, C, V>) -> PropertyCollectionValidity>

    @Suppress("UNCHECKED_CAST")
    internal fun <C : KlerkContext> getValidRefs(name: String): ModelView<out Any, C>? =
        validRefs[name] as ModelView<out Any, C>?

    internal fun getValidEnums(name: String): Set<Enum<*>>? = validEnums[name]


}

/**
 * A void event (creates a new model of type [T]) that takes no parameters. Declare a handler function
 * `fun create(arg: ArgForVoidEvent<T, Nothing?, C, V>): T`.
 */
public abstract class VoidEventNoParameters<T : Any>(forModel: KClass<T>, visibility: EventVisibility) :
    VoidEvent<T, Nothing?>(forModel, visibility)

/**
 * An instance event (acts on an existing model of type [T]) that takes parameters of type [P] when handled.
 * Declare a handler function `fun update(arg: ArgForInstanceEvent<T, P, C, V>): T`.
 */
public open class InstanceEventWithParameters<T : Any, P : Any>(
    forModel: KClass<T>,
    visibility: EventVisibility,
    public val parametersClass: KClass<P>
) : InstanceEvent<T, P>(forModel, visibility) {

    internal var paramRulesForInstanceEvent: Set<(ArgForInstanceEvent<T, P, *, *>) -> PropertyCollectionValidity> =
        setOf()
    internal var validRefs: Map<String, ModelView<out Any, *>?> = mapOf()
    internal var validEnums: Map<String, Set<Enum<*>>> = mapOf()

    internal fun <C : KlerkContext, V> getParamRules() =
        paramRulesForInstanceEvent as Set<(ArgForInstanceEvent<T, P, C, V>) -> PropertyCollectionValidity>

    @Suppress("UNCHECKED_CAST")
    internal fun <C : KlerkContext> getValidRefs(name: String): ModelView<out Any, C>? =
        validRefs[name] as ModelView<out Any, C>?

    internal fun getValidEnums(name: String): Set<Enum<*>>? = validEnums[name]

}

/**
 * An instance event (acts on an existing model of type [T]) that takes no parameters. Declare a handler function
 * `fun archive(arg: ArgForInstanceEvent<T, Nothing?, C, V>): T`.
 */
public abstract class InstanceEventNoParameters<T : Any>(forModel: KClass<T>, visibility: EventVisibility) :
    InstanceEvent<T, Nothing?>(forModel, visibility)


/**
 * Arguments handed to context-only rules, e.g. rules deciding void events not tied to a model instance.
 */
public data class ArgContextReader<C : KlerkContext, V>(val context: C, val reader: Reader<C, V>)

/**
 * Arguments handed to rules that need to inspect the [command] being processed (e.g. event authorization rules).
 */
public data class ArgCommandContextReader<P, C : KlerkContext, V>(
    val command: Command<out Any, P>,
    val context: C,
    val reader: Reader<C, V>
)

/**
 * Arguments handed to rules that evaluate against an existing [model], e.g. read/authorization rules for instance
 * events.
 */
public data class ArgModelContextReader<C : KlerkContext, V>(
    val model: Model<out Any>,
    val context: C,
    val reader: Reader<C, V>
)

/**
 * Arguments handed to property-level authorization rules deciding whether [property] on [model] may be read.
 */
public data class ArgsForPropertyAuth<C : KlerkContext, V>(
    val property: DataContainer<*>,
    val model: Model<out Any>,
    val context: C,
    val reader: Reader<C, V>,
)

/**
 * @property reader Note that the reader will give you access to the data as it was _before_ the current event was
 * executed. I.e. if the current event has modified data in a previous step, the updated data will __not__ be accessible
 * through the reader.
 */
public data class ArgForVoidEvent<T : Any, P, C : KlerkContext, V>(
    val command: Command<T, P>,
    val context: C,
    val reader: Reader<C, V>,
)

/**
 * @param model The model as it is in the un-committed state. I.e. the model you see may differ from the model as it was
 * before the current processing (of an event or time-trigger).
 */
public data class ArgForInstanceEvent<T : Any, P, C : KlerkContext, V>(
    val model: Model<T>,
    val command: Command<T, P>,
    val context: C,
    val reader: Reader<C, V>
)

/**
 * @param model The model as it is in the un-committed state. I.e. the model you see may differ from the model as it was
 * before the current processing (of an event or time-trigger).
 */
public data class ArgForInstanceNonEvent<T : Any, C : KlerkContext, V>(
    val model: Model<T>,
    val time: Instant,
    val reader: Reader<C, V>
)

public typealias EventId = String

/**
 * Model IDs are represented internally using Int but only the positive part, so the maximum amount of simultaneous models is about
 * 2 billion (we should find a way to use UInt).
 * It is recommended to use a String (base36) externally.
 *
 * Implementation details: We first used UInt, but it seems that there is a problem when making this @JvmInline and
 * value class in combination with ULong and UInt (see KT-69674).
 *
 * NOTE: If you make any change to this: clean build, and verify how a relation is serialized ("value" may appear). (If
 * you use IntelliJ's database tool, double check that you actually see the difference, you may have to delete/refresh)
 */
@JvmInline
public value class ModelID<T : Any>(public val value: Int) {

    override fun toString(): String = value.toString()

    public companion object {
    }
}

/**
 * A reference to a large blob attached to a model (see [KlerkLargeData]).
 *
 * Obtain one from [KlerkLargeData.prepare] and store it in a model property. The data is owned exclusively by the
 * first model that references it in a committed command, and is deleted when no property of that model refers to it
 * any more.
 *
 * Implementation details: see the note on [ModelID] regarding @JvmInline and serialization.
 */
@JvmInline
public value class LargeBlobID(internal val id: Int) {
    override fun toString(): String = id.toString()
}

/**
 * A reference to a large string attached to a model (see [KlerkLargeData]).
 *
 * Obtain one from [KlerkLargeData.prepare] and store it in a model property. The data is owned exclusively by the
 * first model that references it in a committed command, and is deleted when no property of that model refers to it
 * any more.
 *
 * Implementation details: see the note on [ModelID] regarding @JvmInline and serialization.
 */
@JvmInline
public value class LargeStringID(internal val id: Int) {
    override fun toString(): String = id.toString()
}

/**
 * The arguments given to the rules deciding who may read attached data (see [KlerkLargeData.get]).
 *
 * @property owner the model that owns the data. A rule can use this to express model-relative policies (e.g. "the
 * actor may read the file if it belongs to a project the actor is a member of").
 * @property authKey the key that was provided when the data was prepared (see [KlerkLargeData.prepare]). It is frozen
 * at upload time and never changes.
 */
public data class ArgsForLargeDataRead<C : KlerkContext, V>(
    val owner: Model<out Any>,
    val authKey: String?,
    val context: C,
    val reader: Reader<C, V>,
)

/**
 * The arguments given to the rules deciding who may prepare attached data (see [KlerkLargeData.prepare]).
 *
 * Note that there is no model at this point since the data has not been attached to anything yet, and that the
 * [authKey] is chosen by the caller. The meaningful check here is the actor in the [context]. The real gate on
 * *attaching* data to a model is the normal event authorization of the command that claims it.
 */
public data class ArgsForLargeDataWrite<C : KlerkContext, V>(
    val authKey: String?,
    val context: C,
    val reader: Reader<C, V>,
)

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

    /** Optional free-text stored alongside the audit log entry for whatever command uses this context. */
    public val auditExtra: String?
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
    public fun propertyDescription(property: String): String?
    public fun event(event: EventReference): String
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

    override fun propertyDescription(property: String): String? = null

    override fun event(event: EventReference): String {
        return camelCaseToPretty(event.eventName)
    }

    override fun function(f: Function<Any>): String {
        val name = (f as KFunction<*>).name
        return camelCaseToPretty(name)
    }


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
        public fun toProblem(f: Function<Any>, translation: Translation): InvalidPropertyCollectionProblem {
            return InvalidPropertyCollectionProblem(
                endUserTranslatedMessage = this.endUserTranslatedMessage ?: translation.klerk.function(f),
                fieldsMustBeNull = if (fieldMustBeNull == null) emptySet() else setOf(fieldMustBeNull),
                fieldsMustNotBeNull = if (fieldMustNotBeNull == null) emptySet() else setOf(fieldMustNotBeNull)
            )
        }

        public fun toProblem(): InvalidPropertyCollectionProblem {  // TODO: if possible, remove this function
            return InvalidPropertyCollectionProblem(
                endUserTranslatedMessage = this.endUserTranslatedMessage ?: "? other toProblem",
            )
        }
    }
}

/**
 * Returns microseconds since 1970.
 * It only works for instants between years -290308 and +294247.
 */
public fun Instant.to64bitMicroseconds(): Long {
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

public fun decode64bitMicroseconds(microsecondsSince1970: Long): Instant {
    // can be improved, e.g. this cannot handle Instant.EPOCH + 1 nanosecond
    if (microsecondsSince1970 == 0L) {
        return Instant.fromEpochSeconds(0)
    }
    val str = microsecondsSince1970.toString()
    val epochSeconds = str.substring(0, str.length - 6).toLong()
    val micros = str.substring(str.length - 6).toLong()
    return Instant.fromEpochSeconds(epochSeconds, micros * 1000)
}

/**
 * A packaged extension that contributes config (managed models, events, rules, ...) to a host application.
 * [mergeConfig] should return [previous] augmented with the plugin's own configuration; [start] is called once
 * after [KlerkMeta.start].
 */
public interface KlerkPlugin<C : KlerkContext, V> {
    public val name: String
    public val description: String
    public fun mergeConfig(previous: Config<C, V>): Config<C, V>
    public suspend fun start(klerk: Klerk<C, V>): Unit
}

/** A [dev.klerkframework.klerk.datatypes.DataContainer] wrapping a [JobId][dev.klerkframework.klerk.job.JobId]'s underlying value. */
public class JobIdContainer(value: Long) : LongContainer(value) {
    override val min: Long = 0
    override val max: Long = Long.MAX_VALUE
}

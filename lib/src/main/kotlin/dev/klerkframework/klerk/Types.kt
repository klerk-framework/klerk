package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.StateMachine
import kotlinx.datetime.Instant
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.time.Duration

public data class StateId(val modelName: String, val stateName: String) {
    override fun toString(): String = "s.$modelName.$stateName"
    public fun withoutPrefix(): String = toString().substring(2)
}

public data class CollectionId(val modelName: String, val shortId: String) {
    override fun toString(): String = "c.$modelName.$shortId"

    public companion object {
        public fun from(string: String): CollectionId {
            val parts = string.split(".")
            require(parts.size == 3) { "CollectionId must contain three parts separated by dots" }
            require(parts.first() == "c") { "CollectionId must start with 'c.'" }
            return CollectionId(parts[1], parts[2])
        }
    }
}

public data class ManagedModel<T : Any, ModelStates : Enum<*>, C : KlerkContext, V>(
    val kClass: KClass<T>,
    val stateMachine: StateMachine<T, ModelStates, C, V>,
    val collections: ModelCollections<T, C>,
)

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

public sealed class Event<T : Any, P>(private val forModel: KClass<T>, internal val isExternal: Boolean) {

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

public sealed class VoidEvent<T : Any, P>(forModel: KClass<T>, isExternal: Boolean) :
    Event<T, P>(forModel, isExternal) {

    internal var noParamRules: Set<(ArgForVoidEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity> = setOf()

    internal fun <C : KlerkContext, V> getNoParamRulesForVoidEvent() =
        noParamRules as Set<(ArgForVoidEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity>

}

public sealed class InstanceEvent<T : Any, P>(forModel: KClass<T>, isExternal: Boolean) :
    Event<T, P>(forModel, isExternal) {

    internal var noParamRules: Set<(ArgForInstanceEvent<T, Nothing?, *, *>) -> PropertyCollectionValidity> = setOf()

    internal fun <C : KlerkContext, V> getNoParamRulesForInstanceEvent() =
        noParamRules as Set<(ArgForInstanceEvent<T, Nothing?, C, V>) -> PropertyCollectionValidity>
}

public abstract class VoidEventWithParameters<T : Any, P : Any>(
    forModel: KClass<T>,
    isExternal: Boolean,
    public val parametersClass: KClass<P>
) : VoidEvent<T, P>(forModel, isExternal) {

    internal var paramRulesForVoidEvent: Set<(ArgForVoidEvent<T, P, *, *>) -> PropertyCollectionValidity> = setOf()
    internal var validRefs: Map<String, ModelCollection<out Any, *>?> = mapOf()

    internal fun <C : KlerkContext, V> getParamRules() =
        paramRulesForVoidEvent as Set<(ArgForVoidEvent<T, P, C, V>) -> PropertyCollectionValidity>

    @Suppress("UNCHECKED_CAST")
    internal fun <C : KlerkContext> getValidRefs(name: String): ModelCollection<out Any, C>? =
        validRefs[name] as ModelCollection<out Any, C>?


}

public abstract class VoidEventNoParameters<T : Any>(forModel: KClass<T>, isExternal: Boolean) :
    VoidEvent<T, Nothing?>(forModel, isExternal)

public open class InstanceEventWithParameters<T : Any, P : Any>(
    forModel: KClass<T>,
    isExternal: Boolean,
    public val parametersClass: KClass<P>
) : InstanceEvent<T, P>(forModel, isExternal) {

    internal var paramRulesForInstanceEvent: Set<(ArgForInstanceEvent<T, P, *, *>) -> PropertyCollectionValidity> = setOf()
    internal var validRefs: Map<String, ModelCollection<out Any, *>?> = mapOf()

    internal fun <C : KlerkContext, V> getParamRules() =
        paramRulesForInstanceEvent as Set<(ArgForInstanceEvent<T, P, C, V>) -> PropertyCollectionValidity>

    @Suppress("UNCHECKED_CAST")
    internal fun <C : KlerkContext> getValidRefs(name: String): ModelCollection<out Any, C>? =
        validRefs[name] as ModelCollection<out Any, C>?

}

public abstract class InstanceEventNoParameters<T : Any>(forModel: KClass<T>, isExternal: Boolean) :
    InstanceEvent<T, Nothing?>(forModel, isExternal)


public data class ArgContextReader<C : KlerkContext, V>(val context: C, val reader: Reader<C, V>)
public data class ArgCommandContextReader<P, C : KlerkContext, V>(
    val command: Command<out Any, P>,
    val context: C,
    val reader: Reader<C, V>
)

public data class ArgModelContextReader<C : KlerkContext, V>(
    val model: Model<out Any>,
    val context: C,
    val reader: Reader<C, V>
)

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
public value class ModelID<T : Any>(private val value: Int) {

    override fun toString(): String = value.toString(36)

    public fun toInt(): Int = value

    public companion object {
        public fun <T : Any> from(value: String): ModelID<T> = ModelID(value.toInt(36))
    }
}

public data class KeyValueID<T : Any>(public val id: UInt, public val type: KClass<T>)

public class BlobToken(public val id: UInt)

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


public interface KlerkContext {
    public val actor: ActorIdentity
    public val auditExtra: String?
    public val translation: Translation
    public val time: Instant
}

public interface Translation {
    public val klerk: KlerkTranslation
}

public interface KlerkTranslation {
    public fun property(property: KProperty1<*, *>): String
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

public interface KlerkPlugin<C : KlerkContext, V> {
    public val name: String
    public val description: String
    public fun mergeConfig(previous: Config<C, V>): Config<C, V>
    public fun start(klerk: Klerk<C, V>): Unit
}

public class JobIdContainer(value: Long) : LongContainer(value) {
    override val min: Long = 0
    override val max: Long = Long.MAX_VALUE
}

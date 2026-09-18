package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.InstanceEvent
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEvent
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.decode64bitMicroseconds
import dev.klerkframework.klerk.defaultDebugOptions
import dev.klerkframework.klerk.log.LogLevel
import dev.klerkframework.klerk.misc.decodeBase64String
import dev.klerkframework.klerk.misc.encodeBase64
import dev.klerkframework.klerk.misc.getCurrentInstant
import dev.klerkframework.klerk.to64bitMicroseconds
import kotlin.time.Instant

/**
 * Describes a single request to run [event][Event], passed to `Klerk.handle`.
 *
 * Build it with one of the `Command(...)` factories, which take exactly the arguments the event kind needs:
 *
 * ```kotlin
 * Command(CreateBook, params)          // void event with parameters
 * Command(ImportBooks)                 // void event without parameters
 * Command(UpdateBook, bookId, params)  // instance event with parameters
 * Command(PublishBook, bookId)         // instance event without parameters
 * ```
 */
@ConsistentCopyVisibility
public data class Command<T : Any, P> internal constructor(
    /** The event to run. */
    public val event: Event<T, P>,
    /** The model this event applies to: non-null for instance events, null for void events. */
    public val model: ModelID<T>?,
    /** The event's parameter object, or `null` if the event declares no parameters. */
    public val params: P,
) {
    public companion object {
        /**
         * Builds a command when the event kind is only known at runtime, as in generic tooling (forms, GraphQL, MCP).
         * Application code should use the `Command(...)` factories instead, which check the shape at compile time.
         *
         * @throws IllegalArgumentException if [model] is null for an instance event, or non-null for a void event.
         */
        public fun <T : Any, P> dynamic(event: Event<T, P>, model: ModelID<T>?, params: P): Command<T, P> {
            when (event) {
                is VoidEvent -> require(model == null) { "$event is a void event, so it takes no model" }
                is InstanceEvent -> require(model != null) { "$event is an instance event, so it needs a model" }
            }
            return Command(event, model, params)
        }
    }
}

/** A command running the void event [event] with [params]. */
public fun <T : Any, P : Any> Command(event: VoidEventWithParameters<T, P>, params: P): Command<T, P> =
    Command.dynamic(event, null, params)

/** A command running the parameterless void event [event]. */
public fun <T : Any> Command(event: VoidEventNoParameters<T>): Command<T, Nothing?> = Command.dynamic(event, null, null)

/** A command running the instance event [event] on [model], with [params]. */
public fun <T : Any, P : Any> Command(
    event: InstanceEventWithParameters<T, P>,
    model: ModelID<T>,
    params: P,
): Command<T, P> = Command.dynamic(event, model, params)

/** A command running the parameterless instance event [event] on [model]. */
public fun <T : Any> Command(event: InstanceEventNoParameters<T>, model: ModelID<T>): Command<T, Nothing?> =
    Command.dynamic(event, model, null)

/** How `Klerk.handle` processes a command. */
public data class ProcessingOptions(
    /**
     * Ensures idempotency: a token can be used to successfully process a command only once, and any later reuse fails
     * with [dev.klerkframework.klerk.KlerkErrorCode.CommandTokenAlreadyUsed]. If created with
     * [CommandToken.requireUnmodifiedModel]/[CommandToken.requireUnmodifiedModels], the command also fails with
     * [dev.klerkframework.klerk.KlerkErrorCode.ModelModifiedSinceTokenCreation] if any of the referenced models were
     * modified after the token was created (optimistic concurrency).
     */
    public val token: CommandToken = CommandToken.simple(),
    /**
     * If true, all rules are evaluated and a [dev.klerkframework.klerk.CommandResult] is produced as normal, but no
     * state is changed and no effects (jobs, subscriptions, persistence) are triggered.
     */
    public val dryRun: Boolean = false,
    /** Extra logging for this command, per category. */
    public val debugOptions: Map<DebugOption, LogLevel> = defaultDebugOptions,
)

/** Categories of extra logging that can be requested per-command via [ProcessingOptions.debugOptions]. */
public enum class DebugOption {
    /** The steps the command goes through. */
    Sequence,

    /** Everything that does not fit the other categories. */
    Misc,

    /** The resulting [dev.klerkframework.klerk.CommandResult]. */
    Result,
}

/**
 * An opaque, serializable idempotency/concurrency token for [ProcessingOptions.token].
 *
 * Construct via the companion factory functions rather than directly. The [toString] output is a compact
 * base64 encoding that can be sent to a client and round-tripped back through [parse] (e.g. to let a client hold a
 * token across a request/response cycle before submitting the actual command).
 */
public class CommandToken private constructor(internal val time: Instant, internal val models: Set<ModelID<out Any>>) {

    override fun toString(): String = "t=${time.to64bitMicroseconds()}:m=${models.joinToString(",")}".encodeBase64()

    override fun equals(other: Any?): Boolean = other is CommandToken && other.time == time && other.models == models

    override fun hashCode(): Int = 31 * time.hashCode() + models.hashCode()

    public companion object {
        /** A token that only guards against being reused (no optimistic-concurrency check). */
        public fun simple(): CommandToken = CommandToken(getCurrentInstant(), emptySet())

        /** A token that additionally fails the command if [id] was modified after the token was created. */
        public fun requireUnmodifiedModel(id: ModelID<out Any>): CommandToken =
            CommandToken(getCurrentInstant(), setOf(id))

        /**
         * A token that additionally fails the command if any model in [ids] was modified after the token was created.
         */
        public fun requireUnmodifiedModels(ids: Set<ModelID<out Any>>): CommandToken =
            CommandToken(getCurrentInstant(), ids)

        /**
         * Parses a token previously produced by [CommandToken.toString].
         * @throws IllegalArgumentException if [value] is not a validly encoded token.
         */
        public fun parse(value: String): CommandToken {
            val fields = value.decodeBase64String().split(":").associate { field ->
                val keyValue = field.split("=")
                require(keyValue.size == 2)
                keyValue.first() to keyValue.last()
            }
            val time = decode64bitMicroseconds(requireNotNull(fields["t"]).toLong())
            val models = requireNotNull(fields["m"]).let { ids ->
                if (ids.isEmpty()) emptySet() else ids.split(",").map { ModelID<Any>(it.toInt()) }.toSet()
            }
            return CommandToken(time, models)
        }

        /** The token in [value], or null if it is not one. */
        public fun parseOrNull(value: String): CommandToken? = runCatching { parse(value) }.getOrNull()
    }
}

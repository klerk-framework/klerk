package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.log.LogLevel
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.decodeBase64String
import dev.klerkframework.klerk.misc.encodeBase64
import dev.klerkframework.klerk.misc.getCurrentInstant
import kotlin.time.Instant

/**
 * Describes a single request to run [event][Event], passed to `Klerk.handle`.
 *
 * @param model the model (if any) on which this event should apply. Must be non-null for instance events and null
 * for void events.
 * @param params the event's parameter object, or `Nothing?` (pass `null`) if the event declares no parameters.
 */
public data class Command<T : Any, P>(
    public val event: Event<T, P>,
    public val model: ModelID<T>?,
    public val params: P,
)

/**
 * @param token ensures idempotency: a given token can be used to successfully process a command only once: any
 * later reuse fails with [dev.klerkframework.klerk.KlerkErrorCode.CommandTokenAlreadyUsed]. If created with
 * [CommandToken.requireUnmodifiedModel]/[CommandToken.requireUnmodifiedModels], the command also fails with
 * [dev.klerkframework.klerk.KlerkErrorCode.ModelModifiedSinceTokenCreation] if any of the referenced models were
 * modified after the token was created (optimistic concurrency).
 * @param dryRun if true, all rules are evaluated and a [dev.klerkframework.klerk.CommandResult] is produced as
 * normal, but no state is actually changed and no effects (jobs, subscriptions, persistence) are triggered.
 */
public data class ProcessingOptions(
    public val token: CommandToken,
    public val dryRun: Boolean = false,
    public val debugOptions: Map<DebugOptions, LogLevel> = defaultDebugOptions
)

/** Categories of extra logging that can be requested per-command via [ProcessingOptions.debugOptions]. */
public enum class DebugOptions {
    Sequence,
    Misc,
    Result
}

/**
 * An opaque, serializable idempotency/concurrency token for [ProcessingOptions.token].
 *
 * Construct via the companion factory functions rather than directly. The [toString] output is a compact
 * base64 encoding that can be sent to a client and round-tripped back through [from] (e.g. to let a client hold a
 * token across a request/response cycle before submitting the actual command).
 */
public class CommandToken private constructor(
    internal val time: Instant,
    internal val models: Set<ModelID<out Any>>,
) {

    public companion object {
        /** A token that only guards against being reused (no optimistic-concurrency check). */
        public fun simple(): CommandToken = CommandToken(getCurrentInstant(), emptySet())

        /** A token that additionally fails the command if [id] was modified after the token was created. */
        public fun requireUnmodifiedModel(id: ModelID<out Any>): CommandToken =
            CommandToken(getCurrentInstant(), setOf(id))

        /** A token that additionally fails the command if any model in [ids] was modified after the token was created. */
        public fun requireUnmodifiedModels(ids: Set<ModelID<out Any>>): CommandToken =
            CommandToken(getCurrentInstant(), ids)

        /**
         * Parses a token previously produced by [CommandToken.toString].
         * @throws IllegalArgumentException if [string] is not a validly encoded token.
         */
        public fun from(string: String): CommandToken {
            var time: Instant? = null
            var models: Set<ModelID<Any>>? = null
            string.decodeBase64String()
                .split(":")
                .forEach { keyValueString ->
                    val keyValueList = keyValueString.split("=")
                    require(keyValueList.size == 2)
                    val key = keyValueList.first()
                    val value = keyValueList.last()
                    if (key == "t") {
                        time = decode64bitMicroseconds(value.toLong())
                    }
                    if (key == "m") {
                        models = if (value.isEmpty()) emptySet() else
                            value.split(",").map { ModelID<Any>(it.toInt()) }.toSet()
                    }
                }
            return CommandToken(requireNotNull(time), requireNotNull(models))
        }
    }

    override fun toString(): String =
        "t=${time.to64bitMicroseconds()}:m=${models.joinToString(",")}".encodeBase64()

    override fun equals(other: Any?): Boolean =
        other is CommandToken && other.time == time && other.models == models

    override fun hashCode(): Int = 31 * time.hashCode() + models.hashCode()
}

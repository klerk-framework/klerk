package dev.klerkframework.klerk

import kotlin.time.Instant

/**
 * Implemented by the application to carry who/when/how-translated for every read, command, and rule evaluation.
 * See the `context` documentation for a full walkthrough and an example implementation.
 */
public interface KlerkContext {
    /** Who is performing the operation; what authorization and business rules key off of. */
    public val actor: ActorIdentity

    /** Optional free-text stored alongside the event log entry for whatever command uses this context. */
    public val eventLogExtra: String?
    /** The language that end-user messages, such as validation problems, are produced in. */
    public val translation: Translation

    /**
     * The instant the operation is considered to happen at. Business logic should read time from here, not
     * `Clock.System.now()`.
     */
    public val time: Instant
}

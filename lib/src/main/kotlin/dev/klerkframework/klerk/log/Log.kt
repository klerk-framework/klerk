package dev.klerkframework.klerk.log

import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * What has happened in the application recently, as [dev.klerkframework.klerk.Klerk.activityLog]. Kept in memory only.
 */
public interface ActivityLog {

    /**
     * Adds an entry to the activity log. Subscribers will be informed about the entry.
     */
    public fun add(entry: LogEntry)

    /**
     * A snapshot of the buffered log entries (capped at ~1000 entries / 1 day, whichever is smaller). Does not include
     * read events. The access itself is recorded in the log, which is what [context] is used for.
     */
    public fun entries(context: KlerkContext): List<LogEntry>

    /**
     * Subscribes to log events. Note that events related to reading of data are excluded. If you need those events,
     * use [subscribeToReads].
     */
    public fun subscribe(): SharedFlow<LogEntry>

    /**
     * Subscribes to read events. Note that you must handle the events efficiently as there can be a huge amount of
     * read events in a system.
     * @see subscribe
     */
    public fun subscribeToReads(): SharedFlow<LogEntry>

}

internal class ActivityLogImpl : ActivityLog {

    private val content: MutableList<LogEntry> = mutableListOf()
    private val maxItems = 1000
    private val logEntryFlow: MutableSharedFlow<LogEntry> = MutableSharedFlow(replay = 0, extraBufferCapacity = 1000)
    private val logEntryReadFlow: MutableSharedFlow<LogEntry> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 1000000)

    override fun add(entry: LogEntry) {
        synchronized(content) {
            if (content.size > maxItems) {
                content.removeFirst()
            }
            if (content.firstOrNull()?.let { it.time < Clock.System.now().minus(1.days) } == true) {
                content.removeFirst()
            }
            content.add(entry)
        }
        if (!logEntryFlow.tryEmit(entry)) {
            logger.error { "Could not emit to ActivityLog" }
        }
    }

    override fun entries(context: KlerkContext): List<LogEntry> {
        add(LogAccessedActivityLog(context))
        return synchronized(content) { content.toList() }
    }

    override fun subscribe(): SharedFlow<LogEntry> = logEntryFlow

    override fun subscribeToReads(): SharedFlow<LogEntry> = logEntryReadFlow

    /** Records that [models] were read, emitting one [LogReadModel] entry per model to [subscribeToReads]. */
    internal fun addReads(models: List<Model<*>>, context: KlerkContext) {
        for (model in models) {
            logEntryReadFlow.tryEmit(LogReadModel(model, context))
        }
    }

}

/** Which part of the system produced a [LogEntry]. */
public enum class MajorSource() {
    Core, Plugin, Application,
}

/** Where a [LogEntry] came from: a [major] category plus an optional free-text [minor] detail (e.g. a plugin name). */
public class LogSource(public val major: MajorSource, public val minor: String? = null) {
    override fun toString(): String = "${major.name}: $minor"
}

/**
 * A single named/typed value attached to a [LogEntry], substitutable into its `headingTemplate`/`contentTemplate`
 * via `{name}` placeholders (see [LogEntry.headingTemplate]).
 */
public class Fact(
    /** The kind of value. */
    public val type: FactType,
    /** The name used for the `{name}` placeholder. */
    public val name: String,
    /** The value, as text. */
    public val value: String,
    /** The action the fact describes, if any. */
    public val verb: FactVerb? = null,
)

/** The kind of value a [Fact] carries. */
public enum class FactType {
    Custom, // for Application and Plugins
    Duration,
    Command,
    Result,
    ModelID,
    JobID,
    RuleID,
}

/** The action a [Fact] describes, when applicable. */
public enum class FactVerb {
    Created,
    Updated,
    Deleted,
    Transitioned,
    SessionCreated,
    SessionDeleted,
    ViolatedAuthorizationRule,
    Issued,
    Read,
}

/**
 * One entry in [ActivityLog]. Implement to define a new kind of loggable event (core Klerk, a plugin, or the
 * application).
 */
public interface LogEntry {
    /** When it happened. */
    public val time: Instant
    /** Who did it, if anyone. */
    public val actor: dev.klerkframework.klerk.ActorIdentity?
    /** Which part of the system produced the entry. */
    public val source: LogSource

    /**
     * A short machine-readable name for this kind of entry, e.g. for filtering. Distinct from a Klerk
     * [dev.klerkframework.klerk.Event].
     */
    public val kind: String

    /**
     * The LogEntry may be rendered in (at least) two ways:
     * 1. a simple string (e.g. for stdout)
     * 2. in a web UI: here we want an HTML representation, preferably with clickable links to related item.
     * To facilitate both these use cases, a template is used from which a String and HTML can be generated.
     * The template is a String which may contain a names in brackets, which refers to facts or actor. Example:
     * ```
     * "The model {deletedModel} was deleted by {actor}"
     * ```
     * When combining this template with facts, we can generate a log message:
     * ```
     * "The model Author(id: 123) was deleted by User(id: 456)"
     * ```
     * A web UI can generate HTML:
     * ```HTML
     * <p>The model <a href="/author/123">Author(id: 123)</a> was deleted by <a href="/user/456">User(id: 456)</a></p>
     * ```
     */
    public val headingTemplate: String

    /** Like [headingTemplate], but for a longer, optional body. */
    public val contentTemplate: String?

    /** The values substituted into [headingTemplate]/[contentTemplate]'s `{name}` placeholders. */
    public val facts: List<Fact>

    /**
     * [headingTemplate] with each `{name}` replaced by the value of the fact with that name, and `{actor}` by [actor].
     * Placeholders that match neither are left as they are.
     */
    public val heading: String get() = renderLogTemplate(headingTemplate, this)

    /** [contentTemplate], rendered like [heading]. */
    public val content: String? get() = contentTemplate?.let { renderLogTemplate(it, this) }
}

private val logPlaceholder = Regex("""\{(\w+)}""")

internal fun renderLogTemplate(template: String, entry: LogEntry): String =
    logPlaceholder.replace(template) { match ->
        val name = match.groupValues[1]
        entry.facts.firstOrNull { it.name == name }?.value
            ?: entry.actor?.takeIf { name == "actor" }?.toString()
            ?: match.value
    }

/** How serious a log line is. Klerk's own mapping to the underlying logging framework is an implementation detail. */
public enum class LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

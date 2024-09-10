package dev.klerkframework.klerk.log

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

public interface KlerkLog {

    /**
     * Adds an entry to the Klerk log. Subscribers will be informed about the entry.
     */
    public fun add(entry: LogEntry): Unit

    public fun entries(context: KlerkContext): List<LogEntry>

    /**
     * Subscribes to log events. Note that events related to reading of data are excluded. If you need those events,
     * use [subscribeToReads].
     */
    public fun subscribe(context: KlerkContext): SharedFlow<LogEntry>

    /**
     * Subscribes to read events. Note that you must handle the events efficiently as there can be a huge amount of
     * read events in a system.
     * @see subscribe
     */
    public fun subscribeToReads(context: KlerkContext): SharedFlow<LogEntry>

    public fun addReads(models: List<Model<*>>, context: KlerkContext)

}

internal class KlerkLogImpl : KlerkLog {

    private val content: MutableList<LogEntry> = mutableListOf()
    private val maxItems = 1000
    private val logEntryFlow: MutableSharedFlow<LogEntry> = MutableSharedFlow(replay = 0, extraBufferCapacity = 1000)
    private val logEntryReadFlow: MutableSharedFlow<LogEntry> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 1000000)

    override fun add(entry: LogEntry) {
        if (content.size > maxItems) {
            content.removeFirst()
        }
        if (content.firstOrNull()?.let { it.time < Clock.System.now().minus(1.days) } == true) {
            content.removeFirst()
        }
        content.add(entry)
        if (!logEntryFlow.tryEmit(entry)) {
            logger.error { "Could not emit to KlerkLog" }
        }
    }

    override fun entries(context: KlerkContext): List<LogEntry> {
        add(LogAccessedKlerkLog(context))
        return content
    }

    override fun subscribe(context: KlerkContext): SharedFlow<LogEntry> {
        return logEntryFlow
    }

    override fun subscribeToReads(context: KlerkContext): SharedFlow<LogEntry> {
        return logEntryReadFlow
    }

    override fun addReads(models: List<Model<*>>, context: KlerkContext) {
        models.forEach {
            logEntryReadFlow.tryEmit(LogReadModel(it, context))
        }
    }

}

public enum class MajorSource() {
    Core, Plugin, Application,
}

public class LogSource(internal val major: MajorSource, internal val minor: String? = null) {
    override fun toString(): String = "${major.name}: $minor"
}

/**
 *
 */
public class Fact(public val type: FactType, public val name: String, public val value: String, public val verb: FactVerb? = null)

public enum class FactType {
    Custom, // for Application and Plugins
    Duration,
    Command,
    Result,
    ModelID,
    JobID,
    RuleID,
}

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

public interface LogEntry {
    public val time: Instant
    public val actor: dev.klerkframework.klerk.ActorIdentity?
    public val source: LogSource
    public val logEventName: String       // would use the term 'event' if not that term already was taken

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
    public val contentTemplate: String?
    public val facts: List<Fact>

    public fun getHeading(): String = headingTemplate
    public fun getContent(): String? = contentTemplate
}

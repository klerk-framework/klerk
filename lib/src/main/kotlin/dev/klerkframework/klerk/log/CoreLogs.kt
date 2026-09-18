package dev.klerkframework.klerk.log

import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.log.LogSourceMinor.ActivityLog
import dev.klerkframework.klerk.log.LogSourceMinor.Event
import dev.klerkframework.klerk.log.LogSourceMinor.Meta
import dev.klerkframework.klerk.log.LogSourceMinor.Read
import dev.klerkframework.klerk.misc.getCurrentInstant
import kotlin.time.Duration
import kotlin.time.Instant

internal abstract class CoreLogEntry(minor: LogSourceMinor, val context: KlerkContext?) : LogEntry {
    override val time: Instant = context?.time ?: getCurrentInstant()
    override val actor: dev.klerkframework.klerk.ActorIdentity? = context?.actor
    override val source: LogSource = LogSource(MajorSource.Core, minor.name)
    override val kind: String = requireNotNull(this::class.simpleName)
    override val contentTemplate: String? = null
}

internal enum class LogSourceMinor {
    Meta,
    Event,
    Read,
    ActivityLog,
}

internal class LogKlerkStarted(startupTime: Duration) : CoreLogEntry(Meta, null) {
    override val headingTemplate = "Klerk started in ${startupTime.inWholeSeconds} s."
    override val facts: List<Fact> by lazy {
        listOf(Fact(FactType.Duration, "startupTime", startupTime.toIsoString()))
    }
}

internal class LogKlerkStopped : CoreLogEntry(Meta, null) {
    override val headingTemplate = "Klerk stopped"
    override val facts: List<Fact> = emptyList()
}

internal class LogAccessedActivityLog(context: KlerkContext) : CoreLogEntry(ActivityLog, context) {
    override val headingTemplate = "Activity log was read"
    override val facts: List<Fact> = emptyList()
}

internal class LogCommandSucceeded<C : KlerkContext>(
    command: Command<out Any, *>,
    context: C,
    result: CommandResult.Success<out Any>,
) : CoreLogEntry(Event, context) {
    override val headingTemplate = "Command ${command.event} was successful."

    override val facts: List<Fact> by lazy {
        val factsList = mutableListOf(
            Fact(
                type = FactType.Command,
                name = "command",
                value = command.event.toString(),
                verb = FactVerb.Issued,
            ),
            Fact(
                type = FactType.Result,
                name = "result",
                value = "Success",
            ),
        )
        for (model in result.createdModels) {
            factsList.add(
                Fact(
                    type = FactType.ModelID,
                    name = "created",
                    value = model.toString(),
                    verb = FactVerb.Created,
                ),
            )
        }
        factsList
    }
}

internal class LogReadModel(model: Model<*>, context: KlerkContext) : CoreLogEntry(Read, context) {
    override val headingTemplate = "Model ${model.id} was read by ${context.actor}"
    override val facts: List<Fact> by lazy {
        listOf(Fact(FactType.ModelID, "model", model.id.toString()))
    }
}

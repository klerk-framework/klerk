package dev.klerkframework.klerk.log

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.log.LogSourceMinor.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

internal abstract class CoreLogEntry(minor: LogSourceMinor, val context: KlerkContext?) : LogEntry {
    override val time: Instant = context?.time ?: Clock.System.now()
    override val actor: dev.klerkframework.klerk.ActorIdentity? = context?.actor
    override val source: LogSource = LogSource(MajorSource.Core, minor.name)
    override val logEventName: String = requireNotNull(this::class.simpleName)
    override val contentTemplate: String? = null
}

internal enum class LogSourceMinor {
    Meta,
    Event,
    Read,
    KlerkLog
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

internal class LogAccessedKlerkLog(context: KlerkContext) : CoreLogEntry(KlerkLog, context) {
    override val headingTemplate = "Klerk log was read"
    override val facts: List<Fact> = emptyList()
}

internal class LogCommandSucceeded<C : KlerkContext, V>(
    command: Command<out Any, *>,
    context: C,
    result: _root_ide_package_.dev.klerkframework.klerk.CommandResult.Success<out Any, C, V>,
) : CoreLogEntry(Event, context) {
    override val headingTemplate = "Command ${command.event} was successful."

    override val facts: List<Fact> by lazy {
        val factsList = mutableListOf(
            Fact(
                type = FactType.Command,
                name = "command",
                value = command.event.toString(),
                verb = FactVerb.Issued
            ),
            Fact(
                type = FactType.Result,
                name = "result",
                value = "Success",
            )
        )
        result.createdModels.forEach {
            factsList.add(
                Fact(
                    type = FactType.ModelID,
                    name = "created",
                    value = it.toString(),
                    verb = FactVerb.Created
                )
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

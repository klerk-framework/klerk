package dev.klerkframework.klerk

import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.MutableClock
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class Mark(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

data class Marked(val byTrigger: Mark, val byCommand: Mark)

data class SetByCommandParams(val byCommand: Mark)

object CreateMarked : VoidEventNoParameters<Marked>(External)

object SetByCommand : InstanceEventWithParameters<Marked, SetByCommandParams>(External)

enum class MarkedStates { Fresh, Old }

/** Blocks the trigger's update once [armed] is set, so that a test can act while the trigger is being processed. */
object TriggerGate {
    @Volatile var armed = true
    val inside = CountDownLatch(1)
    val proceed = CountDownLatch(1)
}

fun newMarked(args: VoidEventArgs<Marked, Nothing?, Ctx, MarkedViews>): Marked = Marked(Mark("no"), Mark("no"))

fun markByTrigger(args: LifecycleArgs<Marked, Ctx, MarkedViews>): Marked {
    if (TriggerGate.armed) {
        TriggerGate.armed = false
        TriggerGate.inside.countDown()
        TriggerGate.proceed.await(10, TimeUnit.SECONDS)
    }
    return args.model.props.copy(byTrigger = Mark("yes"))
}

fun markByCommand(args: InstanceEventArgs<Marked, SetByCommandParams, Ctx, MarkedViews>): Marked =
    args.model.props.copy(byCommand = args.command.params.byCommand)

class MarkedViews : ModelViews<Marked, Ctx>()

class TimeTriggerRaceTest {

    @Test
    fun `a command waits for a running time trigger, so neither undoes the other`() = runBlocking<Unit> {
        // Ctx.system() stamps commands with the system clock, so the trigger is due relative to that.
        val clock = MutableClock(Clock.System.now())
        val views = MarkedViews()
        val specification = SpecificationBuilder<Ctx, MarkedViews>(views).build {
            eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
            managedModels {
                model(
                    Marked::class,
                    stateMachine {
                        event(CreateMarked) {}
                        event(SetByCommand) {}
                        voidState { onEvent(CreateMarked) { createModel(MarkedStates.Fresh, ::newMarked) } }
                        state(MarkedStates.Fresh) {
                            onEvent(SetByCommand) { update(::markByCommand) }
                            after(1.seconds) {
                                update(::markByTrigger)
                                transitionTo(MarkedStates.Old)
                            }
                        }
                        state(MarkedStates.Old) {
                            onEvent(SetByCommand) { update(::markByCommand) }
                        }
                    },
                    views,
                )
            }
            authorization { allowEverythingInsecurely() }
            systemContextProvider { Ctx.system() }
        }
        val klerk = Klerk.create(specification, testSettings(clock = clock))
        klerk.meta.start(installShutdownHook = false)
        val id = klerk.handle(Command(CreateMarked), Ctx.system()).getOrThrow().primaryModel!!
        clock.advance(2.seconds)

        check(TriggerGate.inside.await(20, TimeUnit.SECONDS)) { "the time trigger never ran" }
        val command = async(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            klerk.handle(Command(SetByCommand, id as ModelID<Marked>, SetByCommandParams(Mark("yes"))), Ctx.system())
                .getOrThrow()
        }
        delay(200)
        assertFalse(command.isCompleted, "the command must wait while the time trigger is processed")
        TriggerGate.proceed.countDown()
        command.await()

        @Suppress("UNCHECKED_CAST")
        val marked = klerk.read(Ctx.system()) { get(id as ModelID<Marked>) }.props
        assertEquals("yes", marked.byTrigger.value)
        assertEquals("yes", marked.byCommand.value)
        klerk.meta.stop()
    }
}

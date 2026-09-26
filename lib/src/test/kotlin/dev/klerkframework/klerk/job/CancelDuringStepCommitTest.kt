package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.CommandRuleArgs
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelReadRuleArgs
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.SpecificationBuilder
import dev.klerkframework.klerk.TicketTitle
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.testSettings
import dev.klerkframework.klerk.testing.runUntilIdle
import dev.klerkframework.klerk.testing.step
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals

class CancelDuringStepCommitTest {

    @Test
    fun `a cancel request made while a step waits to commit is not lost`() = runBlocking<Unit> {
        val klerk = start()
        val job = klerk.jobs.schedule(GatedJob.declare(GatedCursor()), Ctx.system())

        val step = async(Dispatchers.Default) { klerk.jobs.step() }
        GatedJob.started.await()

        // A command that holds the command mutex until released, so the step's commit has to wait for it.
        val commandHoldsMutex = async(Dispatchers.Default) {
            klerk.handle(Command(CreateBlocker, null, null), Ctx.system())
        }
        BlockerGate.entered.await()
        GatedJob.release.complete(Unit)
        delay(300) // the step is now planned and waiting for the mutex

        val cancel = async(Dispatchers.Default) { klerk.jobs.cancel(job, Ctx.system(), "stop") }
        delay(300)
        BlockerGate.release.countDown()
        commandHoldsMutex.await()
        step.await()
        cancel.await()

        klerk.jobs.runUntilIdle()
        assertEquals(JobStatus.Cancelled, klerk.jobs.get(job, Ctx.system()).status)
        klerk.meta.stop()
    }

    private suspend fun start(): Klerk<Ctx, BlockerAppViews> {
        val views = BlockerAppViews(BlockerViews())
        val specification = SpecificationBuilder<Ctx, BlockerAppViews>(views).build {
            eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
            managedModels {
                model(Blocker::class, blockerStateMachine(), views.blockers)
            }
            jobs {
                register(GatedJob)
            }
            authorization {
                readModels { positive(::anyoneMayReadBlockers) }
                commands { positive(::anyoneMayCreateBlockers) }
            }
            systemContextProvider { Ctx.system() }
        }
        return Klerk.create(specification, testSettings()).also { it.meta.start(installShutdownHook = false) }
    }
}

@Serializable
data class GatedCursor(val stepsDone: Int = 0)

object GatedJob : JobType.Local<GatedCursor, Ctx, BlockerAppViews>() {
    override val name = JobName("gated-job")
    override val agent: JobAgent = JobAgent.System

    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun step(
        args: JobStepArgs.Local<GatedCursor, Ctx, BlockerAppViews>,
    ): JobResult<GatedCursor, Ctx, BlockerAppViews> {
        if (args.cursor.stepsDone == 0) {
            started.complete(Unit)
            release.await()
            return JobResult.Yield(cursor = args.cursor.copy(stepsDone = 1))
        }
        return JobResult.Success(result = "done")
    }
}

data class BlockerAppViews(val blockers: BlockerViews)

class BlockerViews : ModelViews<Blocker, Ctx>()

data class Blocker(val title: TicketTitle)

object BlockerGate {
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
}

object CreateBlocker : VoidEventNoParameters<Blocker>(External)

enum class BlockerStates { Created }

private fun blockerStateMachine() = stateMachine<Blocker, BlockerStates, Ctx, BlockerAppViews> {
    event(CreateBlocker) {}
    voidState {
        onEvent(CreateBlocker) {
            createModel(BlockerStates.Created, ::blockingCreate)
        }
    }
    state(BlockerStates.Created) {}
}

private fun blockingCreate(args: VoidEventArgs<Blocker, Nothing?, Ctx, BlockerAppViews>): Blocker {
    BlockerGate.entered.complete(Unit)
    BlockerGate.release.await()
    return Blocker(TicketTitle("blocker"))
}

private fun anyoneMayReadBlockers(args: ModelReadRuleArgs<Ctx, BlockerAppViews>) = PositiveAuthorization.Allow

private fun anyoneMayCreateBlockers(args: CommandRuleArgs<*, Ctx, BlockerAppViews>) = PositiveAuthorization.Allow

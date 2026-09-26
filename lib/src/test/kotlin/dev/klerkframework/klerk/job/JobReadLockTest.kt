package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.SpecificationBuilder
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.testSettings
import dev.klerkframework.klerk.testing.step
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.view.count
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Counter(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

data class Counted(val name: Counter)

data class CountedParams(val name: Counter)

object CreateCounted : VoidEventWithParameters<Counted, CountedParams>(External)

enum class CountedStates { Alive }

/** Blocks the first evaluation after [armed] is set, so that a test can act while a step is building a view index. */
object ViewIndexGate {
    @Volatile var armed = false
    val inside = CountDownLatch(1)
    val proceed = CountDownLatch(1)
}

fun everythingIsCounted(model: Model<Counted>): Boolean {
    if (ViewIndexGate.armed) {
        ViewIndexGate.armed = false
        ViewIndexGate.inside.countDown()
        ViewIndexGate.proceed.await(10, TimeUnit.SECONDS)
    }
    return true
}

class CountedViews : ModelViews<Counted, Ctx>() {
    val everything = all.filter(::everythingIsCounted).register("everything")
}

fun newCounted(args: VoidEventArgs<Counted, CountedParams, Ctx, CountedViews>): Counted =
    Counted(args.command.params.name)

@Serializable
data class CountingCursor(val n: Int)

object CountEverything : JobType.Local<CountingCursor, Ctx, CountedViews>() {
    override val name = JobName("count-everything")
    override val agent: JobAgent = JobAgent.System

    override suspend fun step(
        args: JobStepArgs.Local<CountingCursor, Ctx, CountedViews>,
    ): JobResult<CountingCursor, Ctx, CountedViews> {
        val n = args.read { views.everything.count() }
        return JobResult.Success(result = n.toString())
    }
}

class JobReadLockTest {

    @Test
    fun `a command waits for a step's read, so the view index it builds stays complete`() = runBlocking<Unit> {
        val views = CountedViews()
        val specification = SpecificationBuilder<Ctx, CountedViews>(views).build {
            eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
            managedModels {
                model(
                    Counted::class,
                    stateMachine {
                        event(CreateCounted) {}
                        voidState { onEvent(CreateCounted) { createModel(CountedStates.Alive, ::newCounted) } }
                        state(CountedStates.Alive) {}
                    },
                    views,
                )
            }
            jobs { register(CountEverything) }
            authorization { allowEverythingInsecurely() }
            systemContextProvider { Ctx.system() }
        }
        val klerk = Klerk.create(specification, testSettings())
        klerk.meta.start(installShutdownHook = false)
        klerk.handle(Command(CreateCounted, CountedParams(Counter("a"))), Ctx.system()).getOrThrow()
        klerk.jobs.schedule(CountEverything.declare(CountingCursor(0)), Ctx.system())

        ViewIndexGate.armed = true
        val stepping = async(Dispatchers.IO) { klerk.jobs.step() }
        check(ViewIndexGate.inside.await(10, TimeUnit.SECONDS))
        val creating = async(Dispatchers.IO) {
            klerk.handle(Command(CreateCounted, CountedParams(Counter("b"))), Ctx.system()).getOrThrow()
        }
        delay(200)
        assertFalse(creating.isCompleted, "the command must wait while the step reads")
        ViewIndexGate.proceed.countDown()
        stepping.await()
        creating.await()

        val counted = klerk.read(Ctx.system()) { views.everything.count() }
        val all = klerk.read(Ctx.system()) { views.all.count() }
        assertEquals(all, counted)
        klerk.meta.stop()
    }
}

package dev.klerkframework.klerk.storage

import dev.klerkframework.klerk.CommandRuleArgs
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.ModelReadRuleArgs
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.PropertyReadRuleArgs
import dev.klerkframework.klerk.SpecificationBuilder
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.testSettings
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.view.count
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.lang.management.ManagementFactory
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Not part of the regular suite: creates a large, fully-resident population of models backed by SQLite, then
 * restarts Klerk against the same database. Reports heap usage, create time, restart (reload) time and random-read
 * latency, to measure the effect of the id-set/`Model` timestamp changes (see issues #40, #42 and #47).
 *
 * Every model refers to one of the first [PARENTS] models (few referenced models, many referrers each) and to the
 * previously created model (one referrer per referenced model), so both shapes of `ModelCache.relationsTo` are covered.
 *
 * Skipped unless `-Dklerk.benchmark=true` is passed, since it takes minutes and needs a large heap. Run it with:
 *
 * ```
 * ./gradlew :lib:memoryBenchmark
 * ```
 *
 * or directly with a custom size:
 *
 * ```
 * ./gradlew :lib:test --tests "*ModelCacheFootprintBenchmark*" -Dklerk.benchmark=true -Dklerk.benchmark.count=2000000
 * ```
 */
class ModelCacheFootprintBenchmark {

    @Test
    fun `memory footprint, create time, restart time and random-read latency`() {
        if (System.getProperty("klerk.benchmark") != "true") {
            println("Skipping ModelCacheFootprintBenchmark (pass -Dklerk.benchmark=true to run it)")
            return
        }
        val count = System.getProperty("klerk.benchmark.count")?.toIntOrNull() ?: 1_000_000
        val randomReads = System.getProperty("klerk.benchmark.reads")?.toIntOrNull() ?: 200_000

        // A shared-cache in-memory SQLite database, kept alive across the "restart" by never closing this
        // connection. Isolates Klerk's own load path (ModelCache/ModelViews rebuild) from disk I/O noise.
        val dbUrl = "jdbc:sqlite:file:benchmark?mode=memory&cache=shared"
        val keepAlive = DriverManager.getConnection(dbUrl)
        val dataSource = SQLiteDataSource(SQLiteConfig().apply { setReadUncommitted(true) }).apply { url = dbUrl }

        val heapBaseline = heapUsedBytes()
        val modelCacheSettings = ModelCacheSettings(maxResidentModels = count + 1_000)

        val views1 = BenchAppViews(BenchModelViews())
        val klerk1 = Klerk.create(
            buildSpecification(views1),
            testSettings(SqlPersistence(dataSource), modelCache = modelCacheSettings),
        )
        runBlocking { klerk1.meta.start(installShutdownHook = false) }

        val ids = IntArray(count)
        val createDuration = measureTime {
            runBlocking {
                for (i in 0 until count) {
                    val props = BenchProps(
                        n = BenchN(i),
                        parent = if (i >= PARENTS) ModelID(ids[i % PARENTS]) else null,
                        previous = if (i > 0) ModelID(ids[i - 1]) else null,
                    )
                    val result = klerk1.handle(Command(CreateBench, null, props), Ctx.system())
                    ids[i] = result.getOrThrow().primaryModel!!.value
                }
            }
        }
        println(
            "Created $count models in $createDuration " +
                "(${count / createDuration.inWholeMilliseconds.coerceAtLeast(1) * 1000} models/s)",
        )

        // Force every registered view's index to build, so the steady-state cost includes them.
        runBlocking {
            klerk1.read(Ctx.system()) {
                views1.bench.divisibleByTwo.count()
                views1.bench.divisibleByThree.count()
                views1.bench.divisibleByFive.count()
            }
        }

        val heapAfterCreate = heapUsedBytes()

        val random = Random(42)
        val readDuration = measureTime {
            runBlocking {
                repeat(randomReads) {
                    val id = ModelID<BenchProps>(ids[random.nextInt(count)])
                    klerk1.read(Ctx.system()) { get(id) }
                }
            }
        }
        val nsPerRead = readDuration.inWholeNanoseconds / randomReads.toLong()
        println("Read $randomReads random models in $readDuration ($nsPerRead ns/read)")

        runBlocking { klerk1.meta.stop() }

        // "Restart": a fresh Klerk (and fresh ModelViews, exactly like a real process restart) against the same
        // database, reading every model back from storage.
        val views2 = BenchAppViews(BenchModelViews())
        val klerk2 = Klerk.create(
            buildSpecification(views2),
            testSettings(SqlPersistence(dataSource), modelCache = modelCacheSettings),
        )
        val restartDuration = measureTime {
            runBlocking { klerk2.meta.start(installShutdownHook = false) }
        }
        println("Restarted (reloaded $count models) in $restartDuration")

        val heapAfterRestart = heapUsedBytes()
        val readableAfterRestart = runBlocking {
            klerk2.read(Ctx.system()) { get(ModelID<BenchProps>(ids[0])) }.props.n.value
        }
        check(readableAfterRestart == 0) { "Expected the first created model to survive the restart" }
        val referrersOfFirst = runBlocking {
            klerk2.read(Ctx.system()) { referencingIds(ModelID<BenchProps>(ids[0])) }.size
        }
        // ids[0] is the parent of every PARENTS-th model from PARENTS on, and the previous of ids[1].
        check(referrersOfFirst == (count - 1) / PARENTS + 1) { "Expected relations to survive the restart" }

        println("---- ModelCacheFootprintBenchmark ----")
        println("models=$count reads=$randomReads")
        println("heap before create:   ${mb(heapBaseline)} MB")
        println("heap after create:    ${mb(heapAfterCreate)} MB (+${mb(heapAfterCreate - heapBaseline)} MB)")
        println("heap after restart:   ${mb(heapAfterRestart)} MB")
        println("bytes/model (create): ${(heapAfterCreate - heapBaseline) / count}")
        println("bytes/model (restart):${(heapAfterRestart - heapBaseline) / count}")
        println("create:  $createDuration total, ${createDuration.inWholeNanoseconds / count} ns/model")
        println("restart: $restartDuration total, ${restartDuration.inWholeNanoseconds / count} ns/model")
        println("read:    $readDuration total, $nsPerRead ns/read")
        println("---------------------------------------")

        runBlocking { klerk2.meta.stop() }
        keepAlive.close()
    }

    private fun buildSpecification(views: BenchAppViews) = SpecificationBuilder<Ctx, BenchAppViews>(views).build {
        eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
        managedModels {
            model(BenchProps::class, benchStateMachine(views), views.bench)
        }
        authorization {
            readModels { positive(::anyoneMayReadBench) }
            readProperties { positive(::anyoneMayReadBenchProperties) }
            commands { positive(::anyoneMayDoBenchCommands) }
        }
        systemContextProvider { Ctx.system() }
    }

    /** Best-effort resident heap size: forces a few GC passes first, since there is no exact way to ask for this. */
    private fun heapUsedBytes(): Long {
        val bean = ManagementFactory.getMemoryMXBean()
        repeat(3) {
            System.gc()
            Thread.sleep(200)
        }
        return bean.heapMemoryUsage.used
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    private companion object {
        const val PARENTS = 1_000
    }
}

data class BenchAppViews(val bench: BenchModelViews)

class BenchModelViews : ModelViews<BenchProps, Ctx>() {
    val divisibleByTwo = this.all.filter { it.props.n.value % 2 == 0 }.register("divisibleByTwo")
    val divisibleByThree = this.all.filter { it.props.n.value % 3 == 0 }.register("divisibleByThree")
    val divisibleByFive = this.all.filter { it.props.n.value % 5 == 0 }.register("divisibleByFive")
}

class BenchN(value: Int) : IntContainer(value) {
    override val min: Int = Int.MIN_VALUE
    override val max: Int = Int.MAX_VALUE
}

data class BenchProps(val n: BenchN, val parent: ModelID<BenchProps>?, val previous: ModelID<BenchProps>?)

enum class BenchStates { Created }

object CreateBench : VoidEventWithParameters<BenchProps, BenchProps>(External)

fun benchStateMachine(views: BenchAppViews) = stateMachine<BenchProps, BenchStates, Ctx, BenchAppViews> {
    event(CreateBench) {
        validReferences(BenchProps::parent, views.bench.all)
        validReferences(BenchProps::previous, views.bench.all)
    }
    voidState {
        onEvent(CreateBench) { createModel(BenchStates.Created, ::newBenchModel) }
    }
    state(BenchStates.Created) {}
}

private fun newBenchModel(args: VoidEventArgs<BenchProps, BenchProps, Ctx, BenchAppViews>): BenchProps =
    args.command.params

private fun anyoneMayReadBench(args: ModelReadRuleArgs<Ctx, BenchAppViews>) = PositiveAuthorization.Allow

private fun anyoneMayReadBenchProperties(args: PropertyReadRuleArgs<Ctx, BenchAppViews>) = PositiveAuthorization.Allow

private fun anyoneMayDoBenchCommands(args: CommandRuleArgs<*, Ctx, BenchAppViews>) = PositiveAuthorization.Allow

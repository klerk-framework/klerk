package dev.klerkframework.klerk

import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.ModelCacheSettings
import dev.klerkframework.klerk.storage.SqlPersistence
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.klerk.view.count
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.lang.management.ManagementFactory
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Regression benchmark, not part of the regular suite. Creates 1 M models backed by in-memory SQLite, restarts Klerk
 * against the same database and reports:
 *
 * - time to create the models
 * - heap growth after create
 * - restart time (reloading every model)
 * - time to read 1000 random models, both as 1000 separate `read` calls and as 1000 `get`s in one `read`
 *
 * Every model refers to one of the first [PARENTS] models and to the previously created model, so both shapes of
 * `ModelCache.relationsTo` are covered. Compare the output with a run of the base commit on the same machine.
 *
 * Skipped unless `-Dklerk.benchmark=true` is passed, since it takes minutes and needs a large heap. Run it with:
 *
 * ```
 * ./gradlew :lib:benchmark [-Dklerk.benchmark.count=2000000]
 * ```
 */
class PerformanceBenchmark {

    @Test
    fun `create, restart and random reads`() {
        if (System.getProperty("klerk.benchmark") != "true") {
            println("Skipping PerformanceBenchmark (pass -Dklerk.benchmark=true to run it)")
            return
        }
        val count = System.getProperty("klerk.benchmark.count")?.toIntOrNull() ?: 1_000_000

        // Kept alive across the restart by never closing this connection. Isolates Klerk from disk I/O noise.
        val dbUrl = "jdbc:sqlite:file:benchmark?mode=memory&cache=shared"
        val keepAlive = DriverManager.getConnection(dbUrl)
        val dataSource = SQLiteDataSource(SQLiteConfig().apply { setReadUncommitted(true) }).apply { url = dbUrl }
        val modelCacheSettings = ModelCacheSettings(maxResidentModels = count + 1_000)

        val heapBaseline = heapUsedBytes()

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

        // Build every view index, so the heap figure includes them.
        runBlocking {
            klerk1.read(Ctx.system()) {
                views1.bench.divisibleByTwo.count()
                views1.bench.divisibleByThree.count()
                views1.bench.divisibleByFive.count()
            }
        }
        val heapAfterCreate = heapUsedBytes()
        runBlocking { klerk1.meta.stop() }

        // A fresh Klerk and fresh ModelViews, like a process restart.
        val views2 = BenchAppViews(BenchModelViews())
        val klerk2 = Klerk.create(
            buildSpecification(views2),
            testSettings(SqlPersistence(dataSource), modelCache = modelCacheSettings),
        )
        val restartDuration = measureTime {
            runBlocking { klerk2.meta.start(installShutdownHook = false) }
        }
        val referrersOfFirst = runBlocking {
            klerk2.read(Ctx.system()) { referencingIds(ModelID<BenchProps>(ids[0])) }.size
        }
        // ids[0] is the parent of every PARENTS-th model from PARENTS on, and the previous of ids[1].
        check(referrersOfFirst == (count - 1) / PARENTS + 1) { "Expected relations to survive the restart" }

        val random = Random(42)
        val separateReads = medianOf {
            runBlocking {
                repeat(READS) {
                    val id = ModelID<BenchProps>(ids[random.nextInt(count)])
                    klerk2.read(Ctx.system()) { get(id) }
                }
            }
        }
        val singleRead = medianOf {
            runBlocking {
                klerk2.read(Ctx.system()) {
                    repeat(READS) { get(ModelID<BenchProps>(ids[random.nextInt(count)])) }
                }
            }
        }

        println("---- PerformanceBenchmark ($count models) ----")
        println("create:              $createDuration (${createDuration.inWholeNanoseconds / count} ns/model)")
        val heapGrowth = heapAfterCreate - heapBaseline
        println("heap growth:         ${mb(heapGrowth)} MB (${heapGrowth / count} bytes/model)")
        println("restart:             $restartDuration")
        println("$READS random reads:   $separateReads as separate reads, $singleRead in one read (median)")
        println("---------------------------------------------")

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

    /** Median of [MEASURED_ROUNDS] runs of [block], after [WARMUP_ROUNDS] runs to let the JIT settle. */
    private fun medianOf(block: () -> Unit): Duration {
        repeat(WARMUP_ROUNDS) { block() }
        return List(MEASURED_ROUNDS) { measureTime(block) }.sorted()[MEASURED_ROUNDS / 2]
    }

    /** Best-effort resident heap size: forces a few GC passes first, since there is no exact way to ask for this. */
    private fun heapUsedBytes(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(200)
        }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    private companion object {
        const val PARENTS = 1_000
        const val READS = 1_000
        const val WARMUP_ROUNDS = 200
        const val MEASURED_ROUNDS = 51
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

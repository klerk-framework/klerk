package dev.klerkframework.webutils

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.migration.MigrationStep
import dev.klerkframework.klerk.misc.IdFactory
import dev.klerkframework.klerk.storage.AuditEntry
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.SqlPersistence
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.sqlite.SQLiteDataSource
import java.io.File
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.measureTime

fun main() {

    val deleteAndCreate = false

    val dbFilePath = "/tmp/klerktest.sqlite"
    if (deleteAndCreate) {
        File(dbFilePath).delete()
    }
    val ds = SQLiteDataSource()
    ds.url = "jdbc:sqlite:$dbFilePath"
    val persistence = SqlPersistence(ds)

    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))
    val klerk = Klerk.create(createConfig(collections, persistence), KlerkSettings())

    runBlocking {
        klerk.meta.start()
        println("Klerk has started")

        if (deleteAndCreate) {
            createAuthorsAndBooks(50, klerk)
        }

        println("Total number of models: ${klerk.meta.modelsCount}")
        val singleThreadQueryTime = measureTime {
            val count = klerk.read(Context.system()) {
                data.authors.all.filter { it.props.firstName.value.toInt() % 2 == 0 }.count(this)
            }
            println("Count: $count")
        }
        println("singleThreadQueryTime (ms): ${singleThreadQueryTime.inWholeMilliseconds}")

        coroutineScope {
            val jobs = (1..4).map { n ->
                async {
                    klerk.read(Context.system()) {
                        val size = list(collections.books.all).size
                        println(size)
                    }
                }
            }
            val parallelQueryTime = measureTime {
                jobs.awaitAll()
            }
            println("parallelQueryTime (ms): ${parallelQueryTime.inWholeMilliseconds}")
        }

    }
}

/**
 * Creates 9 books per author
 */
suspend fun createAuthorsAndBooks(thousandAuthors: Int, klerk: Klerk<Context, MyCollections>) {
    val createTime = measureTime {
        var k = 0
        for (i in 1..thousandAuthors) {
            val thousandTime = measureTime {
                for (j in 1..1000) {
                    k++
                    val result = klerk.handle(
                        Command(
                            CreateAuthor,
                            model = null,
                            params = CreateAuthorParams(
                                firstName = FirstName(k.toString()),
                                lastName = LastName("Svensson"),
                                age = EvenIntContainer(k * 2),
                                phone = PhoneNumber("234"),
                                secretToken = SecretPasscode(99)
                            ),
                        ),
                        context = Context.system(),
                        options = ProcessingOptions(CommandToken.simple())
                    ).orThrow()

                    for (l in 1..9) {
                        //    launch {
                        klerk.handle(
                            Command(
                                event = CreateBook,
                                model = null,
                                params = CreateBookParams(
                                    title = BookTitle("Pelles äventyr $k$l"),
                                    author = result.primaryModel!!,
                                    coAuthors = emptySet(),
                                    previousBooksInSameSeries = emptyList(),
                                    tags = emptySet(),
                                    averageScore = AverageScore(4.3f)
                                )
                            ),
                            context = Context.system(),
                            options = ProcessingOptions(CommandToken.simple()),
                        )
                    }
                    //       }
                }
            }
            println("$i thousands (last thousand in ${thousandTime.inWholeMilliseconds} ms)")

        }
        println("Authors created")

    }
    println("CreateTime (s): ${createTime.inWholeSeconds}")
}


fun onlyHashMap() {
    val models: MutableMap<Int, Model<out Any>> = mutableMapOf()
    var k = 0
    for (i in 1..100) {
        for (j in 1..1000) {
            k++
            val a =
                Model<Author>(
                    id = IdFactory.getNext<Author>(),
                    createdAt = Clock.System.now(),
                    lastPropsUpdateAt = Clock.System.now(),
                    lastStateTransitionAt = Clock.System.now(),
                    state = "sdf",
                    timeTrigger = null,
                    props =
                    Author(
                        firstName = FirstName(i.toString()),
                        lastName = LastName("Svensson"),
                        address = Address(Street("Storgatan 123"))
                    )
                )
            models[a.id.toInt()] = a

            for (l in 1..9) {
                val book = Model(
                    id = IdFactory.getNext<Book>(),
                    createdAt = Clock.System.now(),
                    lastPropsUpdateAt = Clock.System.now(),
                    lastStateTransitionAt = Clock.System.now(),
                    state = "sdf",
                    timeTrigger = null,
                    props = Book(
                        title = BookTitle("Pelles äventyr"),
                        author = a.id,
                        coAuthors = emptySet(),
                        previousBooksInSameSeries = emptyList(),
                        tags = emptySet(),
                        averageScore = AverageScore(4.3f),
                        salesPerYear = emptySet(),
                        writtenAt = BookWrittenAt(Instant.fromEpochSeconds(100000)),
                        readingTime = ReadingTime(2.days),
                        publishedAt = null,
                        releasePartyPosition = ReleasePartyPosition(GeoPosition(latitude = 1.234, longitude = 3.456))
                    )
                )
                models[book.id.toInt()] = book

            }
        }
    }
    println(models.size)
}


object NoStorage : Persistence {
    override val currentModelSchemaVersion = 1

    override fun <T : Any, P, C : KlerkContext, V> store(
        delta: ProcessingData<out T, C, V>,
        command: Command<T, P>?,
        context: C?
    ) {
        // noop
    }

    override fun readAllModels(lambda: (Model<out Any>) -> Unit) {
        // noop
    }

    override fun readAuditLog(modelId: Int?, from: Instant, until: Instant): Iterable<AuditEntry> {
        TODO("Not yet implemented")
    }

    override fun modifyEventsInAuditLog(modelId: Int, transformer: (AuditEntry) -> AuditEntry?) {
        // noop
    }

    override fun setConfig(config: Config<*, *>) {
        // noop
    }

    override fun migrate(migrations: List<MigrationStep>) {
        // noop
    }

}
package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.ReaderWithAuth
import kotlinx.coroutines.runBlocking
import kotlin.test.*


class EventProcessorTest {

    val bc = BookViews()
    var collections = MyCollections(bc, AuthorViews(bc.all))
    val config = createConfig(collections)

    @Test
    fun `Can handle simple commands`() {
        val options = ProcessingOptions(CommandToken.simple())

        val klerk = Klerk.create(config) as KlerkImpl
        val eventProcessor = EventProcessor(klerk, KlerkSettings(), ReadWriteLock(), MyTimeTriggerManager)
        val createAuthor = Command(CreateAuthor, null, createAstridParameters)
        val context = Context.system()
        val reader = ReaderWithAuth(klerk, context)
        val result = eventProcessor.processPrimaryCommand(createAuthor, context, reader, options)

        val primaryId = requireNotNull(result.primaryModel)

        @Suppress("UNCHECKED_CAST")
        assertEquals("Astrid", (result.aggregatedModelState[primaryId] as Model<Author>).props.firstName.value)
        assertEquals(primaryId.toInt(), result.createdModels.singleOrNull()?.toInt())
    }

    @Test
    fun `On delete cascade`() {
        runBlocking {
            // first we will populate ModelCache
            val klerk = Klerk.create(config)
            klerk.meta.start()

            val context = Context.system()
            val reader = ReaderWithAuth(klerk as KlerkImpl, context)

            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)
            createBookHarryPotter2(klerk, rowling, emptyList(), emptySet())

            val willFail = klerk.handle(
                Command(
                    event = DeleteAuthor,
                    model = rowling,
                    params = null
                    ),
                context,
                ProcessingOptions(CommandToken.simple())
            )
            when (willFail) {
                is CommandResult.Failure -> assertTrue(willFail.problems.first().toString().contains("since these models have a reference to it"))
                is CommandResult.Success -> fail()
            }

            val willNotFail = klerk.handle(
                Command(
                    event = DeleteAuthorAndBooks,
                    model = rowling,
                    params = null
                ),
                context,
                ProcessingOptions(CommandToken.simple())
            ).orThrow()

            assertEquals(3, willNotFail.deletedModels.size)
        }
    }
}

private object MyTimeTriggerManager : TriggerTimeManager {
    override fun init(models: List<Model<out Any>>) {

    }

    override fun start() {
    }

    override fun stop() {
    }

}
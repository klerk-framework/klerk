package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.ReadWriteLock
import dev.klerkframework.klerk.read.ReaderWithAuth
import dev.klerkframework.klerk.storage.CommitBatch
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import dev.klerkframework.klerk.view.*


class EventProcessorTest {

    val bc = BookViews()
    var collections = Views(bc, AuthorViews(bc.all))
    val specification = createConfig(collections)

    @Test
    fun `Can handle simple commands`() {
        val options = ProcessingOptions(CommandToken.simple())

        val klerk = Klerk.create(specification, testSettings()) as KlerkImpl
        val eventProcessor = EventProcessor(klerk, testSettings(), ReadWriteLock(), MyTimeTriggerManager)
        val createAuthor = Command(CreateAuthor, createAstridParameters)
        val context = Ctx.system()
        val reader = ReaderWithAuth(klerk, context)
        val result = eventProcessor.processPrimaryCommand(createAuthor, context, reader, options)

        val primaryId = requireNotNull(result.primaryModel)

        @Suppress("UNCHECKED_CAST")
        assertEquals("Astrid", (result.aggregatedModelState[primaryId] as Model<Author>).props.firstName.value)
        assertEquals(primaryId.value, result.createdModels.singleOrNull()?.value)
    }

    @Test
    fun `On delete cascade`() {
        runBlocking {
            // first we will populate ModelCache
            val klerk = Klerk.create(specification, testSettings())
            klerk.meta.start()

            val context = Ctx.system()
            val reader = ReaderWithAuth(klerk as KlerkImpl, context)

            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)
            createBookHarryPotter2(klerk, rowling, emptyList(), emptySet())

            val willFail = klerk.handle(
                Command(
                    DeleteAuthor,
                    rowling
                ),
                context,
            )
            when (willFail) {
                is CommandResult.Failure -> assertEquals(willFail.problems.first().code, KlerkErrorCode.BrokenReference)
                is CommandResult.Success -> fail()
            }

            val willNotFail = klerk.handle(
                Command(
                    DeleteAuthorAndBooks,
                    rowling
                ),
                context,
            ).getOrElse {
                println(it.problems.joinToString(", "))
                throw it.problems.first().asException()
            }

            assertEquals(3, willNotFail.deletedModels.size)
        }
    }

    @Test
    fun `startup fails when a stored model no longer passes validation`() = runBlocking {
        val storage = RamStorage()

        val first = Klerk.create(specification, testSettings(storage)) as KlerkImpl
        first.meta.start()
        val authorId = createAuthorJKRowling(first)
        val validAuthor = ReaderWithAuth(first, Ctx.system()).get(authorId)

        // Bypass validation to get a property that no longer satisfies FirstName's minLength onto disk.
        val corrupted = validAuthor.copy(props = validAuthor.props.copy(firstName = FirstName("")))
        storage.store(CommitBatch(updatedModels = listOf(corrupted)))
        first.meta.stop()

        val second = Klerk.create(specification, testSettings(storage)) as KlerkImpl
        val exception = assertFailsWith<PersistedModelValidationException> {
            second.meta.start()
        }
        assertEquals("Author", exception.modelType)
        assertEquals(authorId.value, exception.modelId)
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

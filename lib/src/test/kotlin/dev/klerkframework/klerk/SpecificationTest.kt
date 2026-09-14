package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail


class SpecificationTest {

    @Test
    fun `Can combine configs`() {
        val bc = BookViews()
        val collections = Views(bc, AuthorViews(bc.all))
        val specification = createConfig(collections)
        val newManagedModels = specification.managedModels.toMutableSet()
        newManagedModels.drop(1)
        specification.copy(managedModels = newManagedModels)
    }

    @Test
    fun `Model can not contain abstract properties`() {
        val views = ViewWithIllegal(ModelViews())
        try {
            SpecificationBuilder<Ctx, ViewWithIllegal>(views).build {
                managedModels {
                    model(IllegalModel::class, illegalStateMachine, views.x)
                }
                authorization { }
            }
        } catch (e: Exception) {
            return
        }
        fail()
    }

    @Test
    fun `Model can not contain List of String`() {
        try {
            buildSpecWithExtraModel(IllegalModelListString::class, illegalStateMachineListString)
        } catch (e: Exception) {
            return
        }
        fail()
    }

    @Test
    fun `Model can not contain Set of String`() {
        try {
            buildSpecWithExtraModel(IllegalModelSetString::class, illegalStateMachineSetString)
        } catch (e: Exception) {
            return
        }
        fail()
    }
}

private fun <T : Any> buildSpecWithExtraModel(kClass: KClass<T>, stateMachine: StateMachine<T, States, Ctx, Views>) {
    val bc = BookViews()
    val collections = Views(bc, AuthorViews(bc.all))
    SpecificationBuilder<Ctx, Views>(collections).build {
        systemContextProvider { Ctx(actor = SystemIdentity) }
        jobContextProvider(::myJobContextProvider)
        jobs { }
        managedModels {
            model(Book::class, bookStateMachine(collections), collections.books)
            model(kClass, stateMachine, ModelViews())
        }
        authorization {
            readModels { positive { rule(::`Everybody can read`) } }
            readProperties { positive { rule(::canReadAllProperties) } }
            commands { positive { rule(::`Everybody can do everything`) } }
            eventLog { positive { rule(::`Everybody can read event log`) } }
            readAttachedData { positive { rule(::onlyTheAuthorsOwnerCanReadThePicture) } }
            writeAttachedData { positive { rule(::everybodyCanPrepareAttachedData) } }
            jobs { positive { rule(::authorsCanSeeTheirOwnJobs) } }
        }
    }
}

data class IllegalModel(val v: BooleanContainer)

data class ViewWithIllegal(val x: ModelViews<IllegalModel, Ctx>)

data class IllegalModelListString(val tags: List<String>)

private val illegalStateMachineListString = stateMachine<IllegalModelListString, States, Ctx, Views> { }

data class IllegalModelSetString(val tags: Set<String>)

private val illegalStateMachineSetString = stateMachine<IllegalModelSetString, States, Ctx, Views> { }

private enum class States {}

private val illegalStateMachine = stateMachine<IllegalModel, States, Ctx, ViewWithIllegal> { }


private class TestPlugin(override val name: String) : KlerkPlugin<Ctx, Views> {
    override val description: String = "for tests"
    var started = false
    var stopped = false
    val mergedAfter = mutableListOf<String>()

    override fun mergeSpecification(previous: Specification<Ctx, Views>): Specification<Ctx, Views> {
        mergedAfter.addAll(previous.plugins.map { it.name })
        return previous
    }

    override suspend fun start(klerk: Klerk<Ctx, Views>) {
        started = true
    }

    override fun stop() {
        stopped = true
    }
}

class PluginTest {

    private fun specWith(vararg plugin: KlerkPlugin<Ctx, Views>): Specification<Ctx, Views> {
        val bc = BookViews()
        val views = Views(bc, AuthorViews(bc.all))
        return SpecificationBuilder<Ctx, Views>(views).build {
            plugins(*plugin)
            systemContextProvider(::myContextProvider)
            jobContextProvider(::myJobContextProvider)
            managedModels {
                model(Book::class, bookStateMachine(views), views.books)
                model(Author::class, authorStateMachine(views), views.authors)
            }
            authorization { }
        }
    }

    @Test
    fun `plugins are merged in declaration order`() {
        val first = TestPlugin("first")
        val second = TestPlugin("second")
        val spec = specWith(first, second)
        assertEquals(listOf("first", "second"), spec.plugins.map { it.name })
        assertEquals(emptyList(), first.mergedAfter)
        assertEquals(listOf("first"), second.mergedAfter)
    }

    @Test
    fun `plugins are started and stopped with Klerk`() = runBlocking {
        val plugin = TestPlugin("p")
        val klerk = Klerk.create(specWith(plugin), testSettings())
        klerk.meta.start(installShutdownHook = false)
        assertTrue(plugin.started)
        assertFalse(plugin.stopped)
        klerk.meta.stop()
        assertTrue(plugin.stopped)
    }
}

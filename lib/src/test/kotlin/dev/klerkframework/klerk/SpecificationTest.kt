package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
import kotlin.reflect.KClass
import kotlin.test.Test
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


package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
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
}

data class IllegalModel(val v: BooleanContainer)

data class ViewWithIllegal(val x: ModelViews<IllegalModel, Ctx>)

private enum class States {}

private val illegalStateMachine = stateMachine<IllegalModel, States, Ctx, ViewWithIllegal> { }


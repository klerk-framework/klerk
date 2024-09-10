package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
import kotlin.test.Test
import kotlin.test.fail


class ConfigTest {

    @Test
    fun `Can combine configs`() {
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val config = createConfig(collections, RamStorage())
        val newManagedModels = config.managedModels.toMutableSet()
        newManagedModels.drop(1)
        config.copy(managedModels = newManagedModels)
    }

    @Test
    fun `Model can not contain abstract properties`() {
        val views = ViewWithIllegal(ModelCollections())
        try {
            ConfigBuilder<Context, ViewWithIllegal>(views).build {
                managedModels {
                    model(IllegalModel::class, illegalStateMachine, views.x)
                }
                authorization { }
                persistence(RamStorage())
            }
        } catch (e: Exception) {
            return
        }
        fail()
    }
}

data class IllegalModel(val v: BooleanContainer)

data class ViewWithIllegal(val x: ModelCollections<IllegalModel, Context>)

private enum class States {}

private val illegalStateMachine = stateMachine<IllegalModel, States, Context, ViewWithIllegal> {  }


package dev.klerkframework.klerk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue


class StateMachineTest {


    @Test
    fun testar() {
        val r = ModelID<Author>(Int.MAX_VALUE)
        println(r)
    }

    @Test
    fun getVoidEvents() {
        runBlocking {
            val bc = BookViews()
            val collections = MyCollections(bc, AuthorViews(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()

            val voidEvents = klerk.config.managedModels.single { it.kClass == Book::class }.stateMachine
                .getExternalEventsForVoidState(Context.unauthenticated())

            val parameters = klerk.config.getParameters(CreateBook.id)
            requireNotNull(parameters)
            val p = parameters.all.first()
            println(p.name)
            println(p.isNullable)
            println(p.type)
            println(p.modelIDType)
            assertTrue(parameters.raw == CreateBookParams::class)

        }

    }
}

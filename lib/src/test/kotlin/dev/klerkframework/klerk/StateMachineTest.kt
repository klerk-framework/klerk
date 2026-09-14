package dev.klerkframework.klerk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import dev.klerkframework.klerk.view.*


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
            val collections = Views(bc, AuthorViews(bc.all))
            val klerk = createKlerk(collections)
            klerk.meta.start()

            val voidEvents = klerk.specification.managedModels.single { it.kClass == Book::class }.stateMachine
                .getEventsForVoidState(Ctx.unauthenticated(), EventVisibility.External)

            val parameters = klerk.specification.parametersSchema(CreateBook.id)
            requireNotNull(parameters)
            val p = parameters.fields.first()
            println(p.name)
            println(p.isNullable)
            println(p.type)
            println(p.referencedModel)
            println(p.defaultContainer)
            assertTrue(parameters.kClass == CreateBookParams::class)

        }

    }
}

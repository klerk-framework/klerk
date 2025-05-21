package dev.klerkframework.klerk.statemachine

import dev.klerkframework.klerk.AuthorCollections
import dev.klerkframework.klerk.BookCollections
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.MyCollections
import dev.klerkframework.klerk.createConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.fail

class Delete {

    @Test
    fun `Delete related models`() {
        fail()
        runBlocking {
            val bc = BookCollections()
            val collections = MyCollections(bc, AuthorCollections(bc.all))
            val klerk = Klerk.create(createConfig(collections))
            klerk.meta.start()

            /* TODO
            skapa ett bra test


                    tanke:
            om jag beslutar mig för att man ska kunna köra delete på en modell utan att gå genom en statemachine:
            då kan man tänka sig att man kan peta in en funktion i delete(::raderaAndraGrejer).
            Antingen petar man in en egen funktion. Eller så kanske Klerk kan tillhandahålla en cascade-funktion som tar bort alla relaterade objekt.
            En fördel med detta är att man vid uppstart kan varna om att ett command troligen kommer att misslyckas om statemachine gör delete på en modell med relationer men inte har petat in en funktion.

             */
        }
    }
}

package dev.klerkframework.klerk

import kotlin.test.Test

class Translations {

    @Test
    fun test() {

        val t = SwedishTranslation().function(::myGreatFunction)

        println(t)

    }
}


@MyFunctionAnnotation("Detta är en annoterad översättning")
fun myGreatFunction() = "Hello World!"

annotation class MyFunctionAnnotation(val name: String)

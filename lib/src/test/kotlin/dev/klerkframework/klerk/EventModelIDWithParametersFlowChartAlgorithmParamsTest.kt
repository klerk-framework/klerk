package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.read.Reader
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.fail


class EventModelIDWithParametersFlowChartAlgorithmParamsTest {

    @Test
    fun valid() {
        EventParameters(MyValidEventParams::class)
    }

    @Test
    fun invalid() {
        try {
            EventParameters(MyInvalidEventParams::class)
            fail()
        } catch (e: IllegalArgumentException) {
            //
        }
    }

}

private class MyValidEventParams(
    val stringOne: NonEmptyString,
    val list: List<NonEmptyString>,
    val firstContainer: FirstContainer,
    val containerList: List<FirstContainer>,
    val containerSet: Set<FirstContainer>
)

private data class FirstContainer(val stringTwo: NonEmptyString, val secondContainer: SecondContainer)

private data class SecondContainer(val stringThree: NonEmptyString)

private data class BadContainer(val stringFour: String)

private class NonEmptyString(string: String) : StringContainer(string) {
    override val minLength: Int = 1
    override val maxLength: Int = 10000
    override val maxLines: Int = 1
}

private class MyInvalidEventParams(
    val stringOne: NonEmptyString,
    val list: List<NonEmptyString>,
    val firstContainer: FirstContainer,
    val containerList: List<FirstContainer>,
    val containerSet: Set<BadContainer>
)

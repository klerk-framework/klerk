package dev.klerkframework.klerk

import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import kotlin.test.Test
import kotlin.test.fail


class EventModelIDWithParametersFlowChartAlgorithmParamsTest {

    @Test
    fun valid() {
        ObjectSchema.of(MyValidEventParams::class)
    }

    @Test
    fun invalid() {
        try {
            ObjectSchema.of(MyInvalidEventParams::class)
            fail()
        } catch (e: IllegalConfigurationException) {
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

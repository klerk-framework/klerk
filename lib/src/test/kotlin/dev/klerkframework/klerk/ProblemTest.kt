package dev.klerkframework.klerk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProblemTest {

    @Test
    fun `problems compare by class and properties`() {
        assertEquals(InvalidPropertyProblem("Too long", "title"), InvalidPropertyProblem("Too long", "title"))
        assertEquals(
            InvalidPropertyProblem("Too long", "title").hashCode(),
            InvalidPropertyProblem("Too long", "title").hashCode(),
        )
        assertNotEquals(InvalidPropertyProblem("Too long", "title"), InvalidPropertyProblem("Too long", "name"))
        assertNotEquals<Problem>(NotFoundProblem("Gone"), InternalProblem("Gone"))
        assertNotEquals(
            StateProblem("No", "a", KlerkErrorCode.EventNotPossibleInState),
            StateProblem("No", "b", KlerkErrorCode.EventNotPossibleInState),
        )
    }
}

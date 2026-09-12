package dev.klerkframework.klerk

import dev.klerkframework.klerk.statemachine.stateMachine
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Everything a state machine may declare exactly once must fail loudly on the second declaration. */
class StateMachineDeclarationTest {

    @Test
    fun `voidState can only be declared once`() {
        assertFailsWith<IllegalConfigurationException> {
            stateMachine<Book, BookStates, Ctx, Views> {
                voidState {}
                voidState {}
            }
        }
    }

    @Test
    fun `a state can only be declared once`() {
        assertFailsWith<IllegalConfigurationException> {
            stateMachine<Book, BookStates, Ctx, Views> {
                state(BookStates.Draft) {}
                state(BookStates.Draft) {}
            }
        }
    }

    @Test
    fun `onEnter can only be declared once`() {
        assertFailsWith<IllegalConfigurationException> {
            stateMachine<Book, BookStates, Ctx, Views> {
                state(BookStates.Draft) {
                    onEnter {}
                    onEnter {}
                }
            }
        }
    }

    @Test
    fun `onExit can only be declared once`() {
        assertFailsWith<IllegalConfigurationException> {
            stateMachine<Book, BookStates, Ctx, Views> {
                state(BookStates.Draft) {
                    onExit {}
                    onExit {}
                }
            }
        }
    }

    @Test
    fun `a state can only have one time block`() {
        assertFailsWith<IllegalConfigurationException> {
            stateMachine<Book, BookStates, Ctx, Views> {
                state(BookStates.Draft) {
                    after(kotlin.time.Duration.parse("1h")) {}
                    after(kotlin.time.Duration.parse("2h")) {}
                }
            }
        }
    }
}

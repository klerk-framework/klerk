package dev.klerkframework.klerk

import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BlockedTimeTriggerTest {

    @Test
    fun `a blocked time trigger is counted`() = runBlocking<Unit> {
        val views = DocViews()
        val specification = SpecificationBuilder<Ctx, DocViews>(views).build {
            eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
            managedModels { model(Doc::class, docStateMachine(views), views) }
            authorization { allowEverythingInsecurely() }
            systemContextProvider { Ctx.system() }
        }
        val settings = testSettings()
        val klerk = Klerk.create(specification, settings)
        klerk.meta.start(installShutdownHook = false)
        val target = create(klerk, "target", null)
        create(klerk, "referrer", target)

        val counter = { settings.meterRegistry.find("klerk.time_trigger.blocked").counter()?.count() }
        repeat(100) { if (counter() == null) delay(100) }

        assertEquals(1.0, counter())
        assertEquals("Doc", settings.meterRegistry.get("klerk.time_trigger.blocked").counter().id.getTag("model"))
        assertEquals("Alive", settings.meterRegistry.get("klerk.time_trigger.blocked").counter().id.getTag("state"))
        assertNotNull(klerk.read(Ctx.system()) { getOrNull(target) }, "a blocked delete must leave the model")
        klerk.meta.stop()
    }

    private suspend fun create(klerk: Klerk<Ctx, DocViews>, name: String, next: ModelID<Doc>?): ModelID<Doc> =
        requireNotNull(
            klerk.handle(Command(CreateDoc, DocParams(NodeName(name), next)), Ctx.system())
                .getOrThrow().primaryModel,
        )
}

data class Doc(val name: NodeName, val next: ModelID<Doc>?)

data class DocParams(val name: NodeName, val next: ModelID<Doc>?)

class DocViews : ModelViews<Doc, Ctx>()

enum class DocStates { Alive }

object CreateDoc : VoidEventWithParameters<Doc, DocParams>(External)

fun docStateMachine(views: DocViews) = stateMachine<Doc, DocStates, Ctx, DocViews> {
    event(CreateDoc) { validReferences(DocParams::next, views.all) }
    voidState { onEvent(CreateDoc) { createModel(DocStates.Alive, ::newDoc) } }
    state(DocStates.Alive) {
        atTime(::docExpiry) { delete() }
    }
}

fun newDoc(args: VoidEventArgs<Doc, DocParams, Ctx, DocViews>): Doc =
    Doc(args.command.params.name, args.command.params.next)

/** The referrer lives long; the target expires at once, but cannot be deleted while it is referred to. */
fun docExpiry(args: LifecycleArgs<Doc, Ctx, DocViews>): Instant =
    args.model.createdAt + if (args.model.props.next != null) 1.hours else 1.seconds

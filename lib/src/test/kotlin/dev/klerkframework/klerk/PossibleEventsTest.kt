package dev.klerkframework.klerk

import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.validation.Valid
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PossibleEventsTest {

    @Test
    fun `validation rules see the model as handle does, also when the actor may not read a property`() =
        runBlocking<Unit> {
            val klerk = startVaultKlerk()
            val vault = klerk.handle(Command(CreateVault, null, CreateVaultParams(VaultCode("open"))), Ctx.system())
                .getOrThrow().primaryModel!!

            val possible = klerk.read(Ctx.unauthenticated()) { possibleEvents(vault) }

            assertEquals(setOf<Any>(OpenVault), possible)
            assertIs<CommandResult.Success<Vault>>(klerk.handle(Command(OpenVault, vault), Ctx.unauthenticated()))
        }
}

private suspend fun startVaultKlerk(): Klerk<Ctx, VaultAppViews> {
    val views = VaultAppViews(VaultViews())
    val specification = SpecificationBuilder<Ctx, VaultAppViews>(views).build {
        eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
        managedModels {
            model(Vault::class, vaultStateMachine(), views.vaults)
        }
        authorization {
            readModels { positive(::anyoneMayReadVaults) }
            readProperties {
                positive(::anyoneMayReadVaultProperties)
                negative(::unauthenticatedMayNotReadVaultProperties)
            }
            commands { positive(::anyoneMayOpenVaults) }
        }
        systemContextProvider { Ctx.system() }
    }
    return Klerk.create(specification, testSettings()).also { it.meta.start() }
}

data class VaultAppViews(val vaults: VaultViews)

class VaultViews : ModelViews<Vault, Ctx>()

data class Vault(val code: VaultCode)

class VaultCode(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 100
    override val maxLines = 1
}

data class CreateVaultParams(val code: VaultCode)

object CreateVault : VoidEventWithParameters<Vault, CreateVaultParams>(External)

object OpenVault : InstanceEventNoParameters<Vault>(External)

enum class VaultStates { Locked }

private fun vaultStateMachine() = stateMachine<Vault, VaultStates, Ctx, VaultAppViews> {
    event(CreateVault) {}
    event(OpenVault) {
        validate(::codeMustBeOpen)
    }

    voidState {
        onEvent(CreateVault) {
            createModel(VaultStates.Locked, ::newVault)
        }
    }

    state(VaultStates.Locked) {
        onEvent(OpenVault) {}
    }
}

private fun newVault(args: VoidEventArgs<Vault, CreateVaultParams, Ctx, VaultAppViews>): Vault =
    Vault(args.command.params.code)

private fun codeMustBeOpen(args: InstanceEventArgs<Vault, Nothing?, Ctx, VaultAppViews>): PropertyCollectionValidity =
    if (args.model.props.code.value == "open") Valid else PropertyCollectionValidity.Invalid()

private fun anyoneMayReadVaults(args: ModelReadRuleArgs<Ctx, VaultAppViews>) = PositiveAuthorization.Allow

private fun anyoneMayReadVaultProperties(args: PropertyReadRuleArgs<Ctx, VaultAppViews>) = PositiveAuthorization.Allow

private fun unauthenticatedMayNotReadVaultProperties(args: PropertyReadRuleArgs<Ctx, VaultAppViews>) =
    if (args.context.actor == Unauthenticated) NegativeAuthorization.Deny else NegativeAuthorization.Pass

private fun anyoneMayOpenVaults(args: CommandRuleArgs<*, Ctx, VaultAppViews>) = PositiveAuthorization.Allow

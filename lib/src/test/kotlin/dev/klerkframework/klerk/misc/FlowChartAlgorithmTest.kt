package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.misc.ChannelNotificationPref.*
import dev.klerkframework.klerk.misc.ChannelNotificationPref.Nothing
import dev.klerkframework.klerk.misc.ShowNotificationDecisions.*
import dev.klerkframework.klerk.read.ReaderWithAuth
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

val algorithmParams = AlgorithmParams(
    State(threadMessage = true, userSubscribed = true, userDnd = true, dndOverride = true),
    Preferences(channelMuted = false, false, false, Everything),
    "Hejsan @everyone"
)


class FlowChartAlgorithmTest {

    private val bc = BookViews()
    private val collections = MyCollections(bc, AuthorViews(bc.all))
    private val reader = ReaderWithAuth(Klerk.create(createConfig(collections)) as KlerkImpl, Context.system())
    private val model = Model(
        ModelID(10),
        Clock.System.now(),
        Clock.System.now(),
        Clock.System.now(),
        "MyState",
        null,
        Author(
            firstName = FirstName("Astrid"),
            lastName = LastName("Lindgren"),
            address = Address(Street("Storgatan 12"))
        )
    )

    @Test
    fun `Basic algorithm`() {
        val args = ArgForInstanceEvent(model, Command(ImproveAuthor, ModelID(34), null), Context.system(), reader)
        assertEquals(4, MyAlgoWhichReturnsInt.execute(args))
        val resultWithLogs = MyAlgoWhichReturnsInt.executeWithLogs(args)
        assertEquals(4, resultWithLogs.first)
        assertEquals(
            "ChannelMuted=false -> UserInDnD=true -> DnDOverride=true -> ChannelEveryoneHereMessage=true -> Result: 4",
            resultWithLogs.second
        )
    }

    @Test
    fun `Advanced algorithm`() {
        val args = ArgForInstanceEvent(model, Command(ImproveAuthor, null, null), Context.system(), reader)
        assertEquals(true, ShouldSendNotificationAlgorithm.execute(args))
        val resultWithLogs = ShouldSendNotificationAlgorithm.executeWithLogs(args)
        assertEquals(true, resultWithLogs.first)
        assertEquals(
            "ChannelMuted=false -> UserInDnD=true -> DnDOverride=true -> ChannelEveryoneHereMessage=true -> ChannelMentionsSuppressed=false -> ThreadMessageAndUserSubscribed2=true -> ThreadsEverythingPrefOn=false -> WhatIsTheUserChannelNotificationPrefForThisDevice=Everything -> ThreadMessage1=true -> UserSubscribed1=true -> Result: true",
            resultWithLogs.second
        )
    }
}

data class AlgorithmParams(val state: State, val preferences: Preferences, val message: String)

data class State(
    val threadMessage: Boolean,
    val userSubscribed: Boolean,
    val userDnd: Boolean,
    val dndOverride: Boolean
)

data class Preferences(
    val channelMuted: Boolean,
    val channelMentionsSurpressed: Boolean,
    val threadsEverything: Boolean,
    val channelNotification: ChannelNotificationPref
)

sealed class ShowNotificationDecisions<T>(
    override val name: String,
    override val function: (ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) -> T
) : Decision<T, ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>> {

    data object ChannelMuted : ShowNotificationDecisions<Boolean>("Is channel muted?", ::isChannelMuted)

    data object ThreadMessageAndUserSubscribed :
        ShowNotificationDecisions<Boolean>("Thread message && user subscribed?", ::threadMessageAndUserSubscribed)

    data object UserInDnD : ShowNotificationDecisions<Boolean>("Is user in 'Do Not Disturb'?", ::userInDnD)

    data object DnDOverride : ShowNotificationDecisions<Boolean>("'Do Not Disturb' override?", ::dnDOverride)

    data object ChannelEveryoneHereMessage :
        ShowNotificationDecisions<Boolean>("@channel @everyone @here message?", ::channelEveryoneHereMessage)

    data object ChannelMentionsSuppressed :
        ShowNotificationDecisions<Boolean>("@channel mentions suppressed?", ::channelMentionsSuppressed)

    data object ThreadMessageAndUserSubscribed2 :
        ShowNotificationDecisions<Boolean>("Thread message && user subscribed?", ::threadMessageAndUserSubscribed)

    data object ThreadsEverythingPrefOn :
        ShowNotificationDecisions<Boolean>("threads_everything pref on?", ::threadsEverythingPrefOn)

    data object ChannelNotificationPrefIsNothing : ShowNotificationDecisions<Boolean>(
        "Channel notification pref is 'nothing'?", ::channelNotificationPrefIsNothing
    )

    data object WhatIsTheUserChannelNotificationPrefForThisDevice : ShowNotificationDecisions<ChannelNotificationPref>(
        "What is the user channel notification pref for this device?",
        ::whatIsTheUserChannelNotificationPrefForThisDevice
    )

    data object ThreadMessage1 : ShowNotificationDecisions<Boolean>("Thread message?", ::threadMessage)
    data object UserSubscribed1 : ShowNotificationDecisions<Boolean>("User subscribed?", ::userSubscribed)
}

fun whatIsTheUserChannelNotificationPrefForThisDevice(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>): ChannelNotificationPref =
    algorithmParams.preferences.channelNotification

fun channelNotificationPrefIsNothing(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) =
    algorithmParams.preferences.channelNotification == Nothing

fun userSubscribed(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) =
    algorithmParams.state.userSubscribed

fun threadMessage(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) =
    algorithmParams.state.threadMessage

enum class ChannelNotificationPref {
    Nothing,
    Everything,
    Mentions,
    Default
}


fun isChannelMuted(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) =
    algorithmParams.preferences.channelMuted

fun threadMessageAndUserSubscribed(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) =
    threadMessage(params) && userSubscribed(params)

fun userInDnD(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) = algorithmParams.state.userDnd

fun dnDOverride(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>) =
    algorithmParams.state.dndOverride

fun channelEveryoneHereMessage(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>): Boolean {
    val p = algorithmParams
    return p.message.contains("@channel") ||
            p.message.contains("@everyone") ||
            p.message.contains("@here")
}

fun channelMentionsSuppressed(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>): Boolean =
    algorithmParams.preferences.channelMentionsSurpressed

fun threadsEverythingPrefOn(params: ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>): Boolean =
    algorithmParams.preferences.threadsEverything

object MyAlgoWhichReturnsInt : FlowChartAlgorithm<ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>, Int>("Just testing") {

    override fun configure(): AlgorithmBuilder<ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>, Int>.() -> Unit =
        {

            start(ChannelMuted)

            booleanNode(ChannelMuted) {
                on(true, next = ThreadMessageAndUserSubscribed)
                on(false, next = UserInDnD)
            }

            booleanNode(ThreadMessageAndUserSubscribed) {
                on(true, next = UserInDnD)
                on(false, terminateWith = 1)
            }

            booleanNode(UserInDnD) {
                on(true, next = DnDOverride)
                on(false, terminateWith = 2)
            }

            booleanNode(DnDOverride) {
                on(true, next = ChannelEveryoneHereMessage)
                on(false, terminateWith = 3)
            }

            booleanNode(ChannelEveryoneHereMessage) {
                on(true, terminateWith = 4)
                on(false, terminateWith = 5)
            }

        }

}

// Note that the functions in this algorithm are not pure since they use algorithmParams rather than the BlockParams.
// The reason of this is that we want to test with a complicated algorithm (inspired by https://d34u8crftukxnk.cloudfront.net/slackpress/prod/sites/7/0_PV_09olld6K1l8jQ.png)
object ShouldSendNotificationAlgorithm : FlowChartAlgorithm<ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>, Boolean>("Should we send a notification?")  {

    override fun configure(): AlgorithmBuilder<ArgForInstanceEvent<Author, kotlin.Nothing?, Context, MyCollections>, Boolean>.() -> Unit = {
            start(ChannelMuted)

            booleanNode(ChannelMuted) {
                on(true, next = ThreadMessageAndUserSubscribed)
                on(false, next = UserInDnD)
            }

            booleanNode(ThreadMessageAndUserSubscribed) {
                on(true, next = UserInDnD)
                on(false, terminateWith = false)
            }

            booleanNode(UserInDnD) {
                on(true, next = DnDOverride)
                on(false, terminateWith = true)
            }

            booleanNode(DnDOverride) {
                on(true, next = ChannelEveryoneHereMessage)
                on(false, terminateWith = false)
            }

            booleanNode(ChannelEveryoneHereMessage) {
                on(true, next = ChannelMentionsSuppressed)
                on(false, next = ThreadMessageAndUserSubscribed2)
            }

            booleanNode(ChannelMentionsSuppressed) {
                on(true, next = ThreadsEverythingPrefOn)
                on(false, next = ThreadMessageAndUserSubscribed2)
            }

            booleanNode(ThreadsEverythingPrefOn) {
                on(true, next = ChannelNotificationPrefIsNothing)
                on(false, next = WhatIsTheUserChannelNotificationPrefForThisDevice)
            }

            booleanNode(ChannelNotificationPrefIsNothing) {
                on(true, terminateWith = false)
                on(false, terminateWith = true)
            }

            booleanNode(ThreadMessageAndUserSubscribed2) {
                on(true, next = ThreadsEverythingPrefOn)
                on(false, next = WhatIsTheUserChannelNotificationPrefForThisDevice)
            }

            enumNode(WhatIsTheUserChannelNotificationPrefForThisDevice) {
                on(Nothing, terminateWith = false)
                on(Mentions, terminateWith = true)          //
                on(Everything, next = ThreadMessage1)
                on(Default, terminateWith = true)           //
            }

            booleanNode(ThreadMessage1) {
                on(true, next = UserSubscribed1)
                on(false, terminateWith = true)
            }

            booleanNode(UserSubscribed1) {
                on(true, terminateWith = true)
                on(false, terminateWith = false)    //
            }

        }

}

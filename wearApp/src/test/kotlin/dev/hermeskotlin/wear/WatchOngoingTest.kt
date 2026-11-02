package dev.hermeskotlin.wear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchOngoingTest {

    @Test
    fun aCallIsKeptGoingFromItsStartToItsEnd() {
        assertNull(ongoingCall(null))
        // Getting there, it says what the call screen does.
        assertEquals(OngoingCall(WatchLine.Res(R.string.wear_call_hermes), liveSince = null, caption = WatchLine.Res(R.string.wear_call_starting)), ongoingCall(WatchCallState("c1", CallStage.Starting)))
        assertEquals(OngoingCall(WatchLine.Res(R.string.wear_call_hermes), liveSince = null, caption = WatchLine.Res(R.string.wear_call_connecting)), ongoingCall(WatchCallState("c1", CallStage.Connecting)))
        assertEquals(OngoingCall(WatchLine.Res(R.string.wear_call_hermes), liveSince = 1_000L, caption = WatchLine.Res(R.string.wear_call_live)), ongoingCall(WatchCallState("c1", CallStage.Live, liveSince = 1_000L)))
        // Over, it's let go, though its screen still shows how it ended.
        assertNull(ongoingCall(WatchCallState("c1", CallStage.Ended, error = "The call dropped.")))
    }

    @Test
    fun itNamesTheChatCalledNotTheDefaultTarget() {
        assertEquals(WatchLine.Res(R.string.wear_call_target, listOf("Trip plans")), ongoingCall(WatchCallState("c1", CallStage.Live, liveSince = 1_000L, title = "Trip plans"))?.title)
        // A chat with no name yet.
        assertEquals(WatchLine.Res(R.string.wear_call_hermes), ongoingCall(WatchCallState("c1", CallStage.Starting, title = " "))?.title)
    }

    @Test
    fun whatTheCallSaysAlongTheWayDoesntPostItAgain() {
        val live = WatchCallState("c1", CallStage.Live, liveSince = 1_000L)
        assertEquals(ongoingCall(live), ongoingCall(live.copy(caption = "Hello", speaking = true, level = 0.4f, muted = true, thinking = true)))
    }
}

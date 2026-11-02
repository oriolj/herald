package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplyUpdate
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

    private fun turn(phase: ReplyPhase) = WatchTurn("a1", "What's on today?", ReplyUpdate("a1", phase))

    @Test
    fun hermesAtWorkOrSpeakingIsKeptInFrontOutsideACall() {
        assertEquals(R.string.wear_ongoing_working, ongoingWork(turn(ReplyPhase.Sending), inCall = false, speaking = false, voiceComing = false))
        assertEquals(R.string.wear_ongoing_working, ongoingWork(turn(ReplyPhase.Working), inCall = false, speaking = false, voiceComing = false))
        // The reply read aloud after the turn is done.
        assertEquals(R.string.wear_ongoing_speaking, ongoingWork(turn(ReplyPhase.Done), inCall = false, speaking = true, voiceComing = false))
        // Done, its voice still being made: not let go before it's heard.
        assertEquals(R.string.wear_ongoing_working, ongoingWork(turn(ReplyPhase.Done), inCall = false, speaking = false, voiceComing = true))
        assertNull(ongoingWork(turn(ReplyPhase.Done), inCall = false, speaking = false, voiceComing = false))
        assertNull(ongoingWork(null, inCall = false, speaking = false, voiceComing = false))
    }

    @Test
    fun noneWhileACallHasItOrTheAnswerIsOnThePhone() {
        assertNull(ongoingWork(turn(ReplyPhase.Working), inCall = true, speaking = true, voiceComing = false))
        assertNull(ongoingWork(turn(ReplyPhase.WaitingOnPhone), inCall = false, speaking = false, voiceComing = false))
    }
}

package dev.hermeskotlin.core.voice

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameVoiceRecorderTest {

    /**
     * A microphone that hears [script], one sample value per 20 ms frame, then quiet; [onFrame] runs before each
     * frame is read, by its index. [opens] false: it can't be opened. [stopsAfter]: it fails after that many frames;
     * [goneAfter]: it goes, as a watch's does when its call is over.
     */
    private class Scripted(
        private val script: List<Short>,
        private val opens: Boolean = true,
        private val stopsAfter: Int = Int.MAX_VALUE,
        private val goneAfter: Int = Int.MAX_VALUE,
        private val onFrame: Scripted.(Int) -> Unit = {},
    ) : FrameVoiceRecorder() {
        var uses = mutableListOf<MicUse>()

        override suspend fun <T> listen(use: MicUse, frames: suspend (read: MicRead) -> T): T? {
            uses += use
            if (!opens) return null
            var index = 0
            return frames { frame, offset, length ->
                if (index >= stopsAfter) error("The microphone was disconnected.")
                if (index >= goneAfter) return@frames FrameVoiceRecorder.MIC_GONE
                onFrame(index)
                // Half a frame at a time: it's still measured in whole frames.
                val count = minOf(length, FrameVoiceRecorder.FRAME_SAMPLES / 2)
                frame.fill(script.getOrElse(index) { 0 }, offset, offset + count)
                if (offset + count == frame.size) index++
                count
            }
        }
    }

    /** [quiet] frames of quiet, then [loud] frames of someone talking. */
    private fun speech(quiet: Int = 25, loud: Int = 50): List<Short> = List(quiet) { 0.toShort() } + List(loud) { LOUD }

    private fun Recording.samples(): List<Short> =
        bytes.drop(44).chunked(2).map { (low, high) -> ((high.toInt() shl 8) or (low.toInt() and 0xFF)).toShort() }

    @Test
    fun aRecordingEndsWithThePauseAfterSpeech() = runTest {
        val recorder = Scripted(speech())
        var spoke = 0
        val recording = recorder.record(VoiceActivity(), onSpeech = { spoke++ })

        assertTrue(recording.heardSpeech)
        assertEquals(1, spoke)
        assertEquals("audio/wav", recording.mimeType)
        assertTrue(LOUD in recording.samples())
        assertEquals(listOf(MicUse.Record), recorder.uses)
    }

    @Test
    fun finishedEarlyItKeepsWhatWasSaidSoFar() = runTest {
        lateinit var recorder: Scripted
        recorder = Scripted(List(60) { LOUD }, onFrame = { if (it == 60) finish() })
        val recording = recorder.record(VoiceActivity(silenceMs = 60_000))

        assertTrue(recording.heardSpeech)
        // Whole frames only: the one being read as it's finished isn't.
        assertEquals(60 * FrameVoiceRecorder.FRAME_SAMPLES, recording.samples().size)
    }

    @Test
    fun aMicrophoneThatStopsEndsTheRecordingWithWhatWasSaid() = runTest {
        val recording = Scripted(speech(), goneAfter = 60).record(VoiceActivity())

        assertTrue(recording.heardSpeech)
        assertEquals(60 * FrameVoiceRecorder.FRAME_SAMPLES, recording.samples().size)
    }

    @Test
    fun aMicrophoneThatWontOpenSaysSo() = runTest {
        assertFailsWith<IllegalStateException> { Scripted(speech(), opens = false).record(VoiceActivity()) }
    }

    @Test
    fun listeningOverTheVoiceGivesUpWhenTheMicrophoneCantListen() = runTest {
        val bargeIn = BargeIn(BargeInSettings(), VoiceActivity().silenceMs)
        val closed = Scripted(speech(), opens = false)
        assertNull(closed.listenOver(bargeIn, playing = { false }, onTrip = {}))

        // One that stops says why, as a recording does.
        val stopped = Scripted(speech(), stopsAfter = 10)
        var listening = false
        val failure = assertFailsWith<IllegalStateException> { stopped.listenOver(bargeIn, playing = { false }, onTrip = {}, onListening = { listening = true }) }
        assertEquals("The microphone was disconnected.", failure.message)
        assertTrue(listening)
        assertEquals(listOf(MicUse.ListenOver), stopped.uses)
    }

    @Test
    fun aMicrophoneThatSendsNothingFailsTheRecording() = runTest {
        val silent = object : FrameVoiceRecorder(noAudioMs = 50) {
            override suspend fun <T> listen(use: MicUse, frames: suspend (read: MicRead) -> T): T? = frames { _, _, _ -> 0 }
        }
        val failure = assertFailsWith<IllegalStateException> { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { silent.record(VoiceActivity()) } }
        assertEquals("The microphone stopped sending audio.", failure.message)
    }

    @Test
    fun theLevelIsDesktops() {
        assertEquals(0f, FrameVoiceRecorder.level(ShortArray(4), 4))
        // 256 × 42 is Desktop's 1.0, and louder stays there.
        assertEquals(1f, FrameVoiceRecorder.level(ShortArray(4) { (256 * 42).toShort() }, 4))
        assertEquals(1f, FrameVoiceRecorder.level(ShortArray(4) { Short.MAX_VALUE }, 4))
    }

    private companion object {
        const val LOUD: Short = 4_000
    }
}

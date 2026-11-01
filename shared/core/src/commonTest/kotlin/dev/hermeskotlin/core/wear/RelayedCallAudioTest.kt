package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.voice.FrameVoiceRecorder
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayedCallAudioTest {

    /** The watch's samples as they come off its stream: [frames] 20 ms frames of [sample]. */
    private fun stream(frames: Int, sample: Short): ByteArray {
        val bytes = ByteArray(frames * FrameVoiceRecorder.FRAME_SAMPLES * 2)
        for (i in 0 until bytes.size / 2) {
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return bytes
    }

    /**
     * [bytes] fed in uneven pieces, as a channel reads them, and in time: the recording takes in each piece before the
     * next, as it does a watch streaming as it hears.
     */
    private fun TestScope.feedLive(mic: WatchMicRecorder, bytes: ByteArray) {
        val start = testScheduler.currentTime
        var at = 0
        var size = 1
        while (at < bytes.size) {
            val piece = bytes.copyOfRange(at, minOf(bytes.size, at + size))
            mic.feed(piece)
            at += piece.size
            size = size * 3 % 1_000 + 7
            // As long passes as what's been fed lasts: 16 samples a millisecond, 2 bytes each.
            val due = start + at / 32L
            if (due > testScheduler.currentTime) advanceTimeBy(due - testScheduler.currentTime)
            runCurrent()
        }
    }

    @Test
    fun theWatchsMicrophoneIsHeardAsThePhonesWouldBe() = runTest {
        val mic = WatchMicRecorder()
        assertFalse(mic.heard)
        val recording = async { mic.record(VoiceActivity()) }
        runCurrent()
        // Quiet, someone talking for a second, then a pause long enough to end it.
        feedLive(mic, stream(25, 0) + stream(50, 4_000) + stream(70, 0))

        assertTrue(mic.heard)
        assertTrue(recording.await().heardSpeech)
    }

    @Test
    fun whatCameWhileNobodyListenedIsThePast() = runTest {
        val mic = WatchMicRecorder()
        // Talking before the recording starts (the voice's own echo, say) isn't what's said next.
        mic.feed(stream(40, 4_000))
        val recording = async { mic.record(VoiceActivity(idleMs = 1_000)) }
        runCurrent()
        mic.feed(stream(60, 0))

        assertFalse(recording.await().heardSpeech)
    }

    @Test
    fun aWatchThatStopsStreamingEndsTheRecording() = runTest {
        val mic = WatchMicRecorder()
        val recording = async { mic.record(VoiceActivity()) }
        runCurrent()
        mic.feed(stream(25, 0) + stream(30, 4_000))
        runCurrent()
        mic.close()

        assertTrue(recording.await().heardSpeech)
    }

    private class Sent : WatchOutbox {
        val paths = mutableListOf<String>()
        val bytes = mutableListOf<ByteArray>()
        override suspend fun send(node: String, path: String, data: ByteArray) = true
        override suspend fun stream(node: String, path: String, data: ByteArray): Boolean {
            paths += path
            bytes += data
            return true
        }
    }

    @Test
    fun theStreamedVoiceGoesInNumberedPiecesAndIsDoneOncePlayed() = runTest {
        val sent = Sent()
        val speaker = WatchSpeaker("watch", "c1", sent)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        // A second at 24 kHz mono, in small chunks: it goes as four pieces of a quarter second.
        repeat(48) { chunks.trySend(ByteArray(1_000)) }
        chunks.close()
        val sounds = mutableListOf<Boolean>()
        val playing = launch { speaker.playPcm(24_000, 1, chunks) { sounds += it } }
        runCurrent()

        assertEquals((0..3).map { WearPaths.callSoundPath("c1", it, WearPaths.PCM, 24_000, 1) }, sent.paths)
        assertEquals(48_000, sent.bytes.sumOf { it.size })
        assertEquals(listOf(true), sounds)
        assertFalse(playing.isCompleted)

        speaker.played(3)
        runCurrent()
        assertTrue(playing.isCompleted)
        assertEquals(listOf(true, false), sounds)
    }

    @Test
    fun aClipIsDoneOnceTheWatchHasPlayedIt() = runTest {
        val sent = Sent()
        val speaker = WatchSpeaker("watch", "c1", sent)
        val playing = launch { speaker.play(SpokenAudio(byteArrayOf(1, 2, 3), "audio/mpeg")) }
        runCurrent()
        assertEquals(listOf(WearPaths.callSoundPath("c1", 0, "audio/mpeg")), sent.paths)
        assertFalse(playing.isCompleted)

        speaker.played(0)
        runCurrent()
        assertTrue(playing.isCompleted)
    }
}

package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.voice.BargeIn
import dev.hermeskotlin.core.voice.FrameVoiceRecorder
import dev.hermeskotlin.core.voice.MicRead
import dev.hermeskotlin.core.voice.MicUse
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The watch's microphone on a relayed call, as the phone's voice chat records with: what the watch streams
 * ([WearPaths.callMicPath]) comes in through [feed], in 20 ms frames. The stream never stops while the call is on,
 * so what came while nobody listened is the past: each recording starts from now. Closed ([close]), it hears
 * nothing more, and a recording ends as when the phone's own microphone stops.
 */
class WatchMicRecorder : FrameVoiceRecorder() {
    private val frames = Channel<ShortArray>(BUFFERED_FRAMES, BufferOverflow.DROP_OLDEST)
    private val partial = ByteArray(FRAME_SAMPLES * 2)
    private var partialSize = 0

    /** Whether the watch's microphone has started coming in. */
    @kotlin.concurrent.Volatile var heard = false
        private set

    /** [count] bytes off the watch's stream, in whatever pieces they come; called from one reader at a time. */
    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        heard = true
        var at = 0
        while (at < count) {
            val take = minOf(partial.size - partialSize, count - at)
            bytes.copyInto(partial, partialSize, at, at + take)
            partialSize += take
            at += take
            if (partialSize == partial.size) {
                frames.trySend(ShortArray(FRAME_SAMPLES) { i -> ((partial[i * 2 + 1].toInt() shl 8) or (partial[i * 2].toInt() and 0xFF)).toShort() })
                partialSize = 0
            }
        }
    }

    /**
     * Not yet: the watch's speaker is right by its microphone and louder in it than the phone's is in its own, so
     * talking over the voice doesn't cut in on a relayed call until that's been tried on a watch.
     */
    override suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        onMeter: (Float) -> Unit,
    ): Recording? = awaitCancellation()

    /** The watch stopped streaming: the call is over, or dropped. */
    fun close() {
        frames.close()
    }

    override suspend fun <T> listen(use: MicUse, frames: suspend (read: MicRead) -> T): T? {
        while (this.frames.tryReceive().isSuccess) Unit
        if (this.frames.isClosedForReceive) return null
        // A frame handed over in parts, when asked for less than one.
        var pending: ShortArray? = null
        var pendingAt = 0
        return frames { into, offset, length ->
            val frame = pending ?: run {
                val next = this.frames.tryReceive()
                if (next.isClosed) return@frames MIC_GONE
                pendingAt = 0
                next.getOrNull() ?: return@frames 0
            }
            val count = minOf(length, frame.size - pendingAt)
            frame.copyInto(into, offset, pendingAt, pendingAt + count)
            pendingAt += count
            pending = frame.takeIf { pendingAt < it.size }
            count
        }
    }

    private companion object {
        /** A second of the watch's microphone, kept while a recording catches up; older frames give way. */
        const val BUFFERED_FRAMES = 50
    }
}

/**
 * The watch's speaker on a relayed call, as the phone's voice chat plays with: each clip, and the streamed voice in
 * pieces of a fraction of a second, goes to the watch numbered in order ([WearPaths.callSoundPath]), and is done once
 * the watch says it has played it ([played]). A watch that never says so is given up on after about as long as the
 * sound lasts, and a little more, so the chat never stalls on it.
 */
class WatchSpeaker(private val node: String, private val callId: String, private val outbox: WatchOutbox) : SpeechPlayer {
    private var next = 0
    private val playedUpTo = MutableStateFlow(-1)

    /** The watch has played sound [index], and so everything before it. */
    fun played(index: Int) {
        playedUpTo.update { maxOf(it, index) }
    }

    override suspend fun play(audio: SpokenAudio) {
        val index = next++
        if (!outbox.streamSafely(node, WearPaths.callSoundPath(callId, index, audio.mimeType), audio.bytes)) return
        awaitPlayed(index, CLIP_WITHIN_MS)
    }

    override suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) {
        val bytesPerMs = sampleRate * channels * 2 / 1000
        val pieceBytes = bytesPerMs * PIECE_MS
        var piece = ByteArray(0)
        var last = -1
        var sentMs = 0L
        suspend fun send() {
            if (piece.isEmpty()) return
            val index = next++
            if (last < 0) onSound(true)
            last = index
            sentMs += piece.size / bytesPerMs.coerceAtLeast(1)
            outbox.streamSafely(node, WearPaths.callSoundPath(callId, index, WearPaths.PCM, sampleRate, channels), piece)
            piece = ByteArray(0)
        }
        try {
            for (chunk in chunks) {
                piece += chunk
                if (piece.size >= pieceBytes) send()
            }
            send()
            if (last >= 0) awaitPlayed(last, sentMs + SLACK_MS)
        } finally {
            if (last >= 0) onSound(false)
        }
    }

    private suspend fun awaitPlayed(index: Int, withinMs: Long) {
        withTimeoutOrNull(withinMs) { playedUpTo.first { it >= index } }
    }

    private companion object {
        /** The streamed voice goes in pieces this long: few enough channels, soon enough sound. */
        const val PIECE_MS = 250
        /** How long a clip may take to play: its length isn't known here. */
        const val CLIP_WITHIN_MS = 60_000L
        /** On top of how long the streamed voice lasts: getting there, and the watch's buffer. */
        const val SLACK_MS = 5_000L
    }
}

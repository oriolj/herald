package dev.hermeskotlin.wear

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.OutputStream
import java.util.TreeMap
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A call the watch holds: a live one ([WatchCall], straight to the voice model), or the phone's own voice chat
 * relayed through the watch ([RelayedCall]).
 */
interface HeldCall {
    /** The voice model's events, for the phone; a relayed call has none, and this ends when it's over. */
    val events: ReceiveChannel<String>

    /** What the phone needs to start the call: a live call's offer, nothing for a relayed one. */
    suspend fun offer(): String

    /** The phone's answer: a live call connects with it, a relayed one starts sending what the microphone hears. */
    suspend fun answer(sdp: String)

    fun send(event: String)

    /** The voice is talking now. */
    fun speaking(): Boolean

    fun micLevel(): Float

    fun setMuted(muted: Boolean)

    fun close()
}

/**
 * The phone's voice chat, its microphone and speaker the watch's: what the microphone hears streams to the phone
 * ([WearPaths.callMicPath], once [answer]ed), silence while muted, and what the phone sends ([sound]) plays in the
 * order it was sent, each piece said back to the phone once played ([onPlayed]) so the chat knows when the voice is
 * done. The phone decides the rest: when you're done talking, what's said back, and in whose voice.
 */
class RelayedCall(
    private val context: Context,
    /** Opens the stream to the phone the microphone goes on; null when the phone can't be reached. */
    private val openMic: suspend () -> OutputStream?,
    private val onPlayed: (Int) -> Unit,
) : HeldCall {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incoming = Channel<String>()
    override val events: ReceiveChannel<String> = incoming

    @Volatile private var muted = false
    @Volatile private var level = 0f
    private var mic: Job? = null

    private val lock = Any()
    /** Sounds come on their own channels, so they may arrive out of order: each waits for the one before it. */
    private val waiting = TreeMap<Int, Sound>()
    private var nextToPlay = 0
    private val arrived = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var playing = false

    private class Sound(val mimeType: String, val sampleRate: Int, val channels: Int, val bytes: ByteArray)

    init {
        scope.launch { playInOrder() }
    }

    override suspend fun offer(): String = ""

    override suspend fun answer(sdp: String) {
        synchronized(lock) {
            if (mic != null) return
            mic = scope.launch { streamMicrophone() }
        }
    }

    /** Sound [index] from the phone: a clip as [mimeType], or raw samples at [sampleRate] with [channels]. */
    fun sound(index: Int, mimeType: String, sampleRate: Int, channels: Int, bytes: ByteArray) {
        synchronized(lock) {
            if (index < nextToPlay) return
            waiting[index] = Sound(mimeType, sampleRate, channels, bytes)
        }
        arrived.trySend(Unit)
    }

    override fun send(event: String) = Unit

    override fun speaking(): Boolean = playing || synchronized(lock) { waiting.isNotEmpty() }

    override fun micLevel(): Float = if (muted) 0f else level

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun close() {
        incoming.close()
        arrived.close()
        scope.cancel()
    }

    @SuppressLint("MissingPermission") // The call asks for RECORD_AUDIO before it starts.
    private suspend fun streamMicrophone() {
        val output = openMic() ?: return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_SAMPLES * 2 * 4))
        } catch (_: Exception) {
            runCatching { output.close() }
            return
        }
        // The watch's speaker is right by its microphone: its own echo cancelling, where it has one, keeps the voice out.
        val effects = ArrayList<AudioEffect>(2)
        try {
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
            effects.forEach { runCatching { it.enabled = true } }
            record.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            val bytes = ByteArray(FRAME_SAMPLES * 2)
            while (scope.isActive) {
                val read = record.read(frame, 0, frame.size)
                if (read < 0) break
                if (read == 0) continue
                if (muted) frame.fill(0, 0, read)
                var sum = 0.0
                for (i in 0 until read) {
                    val sample = frame[i].toInt()
                    sum += sample.toDouble() * sample
                    bytes[i * 2] = sample.toByte()
                    bytes[i * 2 + 1] = (sample shr 8).toByte()
                }
                // Desktop's level, as the phone measures it.
                level = min(1.0, sqrt(sum / read) / 256.0 / 42.0).toFloat()
                output.write(bytes, 0, read * 2)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The phone went out of reach: the call notices it stopped hearing from it.
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
            runCatching { output.close() }
        }
    }

    /** Plays what the phone sent, one sound after another, as each next one is there. */
    private suspend fun playInOrder() {
        var track: AudioTrack? = null
        var trackFormat: Pair<Int, Int>? = null
        // The streamed voice's pieces, written to the track and not yet played: the frame each ends at.
        val unplayed = ArrayDeque<Pair<Int, Long>>()
        var written = 0L
        try {
            while (scope.isActive) {
                val next = synchronized(lock) { waiting.remove(nextToPlay)?.also { nextToPlay++ } }
                if (next == null) {
                    // Nothing new: say what the track has played meanwhile, and wait for the next sound.
                    val head = track?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
                    while (unplayed.isNotEmpty() && unplayed.first().second <= head) onPlayed(unplayed.removeFirst().first)
                    playing = unplayed.isNotEmpty()
                    if (unplayed.isEmpty()) arrived.receiveCatching().getOrNull() ?: return else delay(POLL_MS)
                    continue
                }
                val index = nextToPlay - 1
                playing = true
                if (next.mimeType != WearPaths.PCM) {
                    playClip(next)
                    onPlayed(index)
                    continue
                }
                val format = next.sampleRate to next.channels
                if (track == null || trackFormat != format) {
                    track?.let { it.stop(); it.release() }
                    track = pcmTrack(next.sampleRate, next.channels)
                    trackFormat = format
                    written = 0
                    track.play()
                }
                track.write(next.bytes, 0, next.bytes.size)
                written += next.bytes.size / (2 * next.channels)
                unplayed.addLast(index to written)
            }
        } finally {
            track?.let { runCatching { it.stop() }; it.release() }
            playing = false
        }
    }

    private fun pcmTrack(sampleRate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(VOICE)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * channels * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** A clip, through a file, as MediaPlayer reads them; done when it has played, or couldn't. */
    private suspend fun playClip(sound: Sound) {
        val file = File.createTempFile("call", ".audio", context.cacheDir)
        try {
            file.writeBytes(sound.bytes)
            suspendCancellableCoroutine { done ->
                val player = MediaPlayer()
                done.invokeOnCancellation { runCatching { player.release() } }
                try {
                    player.setAudioAttributes(VOICE)
                    player.setDataSource(file.path)
                    player.setOnCompletionListener {
                        it.release()
                        if (done.isActive) done.resume(Unit)
                    }
                    player.setOnErrorListener { p, _, _ ->
                        p.release()
                        if (done.isActive) done.resume(Unit)
                        true
                    }
                    player.prepare()
                    player.start()
                } catch (_: Exception) {
                    player.release()
                    if (done.isActive) done.resume(Unit)
                }
            }
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        const val POLL_MS = 40L
        val VOICE: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

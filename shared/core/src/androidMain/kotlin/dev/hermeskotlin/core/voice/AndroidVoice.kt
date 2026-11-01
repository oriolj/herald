package dev.hermeskotlin.core.voice

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Records 16 kHz mono PCM with [AudioRecord] and hands it over as WAV, which every speech-to-text
 * provider takes. The level is Desktop's: RMS of the waveform, scaled so 1.0 is loud speech. While a voice chat
 * plays as a call ([callAudio]), it listens with the call's microphone: the car's or the headset's.
 */
class AndroidVoiceRecorder(
    /** The voice chat's call audio, whose microphone it listens with while one plays as a call; none, never as one. */
    private val callAudio: AndroidCallAudio? = null,
    /** How long a microphone may send nothing before the recording fails. */
    noAudioMs: Long = FrameVoiceRecorder.NO_AUDIO_MS,
) : FrameVoiceRecorder(noAudioMs) {

    /**
     * Recording, the speech-recognition microphone with noise suppression only: automatic gain lifts the room's hum in
     * every pause until it reads as talking, and the end of speech is never found. Listening over the voice, the call
     * microphone with the phone's echo cancelling (and noise suppression) on it, so the voice coming out of the speaker
     * is mostly gone from what it hears.
     */
    @SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before starting.
    override suspend fun <T> listen(use: MicUse, frames: suspend (read: MicRead) -> T): T? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                if (use == MicUse.Record) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
        } catch (_: Exception) {
            return@withContext null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }
        callAudio?.microphone?.let { record.preferredDevice = it }
        val effects = ArrayList<AudioEffect>(2)
        try {
            // Inside the try, so the recorder is let go whatever they do; one that won't switch on is done without.
            if (use == MicUse.ListenOver && AcousticEchoCanceler.isAvailable()) {
                runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
            }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
            effects.forEach { runCatching { it.enabled = true } }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
            // Reads don't block, so cancelling and Finish reach the loop even when the microphone stops sending.
            frames { frame, offset, length ->
                val read = record.read(frame, offset, length, AudioRecord.READ_NON_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT) error("The microphone was disconnected.")
                read
            }
        } finally {
            runCatching { record.stop() }
            // One that throws mustn't keep the microphone from being let go.
            effects.forEach { runCatching { it.release() } }
            record.release()
        }
    }
}

/**
 * Plays each reply clip from a cache file with [MediaPlayer], and streamed speech straight into an
 * [AudioTrack] as it arrives, as assistant audio: as a call's while a chained voice chat plays as one ([callAudio]),
 * and as media while it takes the media buttons ([mediaButtons]), which Android sends to the app that last played media.
 */
class AndroidSpeechPlayer(
    private val context: Context,
    private val callAudio: AndroidCallAudio = AndroidCallAudio(context),
    private val mediaButtons: AndroidMediaButtons = AndroidMediaButtons(context),
) : SpeechPlayer {
    override suspend fun playRingback() {
        val bytes = withContext(Dispatchers.IO) { context.assets.open("call_ringback.wav").use { it.readBytes() } }
        play(SpokenAudio(bytes, "audio/wav"))
    }

    private val lock = Any()
    /** Held ([hold]): what plays now is paused, and what's ready meanwhile waits to start. */
    private var holding = false
    private var track: AudioTrack? = null
    /** A clip once it's ready to play. */
    private var clip: MediaPlayer? = null

    override fun chainedChatAudio(): () -> Unit = callAudio.chat()

    override suspend fun chainedCallAudio(speaker: Boolean): (() -> Unit)? = callAudio.call(speaker)

    override fun hold(on: Boolean): Boolean {
        synchronized(lock) {
            holding = on
            track?.let { runCatching { if (on) it.pause() else it.play() } }
            clip?.let { runCatching { if (on) { if (it.isPlaying) it.pause() } else if (!it.isPlaying) it.start() } }
        }
        return true
    }

    override suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) = coroutineScope {
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Can't play $channels-channel speech.")
        }
        // A frame is a 16-bit sample for each channel.
        val frameBytes = 2 * channels
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage())
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            // Half a second of room: enough to ride out the network's hiccups, little enough to stop at once.
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), sampleRate * channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Frames handed to the track; the head position counts the ones already heard.
        val written = AtomicLong()
        fun played(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val sound = launch(Dispatchers.Default) {
            var on = false
            var quietSince = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val pending = played() < written.get()
                if (pending && !on) {
                    on = true
                    onSound(true)
                } else if (!pending && on) {
                    if (quietSince == 0L) quietSince = now
                    // The short gaps between sentences aren't the voice stopping.
                    if (now - quietSince >= QUIET_MS) {
                        on = false
                        onSound(false)
                    }
                }
                if (pending) quietSince = 0L
                delay(POLL_MS)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    this@AndroidSpeechPlayer.track = track
                    // Held, it fills its buffer and waits to be let go.
                    if (!holding) track.play()
                }
                // Provider chunks needn't end on a whole frame; what's left over waits for the next chunk.
                var carry = ByteArray(0)
                for (chunk in chunks) {
                    val bytes = if (carry.isEmpty()) chunk else carry + chunk
                    val usable = bytes.size - bytes.size % frameBytes
                    carry = bytes.copyOfRange(usable, bytes.size)
                    var offset = 0
                    while (offset < usable) {
                        ensureActive()
                        val n = track.write(bytes, offset, usable - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) error("Couldn't play the reply ($n).")
                        offset += n
                        written.addAndGet(n.toLong() / frameBytes)
                        if (n == 0) delay(WRITE_WAIT_MS)
                    }
                }
                // What the track still holds, counted before stopping: a stopped track's position can read 0, so
                // waiting for it to catch up would sit through the whole reply's length again in silence.
                val left = (written.get() - played()).coerceAtLeast(0)
                // Nor can it tell sound from silence any more: what's showing now stays until the end.
                sound.cancelAndJoin()
                // Stopping a streaming track plays out what it holds first. Held meanwhile, it pauses where it is and
                // goes on when let go, so the wait does too.
                track.stop()
                var draining = left * 1000 / sampleRate + DRAIN_SLACK_MS
                while (draining > 0) {
                    val step = min(draining, DRAIN_STEP_MS)
                    delay(step)
                    if (!synchronized(lock) { holding }) draining -= step
                }
            }
        } finally {
            withContext(NonCancellable) { sound.cancelAndJoin() }
            onSound(false)
            synchronized(lock) { if (this@AndroidSpeechPlayer.track === track) this@AndroidSpeechPlayer.track = null }
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    override suspend fun play(audio: SpokenAudio) {
        val extension = when {
            "ogg" in audio.mimeType || "opus" in audio.mimeType -> "ogg"
            "wav" in audio.mimeType -> "wav"
            "flac" in audio.mimeType -> "flac"
            else -> "mp3"
        }
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("hermes-speech-", ".$extension", context.cacheDir).apply { writeBytes(audio.bytes) }
        }
        val player = MediaPlayer()
        try {
            suspendCancellableCoroutine { continuation ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage())
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setOnCompletionListener {
                    // Done: letting go of a hold mustn't start it over.
                    synchronized(lock) { if (clip === player) clip = null }
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, what, _ ->
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Couldn't play the reply ($what)."))
                    true
                }
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener {
                    synchronized(lock) {
                        clip = it
                        if (!holding) it.start()
                    }
                }
                player.prepareAsync()
                continuation.invokeOnCancellation { runCatching { player.stop() } }
            }
        } finally {
            synchronized(lock) { if (clip === player) clip = null }
            player.release()
            file.delete()
        }
    }

    /** A call's voice goes down the call channel; the assistant's would stay on the media one, the car's music. */
    private fun usage() = when {
        callAudio.on -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        mediaButtons.on -> AudioAttributes.USAGE_MEDIA
        else -> AudioAttributes.USAGE_ASSISTANT
    }

    private companion object {
        const val POLL_MS = 50L
        const val QUIET_MS = 600L
        const val WRITE_WAIT_MS = 10L
        const val DRAIN_SLACK_MS = 400L
        /** How often the drain looks whether it's held. */
        const val DRAIN_STEP_MS = 50L
    }
}

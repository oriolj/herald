package dev.hermeskotlin.core.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** What a recorder opens its microphone for, which decides how it's opened ([FrameVoiceRecorder.listen]). */
enum class MicUse {
    /** One thing said, for the gateway to write down: the speech-recognition microphone, noise suppressed. */
    Record,

    /** Listening over the voice for someone cutting in: the call microphone, with its echo cancelling. */
    ListenOver,
}

/**
 * Reads up to `length` samples into the array from `offset`, without waiting: how many it put there, 0 when there are
 * none yet, [FrameVoiceRecorder.MIC_GONE] once the microphone is gone for good, below 0 otherwise (or a throw) when it
 * failed.
 */
typealias MicRead = suspend (frame: ShortArray, offset: Int, length: Int) -> Int

/**
 * A [VoiceRecorder] whatever its microphone is: the phone's own, or one streaming from elsewhere. It reads 16 kHz
 * mono 16-bit frames, 20 ms each, from what [listen] opens, and decides from them when the speaker is done
 * ([EndOfSpeech]) or cuts in over the voice ([BargeIn]). The level is Desktop's: RMS of the waveform, scaled so 1.0 is
 * loud speech.
 */
abstract class FrameVoiceRecorder(
    /** How long a microphone may send nothing before the recording fails. */
    private val noAudioMs: Long = NO_AUDIO_MS,
) : VoiceRecorder {

    @kotlin.concurrent.Volatile private var finishRequested = false

    /**
     * Opens the microphone for [use] and runs [frames] on it, reading with the [MicRead] it's given, then lets it go.
     * Null when it couldn't be opened. Runs on whatever thread reading needs.
     */
    protected abstract suspend fun <T> listen(use: MicUse, frames: suspend (read: MicRead) -> T): T?

    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
        finishRequested = false
        return listen(MicUse.Record) { read ->
            val pcm = Buffer()
            val frame = ShortArray(FRAME_SAMPLES)
            val endOfSpeech = EndOfSpeech(activity)
            var elapsed = 0L
            try {
                while (!finishRequested) {
                    if (!readFrame(read, frame) { finishRequested }) break
                    pcm.write(frame.littleEndian(frame.size))
                    elapsed += FRAME_MS
                    val level = level(frame, frame.size)
                    onLevel(level)
                    val heardBefore = endOfSpeech.heardSpeech
                    val done = endOfSpeech.onFrame(level, elapsed)
                    if (endOfSpeech.heardSpeech && !heardBefore) onSpeech()
                    if (done) break
                }
            } finally {
                onLevel(0f)
            }
            val heard = endOfSpeech.heardSpeech || finishRequested && pcm.size > SAMPLE_RATE / 2
            Recording(wav(pcm.readByteArray(), SAMPLE_RATE), "audio/wav", heard)
        } ?: error("The microphone isn't available.")
    }

    override fun finish() {
        finishRequested = true
    }

    /**
     * The last [BargeIn.PRE_ROLL_MS] are always kept; once someone cuts in, the recording goes on from there until they
     * pause.
     */
    override suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        onMeter: (Float) -> Unit,
    ): Recording? {
        finishRequested = false
        return listen(MicUse.ListenOver) { read ->
            onListening()
            val frame = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ByteArray>()
            val preRollFrames = (BargeIn.PRE_ROLL_MS / FRAME_MS).toInt()
            val pcm = Buffer()
            var elapsed = 0L
            var tripped = false
            try {
                while (true) {
                    // Send now, once someone has cut in, ends it even while the microphone sends nothing.
                    if (!readFrame(read, frame) { tripped && finishRequested }) break
                    val bytes = frame.littleEndian(frame.size)
                    elapsed += FRAME_MS
                    val level = level(frame, frame.size)
                    onMeter(level)
                    if (tripped) {
                        pcm.write(bytes)
                        onLevel(level)
                    } else {
                        preRoll.addLast(bytes)
                        if (preRoll.size > preRollFrames) preRoll.removeFirst()
                    }
                    when (bargeIn.onFrame(level, elapsed, playing())) {
                        BargeIn.Step.Tripped -> {
                            tripped = true
                            // Send now counts from here: one asked before anyone cut in isn't about what they say.
                            finishRequested = false
                            preRoll.forEach(pcm::write)
                            preRoll.clear()
                            onTrip()
                        }
                        BargeIn.Step.Done -> break
                        BargeIn.Step.Waiting, BargeIn.Step.Hearing -> Unit
                    }
                    // Send now, once it has heard them.
                    if (tripped && finishRequested) break
                }
            } finally {
                onLevel(0f)
            }
            Recording(wav(pcm.readByteArray(), SAMPLE_RATE), "audio/wav", heardSpeech = bargeIn.spoke || finishRequested)
        }
    }

    /**
     * Fills [frame] with the next 20 ms, or returns false once [stop] says so. Reads don't wait, so cancelling (and
     * Finish) reach the caller even when the microphone stops sending; a whole frame each time keeps the level and
     * the speech timers measuring 20 ms steps.
     */
    private suspend fun readFrame(read: MicRead, frame: ShortArray, stop: () -> Boolean): Boolean {
        var filled = 0
        var lastAudio = TimeSource.Monotonic.markNow()
        while (filled < frame.size) {
            currentCoroutineContext().ensureActive()
            if (stop()) return false
            val count = read(frame, filled, frame.size - filled)
            when {
                // A microphone that went (the watch's, its call over) ends it with what was said.
                count == MIC_GONE -> return false
                count < 0 -> error("Couldn't read from the microphone (error $count).")
                count > 0 -> {
                    filled += count
                    lastAudio = TimeSource.Monotonic.markNow()
                }
                lastAudio.elapsedNow() >= noAudioMs.milliseconds -> error("The microphone stopped sending audio.")
                else -> delay(FRAME_MS / 2)
            }
        }
        return true
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val NO_AUDIO_MS = 5_000L

        /** What a [MicRead] returns once its microphone is gone for good: what was said so far is the recording. */
        const val MIC_GONE = Int.MIN_VALUE
        /** 20 ms, a frame as Desktop measures them. */
        const val FRAME_SAMPLES = 320
        const val FRAME_MS = 20L

        /** Desktop measures 8-bit samples (±128) against 42; 16-bit samples are 256 times larger. */
        fun level(frame: ShortArray, count: Int): Float {
            var sum = 0.0
            for (i in 0 until count) sum += frame[i].toDouble() * frame[i]
            return min(1.0, sqrt(sum / count) / 256.0 / 42.0).toFloat()
        }

        private fun ShortArray.littleEndian(count: Int): ByteArray {
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val sample = this[i].toInt()
                bytes[i * 2] = sample.toByte()
                bytes[i * 2 + 1] = (sample shr 8).toByte()
            }
            return bytes
        }
    }
}

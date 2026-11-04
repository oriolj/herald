package dev.hermeskotlin.core.voice

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The short sounds a voice chat makes so it can be followed without looking: [YourTurn] as the microphone opens (a
 * rising pair of notes), [GotIt] once what was said is taken (the same pair falling).
 */
enum class VoiceCue(internal val notes: List<Double>) {
    YourTurn(listOf(660.0, 880.0)),
    GotIt(listOf(880.0, 660.0)),
    ;

    /** [cuePcm] of it, made once. */
    val pcm: ByteArray by lazy { cuePcm(this) }
}

/** The rate [cuePcm] is made at, the voice's own. */
const val CUE_SAMPLE_RATE = 24_000

/**
 * [cue] as 16-bit mono PCM at [CUE_SAMPLE_RATE]: each note a soft sine that fades in and out, so it doesn't click,
 * well under the voice's loudness.
 */
fun cuePcm(cue: VoiceCue): ByteArray {
    val noteSamples = CUE_SAMPLE_RATE * NOTE_MS / 1000
    val fadeSamples = CUE_SAMPLE_RATE * FADE_MS / 1000
    val pcm = ByteArray(cue.notes.size * noteSamples * 2)
    var at = 0
    for (frequency in cue.notes) {
        for (i in 0 until noteSamples) {
            val fade = min(1.0, min(i, noteSamples - 1 - i).toDouble() / fadeSamples)
            val sample = (sin(2 * PI * frequency * i / CUE_SAMPLE_RATE) * fade * CUE_AMPLITUDE).toInt()
            pcm[at++] = sample.toByte()
            pcm[at++] = (sample shr 8).toByte()
        }
    }
    return pcm
}

private const val NOTE_MS = 70
private const val FADE_MS = 10
/** A quarter of full scale. */
private const val CUE_AMPLITUDE = 8_000.0

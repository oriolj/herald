package dev.hermeskotlin.core.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceCuesTest {

    private fun samples(pcm: ByteArray) = (pcm.indices step 2).map { (pcm[it].toInt() and 0xFF) or (pcm[it + 1].toInt() shl 8) }

    @Test
    fun aCueIsTwoShortNotes() {
        val pcm = cuePcm(VoiceCue.YourTurn)
        // Two notes of 70 ms, 16-bit mono.
        assertEquals(CUE_SAMPLE_RATE * 140 / 1000 * 2, pcm.size)
    }

    @Test
    fun itFadesInAndOutSoItDoesntClick() {
        val s = samples(cuePcm(VoiceCue.GotIt))
        assertTrue(abs(s.first()) < 100 && abs(s.last()) < 100)
        // And it's there in between, well under full scale.
        val loudest = s.maxOf { abs(it) }
        assertTrue(loudest in 4_000..9_000, "loudest $loudest")
    }

    @Test
    fun yourTurnAndGotItSoundDifferent() {
        assertNotEquals(cuePcm(VoiceCue.YourTurn).toList(), cuePcm(VoiceCue.GotIt).toList())
    }
}

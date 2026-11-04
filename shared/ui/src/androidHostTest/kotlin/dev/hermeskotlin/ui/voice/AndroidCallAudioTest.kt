package dev.hermeskotlin.ui.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import dev.hermeskotlin.core.voice.AndroidCallAudio
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCallAudioTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val car = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    private fun connected(vararg outputs: AudioDeviceInfo, callChannels: List<AudioDeviceInfo> = listOf(speaker)) {
        shadowOf(manager).setOutputDevices(listOf(speaker) + outputs)
        shadowOf(manager).setAvailableCommunicationDevices(callChannels)
        manager.mode = AudioManager.MODE_NORMAL
    }

    /** #369 alone leaves a car on the media route: the music goes on, and the phone's microphone hears the radio. */
    @Test
    fun aCallGoesDownTheCarsCallChannelAndComesBack() = runTest {
        connected(car, callChannels = listOf(speaker, car))
        val audio = AndroidCallAudio(context)

        val close = assertNotNull(audio.call())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(car, manager.communicationDevice)
        assertTrue(audio.on)

        close()
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        assertFalse(audio.on)
    }

    /** End, then Start right away: the old chat closes while the new one is already on the call. */
    @Test
    fun anOldChatClosingLateKeepsTheNewChatsCall() = runTest {
        connected(car, callChannels = listOf(speaker, car))
        val audio = AndroidCallAudio(context)

        val old = assertNotNull(audio.call())
        val new = assertNotNull(audio.call())
        old()
        old()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(car, manager.communicationDevice)
        assertTrue(audio.on)

        new()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    /** The car turned off mid-reply: the call moves to the phone's speaker and microphone, and still comes back at the end. */
    @Test
    fun aCarGoingAwayMovesTheCallToTheSpeaker() = runTest {
        connected(car, callChannels = listOf(speaker, car))
        val carMicrophone = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(manager).setInputDevices(listOf(carMicrophone))
        val audio = AndroidCallAudio(context)
        val close = assertNotNull(audio.call())
        assertEquals(carMicrophone, audio.microphone)

        shadowOf(manager).removeAvailableCommunicationDevice(car, false)
        shadowOf(manager).removeOutputDevice(car, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(speaker, manager.communicationDevice)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertNull(audio.microphone)
        assertTrue(audio.on)

        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        assertFalse(audio.on)
    }

    /** As a call, earbuds with no call channel would lose the sound to the phone's speaker. */
    @Test
    fun earbudsWithNoCallChannelAreLeftAlone() = runTest {
        connected(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val audio = AndroidCallAudio(context)

        assertNull(audio.call())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        assertFalse(audio.on)

        val close = audio.chat()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertFalse(audio.on)
        close()
    }

    /** Nothing connected: a call is on the phone's speaker, not its earpiece. */
    @Test
    fun aCallWithNothingConnectedIsOnTheSpeaker() = runTest {
        connected(callChannels = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), speaker))
        val audio = AndroidCallAudio(context)

        val close = assertNotNull(audio.call())
        assertEquals(speaker, manager.communicationDevice)
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    /** A chat on the speaker's call routing, then a call from another screen: either closing first, the mode comes back. */
    @Test
    fun aCallTakesOverFromAChatOnTheSpeakerAndGivesItBack() = runTest {
        connected()
        val audio = AndroidCallAudio(context)

        val chat = audio.chat()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        val call = assertNotNull(audio.call())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertTrue(audio.on)

        // The chat still has its route once the call has ended.
        call()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)
        assertTrue(audio.on)

        chat()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        assertFalse(audio.on)
    }

    @Test
    fun aChatClosingUnderACallLeavesItAlone() = runTest {
        connected()
        val audio = AndroidCallAudio(context)

        val chat = audio.chat()
        val call = assertNotNull(audio.call())
        chat()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertTrue(audio.on)

        call()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertFalse(audio.on)
    }
}

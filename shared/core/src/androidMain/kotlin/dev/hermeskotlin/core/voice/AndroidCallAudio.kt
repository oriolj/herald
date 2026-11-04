package dev.hermeskotlin.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A call's audio routing: communication mode, to the headset (or the car's hands-free) when one is connected,
 * else the speaker, with the phone's call processing. The GPT-Live call and the chained voice chat route the same way.
 */
internal class CallAudioRoute(private val audioManager: AudioManager) {
    private var previousMode = AudioManager.MODE_NORMAL
    private var routed = false

    /** Android wouldn't send the call's sound to the device [route] picked. */
    var refused = false
        private set

    /**
     * Whether what's connected can carry a call. Earbuds or a speaker with no call channel can't: routed as a call,
     * the sound would leave them for the phone's speaker. Nothing connected, the phone's speaker can.
     */
    @Suppress("DEPRECATION")
    fun takesCalls(): Boolean {
        if (audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in OWN_OUTPUTS }) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.availableCommunicationDevices.any { it.type in HEADSETS }
        else audioManager.isWiredHeadsetOn || audioManager.isBluetoothScoOn
    }

    /** Routes the sound as a call; the device it goes to, where the platform says (Android 12 and later). */
    @Suppress("DEPRECATION")
    fun route(): AudioDeviceInfo? {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routed = true
        refused = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            return (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
                ?.also { refused = !audioManager.setCommunicationDevice(it) }
        }
        audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
        return null
    }

    /** The sound goes to [device] now: Bluetooth takes a moment to open its call channel. */
    fun reached(device: AudioDeviceInfo): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audioManager.communicationDevice?.id == device.id

    /** The microphone that comes with [output] (the headset's, the car's), or null for the phone's own. */
    fun microphoneFor(output: AudioDeviceInfo): AudioDeviceInfo? {
        if (output.type !in HEADSETS) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == output.type && (output.address.isEmpty() || it.address == output.address) }
    }

    @Suppress("DEPRECATION")
    fun unroute() {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        else audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}

/** Outputs that aren't the phone itself: with one in, a chained chat leaves the speaker's route alone. */
@SuppressLint("InlinedApi") // A device only reports the types its Android knows.
private val OWN_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
)

/**
 * The one owner of the chained voice chat's route, for the whole app: the chat, a phone call and the assistant panel
 * share it, so a chat winding down can't take the route from a newer one. A chat plays as a call ([call]), or else
 * has the usual route, with the phone's echo cancelling on its own speaker ([chat]). A call comes first, and the route
 * goes back once the last chat has closed. The recorder listens with the call's [microphone] and the player plays as a
 * call while [on].
 */
class AndroidCallAudio(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val route = CallAudioRoute(audioManager)
    private val lock = Any()

    /** Each chat playing as a call, or waiting for the device to take it. */
    private val callers = mutableSetOf<Any>()
    private var callDevice: AudioDeviceInfo? = null

    /** Each chat on the usual route. */
    private val chats = mutableSetOf<Any>()

    /** Puts the usual route back; set while a chat has call routing on the phone's own speaker. */
    private var restoreSpeaker: (() -> Unit)? = null

    /** Headphones or earbuds that come or go during a chat change its route, and a call moves off a device that's gone. */
    private val outputsChanged = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            moveCall(removedDevices)
            reroute()
        }
    }

    /** Routed as a call, either way: the voice plays as one. */
    @Volatile var on = false
        private set

    /** The call's microphone, when it isn't the phone's own (the car's, a headset's). */
    @Volatile var microphone: AudioDeviceInfo? = null
        private set

    /**
     * Routes the chat as a call, through the car's or the headset's call channel, else the phone's speaker; the action
     * that closes it, safe to call again. Null, with the route as it was, when what's connected has no call channel,
     * Android refuses the device, or it doesn't take the sound in time (a car's call channel that won't open): the chat
     * then has the usual route ([chat]), which still reaches it.
     */
    suspend fun call(): (() -> Unit)? {
        val caller = Any()
        val device = synchronized(lock) {
            if (callers.isEmpty()) {
                try {
                    if (!route.takesCalls()) return null
                    closeSpeaker()
                    callDevice = route.route()
                } catch (_: Exception) {
                    route.unroute()
                    reroute()
                    return null
                }
            }
            if (callers.isEmpty() && chats.isEmpty()) listenForOutputs()
            callers += caller
            on = true
            callDevice
        }
        val close = { closeCall(caller) }
        val reached = try {
            // What's played or said before the car's call channel opens is lost. A chat joining one still opening waits too.
            device == null || !route.refused && withTimeoutOrNull(ROUTE_WAIT_MS) {
                while (!route.reached(device)) delay(ROUTE_POLL_MS)
            } != null
        } catch (e: CancellationException) {
            close()
            throw e
        }
        if (!reached) {
            close()
            return null
        }
        microphone = device?.let(route::microphoneFor)
        return close
    }

    private fun closeCall(caller: Any): Unit = synchronized(lock) {
        // An old chat's close must not unroute a newer one.
        if (!callers.remove(caller) || callers.isNotEmpty()) return
        callDevice = null
        microphone = null
        // A chat's end must never throw.
        runCatching { route.unroute() }
        on = false
        if (chats.isEmpty()) stopListeningForOutputs()
        reroute()
    }

    /**
     * The car or headset a call went to is gone (the car turned off): the call moves to what's left, another headset
     * or the phone's speaker, and listens with that one's microphone.
     */
    private fun moveCall(removed: Array<out AudioDeviceInfo>): Unit = synchronized(lock) {
        val gone = callDevice ?: return
        if (removed.none { it.id == gone.id }) return
        microphone = null
        callDevice = runCatching {
            route.unroute()
            route.route()
        }.getOrNull()
        microphone = callDevice?.let(route::microphoneFor)
    }

    private fun listenForOutputs() {
        runCatching { audioManager.registerAudioDeviceCallback(outputsChanged, null) }
    }

    private fun stopListeningForOutputs() {
        runCatching { audioManager.unregisterAudioDeviceCallback(outputsChanged) }
    }

    /**
     * A chat on the usual route. On the phone's own speaker it uses call routing, so the phone's echo cancelling keeps
     * the voice out of the microphone. Headphones, earbuds or another speaker keep their usual route: call routing
     * would move earbuds without a call profile to the phone's speaker and drop the others to call quality. A route
     * that won't take leaves the chat on its usual one, never on the earpiece. Headphones that come or go mid-chat
     * change the route. The returned close action is safe to call again.
     */
    fun chat(): () -> Unit = synchronized(lock) {
        val owner = Any()
        if (chats.isEmpty() && callers.isEmpty()) listenForOutputs()
        chats += owner
        reroute()
        return@synchronized {
            synchronized(lock) {
                // An old chat's finally must not restore under a newer one, here or in another controller.
                if (chats.remove(owner) && chats.isEmpty()) {
                    if (callers.isEmpty()) stopListeningForOutputs()
                    reroute()
                }
            }
        }
    }

    /** Call routing while a chat on the usual route is open on the phone's own speaker, unless a call has the route. */
    @Suppress("DEPRECATION")
    private fun reroute(): Unit = synchronized(lock) {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
        val wanted = callers.isEmpty() && chats.isNotEmpty() && outputs != null && outputs.none { it.type in OWN_OUTPUTS }
        if (wanted == (restoreSpeaker != null)) return
        if (!wanted) {
            closeSpeaker()
            return
        }
        val mode = audioManager.mode
        val speaker = audioManager.isSpeakerphoneOn
        val restore = {
            // Each step on its own: a chat's end must never throw.
            runCatching { if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice() else audioManager.isSpeakerphoneOn = speaker }
            runCatching { audioManager.mode = mode }
            Unit
        }
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val builtIn = audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                check(builtIn != null && audioManager.setCommunicationDevice(builtIn))
            } else audioManager.isSpeakerphoneOn = true
            restoreSpeaker = restore
            on = true
        } catch (_: Exception) {
            restore()
        }
    }

    /** Lets go of the speaker's call routing, putting back what it found: before a call routes, so that's what it finds. */
    private fun closeSpeaker() {
        restoreSpeaker?.invoke()
        restoreSpeaker = null
        on = callers.isNotEmpty()
    }

    private companion object {
        const val ROUTE_WAIT_MS = 2_000L
        const val ROUTE_POLL_MS = 50L
    }
}

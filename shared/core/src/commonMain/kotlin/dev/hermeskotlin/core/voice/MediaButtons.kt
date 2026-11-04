package dev.hermeskotlin.core.voice

/** A press of the car's, the headset's or the watch's media buttons. */
enum class MediaButton { PlayPause, Next }

/**
 * The media buttons for a voice chat that plays like media (a car's steering wheel, a headset): while it's held,
 * presses come to it instead of the music app that last played.
 */
interface MediaButtons {
    /**
     * Takes the media buttons; [onPress] hears each press. The action that lets them go, safe to call again, which does
     * nothing once a newer chat has taken them. Null when it can't.
     */
    fun hold(onPress: (MediaButton) -> Unit): (() -> Unit)?

    /** No media buttons on this platform. */
    object None : MediaButtons {
        override fun hold(onPress: (MediaButton) -> Unit) = null
    }
}

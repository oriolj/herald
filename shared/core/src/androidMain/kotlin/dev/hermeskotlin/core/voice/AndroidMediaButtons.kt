package dev.hermeskotlin.core.voice

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * [MediaButtons] on Android: a media session, playing while it's held. Android sends the media buttons to the app
 * that last played media, so the voice chat plays its voice as media meanwhile ([on]), as the player does.
 */
class AndroidMediaButtons(private val context: Context) : MediaButtons {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var session: MediaSession? = null

    /** Held: the voice chat's voice plays as media, so the buttons come here. */
    val on get() = session != null

    override fun hold(onPress: (MediaButton) -> Unit): (() -> Unit)? {
        synchronized(lock) { closeSession() }
        val session = try {
            MediaSession(context, "Herald voice chat")
        } catch (_: Exception) {
            return null
        }
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = onPress(MediaButton.PlayPause)

                override fun onPause() = onPress(MediaButton.PlayPause)

                override fun onSkipToNext() = onPress(MediaButton.Next)
            },
            main,
        )
        // What a car's screen shows for it.
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Hermes voice chat").build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.isActive = true
        synchronized(lock) {
            closeSession()
            this.session = session
        }
        return {
            // An old chat winding down must not take them from a newer one.
            synchronized(lock) { if (this.session === session) closeSession() }
        }
    }

    private fun closeSession() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
    }
}

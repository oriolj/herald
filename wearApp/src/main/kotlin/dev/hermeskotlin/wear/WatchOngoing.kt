package dev.hermeskotlin.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.hermeskotlin.wearlink.ReplyPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the watch app in front while Hermes works on what was asked, and while the answer is read aloud: Wear OS
 * goes back to the watch face after a while with nothing touched, which would leave the reply behind it. It's an
 * Ongoing Activity ([ongoingWork]) with no service behind it, as nothing listens meanwhile; a call is one of its own
 * ([CallService]).
 */
class WatchOngoing(
    private val context: Context,
    private val link: WatchLink,
    private val speech: SpeechOut,
    private val rings: WatchRings,
    private val scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(NotificationChannelCompat.Builder(WORK_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.getString(R.string.wear_channel_replies_name)).build())
    }

    // canRing checks the notification permission.
    @SuppressLint("MissingPermission")
    fun start() {
        scope.launch {
            // Only whether a call is on: its captions and levels change many times a second.
            val inCall = link.call.map { it?.active == true }.distinctUntilChanged()
            // From the first value: one left behind by an app that was ended mid-turn is taken down too.
            combine(link.turn, inCall, speech.speaking, speech.coming, ::ongoingWork).distinctUntilChanged().collect { work ->
                if (work != null && rings.canRing) {
                    val line = context.getString(work)
                    manager.notify(WORK_ID, ongoingNotification(context, WORK_CHANNEL, WORK_ID, R.drawable.ic_mic, line, NotificationCompat.CATEGORY_PROGRESS, Status.Builder().addTemplate(line).build()))
                } else {
                    manager.cancel(WORK_ID)
                }
            }
        }
    }

    private companion object {
        const val WORK_CHANNEL = "reply_ongoing"
        const val WORK_ID = 7_002
    }
}

/**
 * What the watch says it's doing while it waits on Hermes outside a call: at work on what was asked (the answer's
 * voice still [voiceComing] included), or reading the answer aloud. Null otherwise, and while Hermes waits on an
 * answer given on the phone, which is where the user is. A line of its own, not the talk screen's activity, so the
 * notification isn't posted again with each tool Hermes runs.
 */
@StringRes
internal fun ongoingWork(turn: WatchTurn?, inCall: Boolean, speaking: Boolean, voiceComing: Boolean): Int? = when {
    inCall -> null
    speaking -> R.string.wear_ongoing_speaking
    voiceComing -> R.string.wear_ongoing_working
    turn?.running == true && turn.update.phase != ReplyPhase.WaitingOnPhone -> R.string.wear_ongoing_working
    else -> null
}

/** An ongoing notification that's an Ongoing Activity: [status] on the watch face, and a tap back to the talk screen. */
internal fun ongoingNotification(
    context: Context,
    channel: String,
    id: Int,
    @DrawableRes icon: Int,
    title: String,
    category: String,
    status: Status,
): Notification {
    val open = PendingIntent.getActivity(context, MainActivity.OPEN_REQUEST, MainActivity.openIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = NotificationCompat.Builder(context, channel)
        .setSmallIcon(icon)
        .setContentTitle(title)
        .setCategory(category)
        .setContentIntent(open)
        .setOngoing(true)
        .setSilent(true)
    OngoingActivity.Builder(context.applicationContext, id, builder)
        .setStaticIcon(icon)
        .setTouchIntent(open)
        .setStatus(status)
        .build()
        .apply(context.applicationContext)
    return builder.build()
}

/**
 * A call as its Ongoing Activity says it: [title] the chat called, live since [liveSince]
 * ([SystemClock.elapsedRealtime]), else getting there ([caption]).
 */
data class OngoingCall(val title: WatchLine, val liveSince: Long?, val caption: WatchLine)

/** What [call] keeps going: nothing once it's over, or before there is one. */
internal fun ongoingCall(call: WatchCallState?): OngoingCall? = call?.takeIf { it.active }?.let {
    OngoingCall(
        it.title?.takeIf(String::isNotBlank)?.let { title -> WatchLine.Res(R.string.wear_call_target, listOf(title)) } ?: WatchLine.Res(R.string.wear_call_hermes),
        it.liveSince,
        if (it.liveSince == null) callCaption(it) else WatchLine.Res(R.string.wear_call_live),
    )
}

/**
 * A call, held in front of everything else on the watch, as Maps holds its navigation: its notification is an
 * Ongoing Activity ("Call · 2:31", a tap back to the call), and it keeps the microphone the call's while the screen
 * is off or the watch face is up, where Android would stop it otherwise. Started with the call ([WatchLink.startCall],
 * from a tap, with the app in front, as a microphone service must be) and stopped as it ends; meanwhile it follows the
 * call itself.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = HeraldWatch.of(this)
        val call = ongoingCall(app.link.call.value)
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(getString(R.string.wear_channel_call_name)).build(),
        )
        // In front first, even for a call already over: Android ends the app when a service started this way stops
        // before it's in front.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(call ?: OngoingCall(WatchLine.Res(R.string.wear_call_hermes), null, WatchLine.Res(R.string.wear_call_ended))), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            // Refused (out of sight already, or the microphone no longer allowed): the call goes on as it would without.
            stopSelf()
            return START_NOT_STICKY
        }
        // Over already, or its notification wouldn't show: a call held unseen would go on hearing the room.
        if (call == null || !shown(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        _holding.value = true
        if (!following) {
            following = true
            scope.launch {
                // From its first change: the call as it is now was just posted.
                app.link.call.map(::ongoingCall).distinctUntilChanged().drop(1).collect { now ->
                    if (now == null) stopSelf() else NotificationManagerCompat.from(this@CallService).notifyCall(notification(now))
                }
            }
            scope.launch {
                // Its notification turned off meanwhile, which is done out of sight: the call ends, as it would
                // have gone out of sight without the service.
                while (shown(this@CallService)) delay(SHOWN_CHECK_MS)
                app.link.endCall()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _holding.value = false
        scope.cancel()
        super.onDestroy()
    }

    // The service's own notification, which posting needs no permission for.
    @SuppressLint("MissingPermission")
    private fun NotificationManagerCompat.notifyCall(notification: Notification) = notify(NOTIFICATION_ID, notification)

    private fun notification(call: OngoingCall): Notification {
        val since = call.liveSince
        val status = if (since != null) {
            Status.Builder().addTemplate("#type# · #time#").addPart("type", Status.TextPart(getString(R.string.wear_call_short))).addPart("time", Status.StopwatchPart(since)).build()
        } else {
            Status.Builder().addTemplate(call.caption.text(this)).build()
        }
        return ongoingNotification(this, CHANNEL, NOTIFICATION_ID, R.drawable.ic_call, call.title.text(this), NotificationCompat.CATEGORY_CALL, status)
    }

    companion object {
        private val _holding = MutableStateFlow(false)

        /** A call is held in front now, so it goes on out of sight ([MainActivity.onStop]). */
        val holding: StateFlow<Boolean> = _holding.asStateFlow()

        private const val CHANNEL = "call_ongoing"
        private const val NOTIFICATION_ID = 7_001
        private const val SHOWN_CHECK_MS = 5_000L

        /**
         * Whether the call's notification shows, and with it the call on the watch face. Android starts the service
         * without it (notifications denied, or the Calls channel turned off), so the call isn't held then.
         */
        private fun shown(context: Context): Boolean =
            HeraldWatch.of(context).rings.canRing &&
                NotificationManagerCompat.from(context).getNotificationChannelCompat(CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

        /**
         * Holds the call on now in front: from the tap that starts it, while the app is in front. Never stopped from
         * outside, which could come before it's in front: it stops itself once the call is over.
         */
        fun start(context: Context) {
            if (!shown(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            } catch (_: RuntimeException) {
                // Not allowed from here: the call goes on as it would without.
            }
        }
    }
}

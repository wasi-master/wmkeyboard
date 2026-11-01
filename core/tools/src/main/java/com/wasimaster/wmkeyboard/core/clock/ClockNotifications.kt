package com.wasimaster.wmkeyboard.core.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wasimaster.wmkeyboard.app.MainActivityContract
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.tools.R
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The clock's notifications: one per running timer, one for the stopwatch,
 * and the ring itself.
 *
 * Timers and the stopwatch are an ongoing [NotificationKind.CLOCK] entry each,
 * drawn by the system's own chronometer so nothing has to stay awake to tick
 * them. Their buttons go to [ClockReceiver]. Running ones need no service: the
 * time is in the data, and the wake-up for the end is registered with the
 * alarm manager.
 *
 * Ringing has two channels of its own, made here rather than as a
 * [NotificationKind] because they cannot be switched off from this app: an
 * alarm the user set rings. The quiet one carries the ring service's
 * notification while the service plays the sound; the loud one is the
 * fallback when Android would not let the service start, and plays the
 * system's alarm sound itself, over and over, until it is answered.
 */
object ClockNotifications {

    const val CHANNEL_RING = "clock_ring"
    const val CHANNEL_RING_SOUND = "clock_ring_sound"

    /** The ring screen, in `:app`, named as a string like the settings activity. */
    const val RING_ACTIVITY = "com.wasimaster.wmkeyboard.app.clock.ClockRingActivity"

    /** Brings the shade in line with [data]: posts what runs, takes down what stopped. */
    fun refresh(context: Context, data: ClockData) {
        val wanted = mutableSetOf<Int>()
        data.timers.forEach { t ->
            val id = NotificationIds.forTimer(t.id)
            wanted += id
            postTimer(context, t, id)
        }
        if (!data.stopwatch.isReset) {
            wanted += NotificationIds.CLOCK_STOPWATCH
            postStopwatch(context, data.stopwatch)
        }
        postAlarmNotices(context, data, wanted)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { manager.activeNotifications.map { it.id } }.getOrDefault(emptyList())
        active.filter {
            (NotificationIds.isTimer(it) || NotificationIds.isAlarmNotice(it) || it == NotificationIds.CLOCK_STOPWATCH) &&
                it !in wanted
        }
            .forEach { WmNotifications.cancel(context, it) }
    }

    /**
     * An alarm's notice (#970): while it is snoozed, the time it rings again
     * and a button that ends the snooze; in the minutes before it rings, when
     * [ClockConfig.upcomingNoticeMinutes] asks, the time and a button that
     * skips it. Not while it rings: the ring has a notification of its own.
     */
    private fun postAlarmNotices(context: Context, data: ClockData, wanted: MutableSet<Int>) {
        val config = ClockPrefs.get(context)
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val hour24 = android.text.format.DateFormat.is24HourFormat(context)
        val window = config.upcomingNoticeMinutes * 60_000L
        for (alarm in data.alarms) {
            if (data.ringing.any { it.kind == RingKind.ALARM && it.ref == alarm.id }) continue
            val snoozed = alarm.enabled && alarm.snoozedUntil > now
            val next = if (snoozed || window <= 0) null else AlarmMath.nextFire(alarm, now, zone)
            val coming = next != null && next - now <= window
            if (!(snoozed && config.snoozedNotice) && !coming) continue
            val id = NotificationIds.forAlarmNotice(alarm.id)
            wanted += id
            val at = if (snoozed) alarm.snoozedUntil else next!!
            val title = alarm.label.ifEmpty { context.getString(R.string.core_tools_clock_alarm) }
            val time = ClockFormats.time(at, zone, hour24)
            WmNotifications.post(context, NotificationKind.CLOCK, id) {
                setSmallIcon(R.drawable.core_tools_ic_alarm)
                setContentTitle(title)
                setContentText(
                    context.getString(
                        if (snoozed) R.string.core_tools_clock_snoozed_until else R.string.core_tools_clock_coming_at,
                        time,
                    ),
                )
                setOnlyAlertOnce(true)
                setSilent(true)
                setOngoing(snoozed)
                setContentIntent(openClock(context, "alarms"))
                setTimeoutAfter((at - now).coerceAtLeast(0) + TIMEOUT_GRACE_MS)
                if (snoozed) {
                    addAction(0, context.getString(R.string.core_tools_clock_dismiss), action(context, ClockReceiver.ACTION_END_SNOOZE, alarm.id))
                } else {
                    addAction(0, context.getString(R.string.core_tools_clock_skip), action(context, ClockReceiver.ACTION_SKIP_COMING, alarm.id))
                }
            }
        }
    }

    /** An alarm that rang with nobody there (#970): it stays in the shade until it is swiped away. */
    fun postMissed(context: Context, ring: Ringing) {
        val title = ring.label.ifEmpty { context.getString(R.string.core_tools_clock_alarm) }
        val time = ClockFormats.time(ring.since, java.util.TimeZone.getDefault(), android.text.format.DateFormat.is24HourFormat(context))
        WmNotifications.post(context, NotificationKind.CLOCK, NotificationIds.forMissedAlarm(ring.ref)) {
            setSmallIcon(R.drawable.core_tools_ic_alarm)
            setContentTitle(context.getString(R.string.core_tools_clock_missed_title, title))
            setContentText(context.getString(R.string.core_tools_clock_missed_at, time))
            setAutoCancel(true)
            setContentIntent(openClock(context, "history"))
        }
    }

    private fun postTimer(context: Context, t: RunningTimer, id: Int) {
        val now = System.currentTimeMillis()
        val title = t.label.ifEmpty {
            t.pomodoro?.let { context.getString(phaseRes(it.phase)) } ?: context.getString(R.string.core_tools_clock_timer)
        }
        WmNotifications.post(context, NotificationKind.CLOCK, id) {
            setSmallIcon(R.drawable.core_tools_ic_timer)
            setContentTitle(title)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setContentIntent(openClock(context, "timers"))
            if (t.paused) {
                setContentText(context.getString(R.string.core_tools_clock_paused_left, ClockFormats.countdown(t.pausedRemainingMs)))
                setProgress(PROGRESS_MAX, (t.progressAt(now) * PROGRESS_MAX).toInt(), false)
                addAction(0, context.getString(R.string.core_tools_clock_resume), action(context, ClockReceiver.ACTION_TIMER_RESUME, t.id))
            } else {
                setShowWhen(true)
                setWhen(t.endsAt)
                setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setChronometerCountDown(true)
                setTimeoutAfter((t.endsAt - now).coerceAtLeast(0) + TIMEOUT_GRACE_MS)
                addAction(0, context.getString(R.string.core_tools_clock_pause), action(context, ClockReceiver.ACTION_TIMER_PAUSE, t.id))
                addAction(0, context.getString(R.string.core_tools_clock_add_minute), action(context, ClockReceiver.ACTION_TIMER_ADD, t.id))
            }
            addAction(0, context.getString(R.string.core_tools_clock_cancel), action(context, ClockReceiver.ACTION_TIMER_CANCEL, t.id))
        }
    }

    private fun postStopwatch(context: Context, s: Stopwatch) {
        val now = System.currentTimeMillis()
        WmNotifications.post(context, NotificationKind.CLOCK, NotificationIds.CLOCK_STOPWATCH) {
            setSmallIcon(R.drawable.core_tools_ic_stopwatch)
            setContentTitle(context.getString(R.string.core_tools_clock_stopwatch))
            setOngoing(s.running)
            setOnlyAlertOnce(true)
            setSilent(true)
            setContentIntent(openClock(context, "stopwatch"))
            if (s.laps.isNotEmpty()) {
                setSubText(context.resources.getQuantityString(R.plurals.core_tools_clock_laps, s.laps.size, s.laps.size))
            }
            if (s.running) {
                setShowWhen(true)
                setWhen(now - s.elapsedAt(now))
                setUsesChronometer(true)
                addAction(0, context.getString(R.string.core_tools_clock_lap), action(context, ClockReceiver.ACTION_STOPWATCH_LAP))
                addAction(0, context.getString(R.string.core_tools_clock_pause), action(context, ClockReceiver.ACTION_STOPWATCH_PAUSE))
            } else {
                setContentText(ClockFormats.stopwatch(s.accumulatedMs))
                addAction(0, context.getString(R.string.core_tools_clock_resume), action(context, ClockReceiver.ACTION_STOPWATCH_RESUME))
                addAction(0, context.getString(R.string.core_tools_clock_reset), action(context, ClockReceiver.ACTION_STOPWATCH_RESET))
            }
        }
    }

    /**
     * The ring's notification: what rings, Snooze and Dismiss, and the ring
     * screen as its full-screen intent so a locked phone lights up with it.
     */
    fun ringNotification(context: Context, ringing: List<Ringing>, loud: Boolean, timeoutMs: Long? = null): Notification {
        ensureRingChannels(context)
        val first = ringing.firstOrNull()
        val title = when {
            first == null -> context.getString(R.string.core_tools_clock_alarm)
            first.label.isNotEmpty() -> first.label
            first.kind == RingKind.TIMER -> context.getString(R.string.core_tools_clock_timer_done)
            else -> context.getString(R.string.core_tools_clock_alarm)
        }
        val text = ClockFormats.time(
            first?.since ?: System.currentTimeMillis(),
            java.util.TimeZone.getDefault(),
            android.text.format.DateFormat.is24HourFormat(context),
        )
        val more = ringing.size - 1
        val ring = ringScreen(context)
        return NotificationCompat.Builder(context, if (loud) CHANNEL_RING_SOUND else CHANNEL_RING)
            .setSmallIcon(if (first?.kind == RingKind.TIMER) R.drawable.core_tools_ic_timer else R.drawable.core_tools_ic_alarm)
            .setContentTitle(title)
            .setContentText(
                if (more > 0) context.resources.getQuantityString(R.plurals.core_tools_clock_ring_more, more, text, more) else text,
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(ring)
            .setFullScreenIntent(ring, true)
            .addAction(0, context.getString(R.string.core_tools_clock_snooze), action(context, ClockReceiver.ACTION_SNOOZE_ALL))
            .addAction(0, context.getString(R.string.core_tools_clock_dismiss), action(context, ClockReceiver.ACTION_DISMISS_ALL))
            .apply { if (timeoutMs != null) setTimeoutAfter(timeoutMs) }
            .build()
            .apply { if (loud) flags = flags or Notification.FLAG_INSISTENT }
    }

    /** The ring without a service: the loud channel plays the sound itself. */
    fun postFallbackRing(context: Context, ringing: List<Ringing>) {
        if (!WmNotifications.canPost(context) || ringing.isEmpty()) return
        // No service is there to stop the sound, so the shade does: the ring
        // stops by itself after the auto-silence time, and the next wake-up
        // logs it as missed (ClockEngine.expireStale).
        val silenceMs = ClockPrefs.get(context).autoSilenceMinutes.coerceAtLeast(1) * 60_000L
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NotificationIds.CLOCK_RING, ringNotification(context, ringing, loud = true, timeoutMs = silenceMs))
        }
    }

    fun cancelRing(context: Context) = WmNotifications.cancel(context, NotificationIds.CLOCK_RING)

    /** Whether Android will let the ring screen take over a locked phone (asked from Android 14). */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)
    }

    fun ringScreen(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, REQUEST_RING_SCREEN,
            Intent().setClassName(context, RING_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openClock(context: Context, tab: String): PendingIntent =
        PendingIntent.getActivity(
            context, REQUEST_OPEN + tab.hashCode() % 64,
            MainActivityContract.intent(context, ClockEngine.ROUTE).putExtra(EXTRA_TAB, tab),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Which tab the Clock screen opens on, from a notification. */
    const val EXTRA_TAB = "clock_tab"

    private fun action(context: Context, action: String, id: Long = -1): PendingIntent =
        ClockEngine.broadcast(context, action, REQUEST_ACTION_BASE + (action.hashCode() and 0xFFF) + (id and 0xFFFF).toInt() * 4096) {
            putExtra(ClockReceiver.EXTRA_ID, id)
        }

    /** Makes the two ring channels, so their system settings page exists to open. */
    fun ensureRingChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_RING) == null) {
            val quiet = NotificationChannel(
                CHANNEL_RING,
                context.getString(R.string.core_tools_clock_channel_ring_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.core_tools_clock_channel_ring_body)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            runCatching { manager.createNotificationChannel(quiet) }
        }
        if (manager.getNotificationChannel(CHANNEL_RING_SOUND) == null) {
            val loud = NotificationChannel(
                CHANNEL_RING_SOUND,
                context.getString(R.string.core_tools_clock_channel_sound_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.core_tools_clock_channel_sound_body)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = ClockRingService.VIBRATION
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            runCatching { manager.createNotificationChannel(loud) }
        }
    }

    fun phaseRes(phase: PomodoroPhase): Int = when (phase) {
        PomodoroPhase.FOCUS -> R.string.core_tools_clock_focus
        PomodoroPhase.SHORT_BREAK -> R.string.core_tools_clock_short_break
        PomodoroPhase.LONG_BREAK -> R.string.core_tools_clock_long_break
    }

    private const val PROGRESS_MAX = 1000
    private const val TIMEOUT_GRACE_MS = 5 * 60_000L
    private const val REQUEST_RING_SCREEN = 10
    private const val REQUEST_OPEN = 20
    private const val REQUEST_ACTION_BASE = 100
}

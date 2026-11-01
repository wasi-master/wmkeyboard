package com.wasimaster.wmkeyboard.core.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wasimaster.wmkeyboard.app.MainActivityContract
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * The one door every change to the clock goes through.
 *
 * [update] applies a change to [ClockStore], and then does the two things a
 * change can make stale: the wake-ups registered with [AlarmManager] and the
 * notifications in the shade. A screen that changed the data and forgot either
 * would leave an alarm that rings at its old time, or a timer in the shade that
 * was cancelled a minute ago.
 */
object ClockEngine {

    /** Applies [change] and brings the system's view of the clock up to date. */
    fun update(context: Context, change: (ClockData) -> ClockData): ClockData {
        val app = context.applicationContext
        val before = ClockStore.get(app).data
        val data = ClockStore.get(app).update(change)
        rearm(app, data)
        ClockNotifications.refresh(app, data)
        // Answered: the ring's notification goes. The ring service does this
        // itself, but the notification-only ring (the Play build, or a phone
        // that refused the service) has nobody else to take it down.
        if (before.ringing.isNotEmpty() && data.ringing.isEmpty()) ClockNotifications.cancelRing(app)
        return data
    }

    /**
     * Rings that were never answered and are long past their auto-silence:
     * logged as missed. The ring service does this on a timer, but a ring with
     * no service (or a service the process lost) would otherwise sit in the
     * data for ever, and keep its alarm from ringing the next day.
     */
    private fun expireStale(context: Context, now: Long) {
        val limit = silenceMs(context) + STALE_GRACE_MS
        val stale = ClockStore.get(context).data.ringing.filter { now - it.since > limit }
        if (stale.isEmpty()) return
        settleUnanswered(context, stale, now)
    }

    /** How long a ring goes on before nobody is counted as there to answer it. */
    private fun silenceMs(context: Context): Long =
        ClockPrefs.get(context).autoSilenceMinutes.coerceAtLeast(1) * 60_000L

    /**
     * [rings] ran their time with nobody answering: each snoozes itself, while
     * its alarm has [ClockConfig.autoSnoozes] left, or counts as missed, and a
     * missed alarm gets its notice in the shade (#970).
     */
    private fun settleUnanswered(context: Context, rings: List<Ringing>, now: Long) {
        val config = ClockPrefs.get(context)
        val missed = mutableListOf<Ringing>()
        update(context) { d ->
            rings.fold(d) { acc, r ->
                // The same test [ClockActions.unanswered] makes, to know which went missed.
                val limit = acc.alarm(r.ref)?.autoSnoozes?.takeIf { it >= 0 } ?: config.autoSnoozes
                val snoozes = r.kind == RingKind.ALARM &&
                    (acc.alarm(r.ref)?.snoozeCount ?: Int.MAX_VALUE) < limit
                if (r.kind == RingKind.ALARM && !snoozes) missed += r
                ClockActions.unanswered(acc, r, now, limit, snoozeMinutesFor(context, r, acc))
            }
        }
        if (config.missedNotice) missed.forEach { ClockNotifications.postMissed(context, it) }
    }

    private const val STALE_GRACE_MS = 60_000L

    // ---- Wake-ups ------------------------------------------------------

    /**
     * Registers the next alarm and the next timer end with [AlarmManager],
     * replacing whatever was registered before.
     *
     * Two wake-ups at most, whatever the number of alarms and timers: the
     * receiver works out what is due when it fires, and re-arms for the next.
     * That keeps the registration a pure function of the data, which is what
     * makes re-arming after a reboot or a zone change safe to do at any time.
     */
    fun rearm(context: Context, data: ClockData = ClockStore.get(context).data) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val nextAlarm = AlarmMath.nextOfAll(data.alarms, now, TimeZone.getDefault())
        val alarmPi = broadcast(context, ClockReceiver.ACTION_ALARM, REQUEST_ALARM)
        if (nextAlarm == null) {
            am.cancel(alarmPi)
        } else {
            setAlarmClock(context, am, nextAlarm.second, alarmPi)
        }
        val nextTimer = data.timers.filterNot { it.paused }.minOfOrNull { it.endsAt }
        val timerPi = broadcast(context, ClockReceiver.ACTION_TIMER, REQUEST_TIMER)
        if (nextTimer == null) {
            am.cancel(timerPi)
        } else {
            setExact(am, nextTimer, timerPi)
        }
        // The heads-up before the next alarm (#970). Inexact: a notice a minute
        // late is still a notice, and it is not worth an exact-alarm slot.
        val upcoming = ClockPrefs.get(context).upcomingNoticeMinutes
        val noticeAt = nextAlarm?.second?.minus(upcoming * 60_000L)?.takeIf { upcoming > 0 && it > now }
        val noticePi = broadcast(context, ClockReceiver.ACTION_UPCOMING, REQUEST_UPCOMING)
        if (noticeAt == null) {
            am.cancel(noticePi)
        } else {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, noticeAt, noticePi) }
        }
        // The end of a ring nobody answers, so it snoozes or counts as missed
        // on time even with no ring service to time it (the Play build, or a
        // service Android refused).
        val silenceAt = data.ringing.minOfOrNull { it.since }?.plus(silenceMs(context) + STALE_GRACE_MS + 1)
        val silencePi = broadcast(context, ClockReceiver.ACTION_SILENCE, REQUEST_SILENCE)
        if (silenceAt == null) {
            am.cancel(silencePi)
        } else {
            setExact(am, silenceAt, silencePi)
        }
    }

    /**
     * Re-arms from a cold process start. The tz database and the phone's
     * clock may have moved while the process was dead (after a reboot, the
     * registrations are gone outright), and this app deliberately has no boot
     * receiver: the keyboard starts at boot anyway, and this runs then.
     *
     * Off the main thread, and free when the user never used the clock.
     */
    fun rearmOnStart(context: Context) {
        val app = context.applicationContext
        if (!ClockStore.exists(app)) return
        thread(name = "clock-rearm", isDaemon = true) {
            runCatching {
                val store = ClockStore.get(app)
                // Timers that ran out while nothing was alive ring now rather
                // than never: late is better than silent.
                onTimerWake(app, System.currentTimeMillis(), ringIfDue = true)
                rearm(app, store.data)
                ClockNotifications.refresh(app, store.data)
            }.onFailure { DebugLog.e("Clock", "re-arm at start failed", it) }
        }
    }

    /** Whether alarms can be registered to the minute (Android 12 and up ask the user). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun setAlarmClock(context: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context, REQUEST_SHOW,
                MainActivityContract.intent(context, ROUTE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val ok = runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi) }.isSuccess
            if (ok) return
        }
        // No exact-alarm grant: the best Android allows is a wake-up that Doze
        // may hold back by some minutes. The Alarms tab says so and offers the grant.
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        val ok = exact && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.isSuccess
        if (!ok) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    internal fun broadcast(context: Context, action: String, request: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context, request,
            Intent(context, ClockReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- What a wake-up does --------------------------------------------

    /** The alarm wake-up fired: ring whatever is due. */
    fun onAlarmWake(context: Context, at: Long) {
        expireStale(context, at)
        var started: List<Ringing> = emptyList()
        update(context) { d ->
            val (next, rang) = ClockActions.fireAlarms(d, at, TimeZone.getDefault())
            started = rang
            next
        }
        if (started.isNotEmpty()) ClockRinger.start(context)
    }

    /** The timer wake-up fired, or a process start found timers that ran out. */
    fun onTimerWake(context: Context, at: Long, ringIfDue: Boolean = true) {
        expireStale(context, at)
        val config = ClockPrefs.get(context)
        var started: List<Ringing> = emptyList()
        update(context) { d ->
            val (next, rang) = ClockActions.fireTimers(d, at, config.pomodoro)
            started = rang
            next
        }
        if (ringIfDue && started.isNotEmpty()) ClockRinger.start(context)
    }

    // ---- Answers to a ring ----------------------------------------------

    fun dismiss(context: Context, ring: Ringing) {
        update(context) { ClockActions.dismiss(it, ring, System.currentTimeMillis()) }
    }

    fun dismissAll(context: Context) {
        val now = System.currentTimeMillis()
        update(context) { d -> d.ringing.fold(d) { acc, r -> ClockActions.dismiss(acc, r, now) } }
    }

    fun snooze(context: Context, ring: Ringing, minutes: Int = snoozeMinutesFor(context, ring)) {
        update(context) { ClockActions.snooze(it, ring, System.currentTimeMillis(), minutes) }
    }

    fun snoozeAll(context: Context) {
        val now = System.currentTimeMillis()
        update(context) { d ->
            d.ringing.fold(d) { acc, r -> ClockActions.snooze(acc, r, now, snoozeMinutesFor(context, r, d)) }
        }
    }

    /** The ring service's auto-silence ran out: everything still ringing went unanswered. */
    fun unansweredAll(context: Context) {
        val ringing = ClockStore.get(context).data.ringing
        if (ringing.isNotEmpty()) settleUnanswered(context, ringing, System.currentTimeMillis())
    }

    /** The auto-silence wake-up fired (see [rearm]). */
    fun onSilenceWake(context: Context, at: Long) = expireStale(context, at)

    fun endSnooze(context: Context, id: Long) {
        update(context) { ClockActions.endSnooze(it, id) }
    }

    fun skipComing(context: Context, id: Long) {
        update(context) { ClockActions.skipComing(it, id, System.currentTimeMillis()) }
    }

    /** The alarm's own snooze, or the tool's. Timers snooze for one minute. */
    fun snoozeMinutesFor(context: Context, ring: Ringing, data: ClockData = ClockStore.get(context).data): Int =
        when (ring.kind) {
            RingKind.TIMER -> 1
            RingKind.ALARM -> data.alarm(ring.ref)?.snoozeMinutes?.takeIf { it > 0 }
                ?: ClockPrefs.get(context).snoozeMinutes
        }

    /** Drops history past the retention setting. Called when a clock screen opens. */
    fun prune(context: Context) {
        val days = ClockPrefs.get(context).historyDays
        ClockStore.get(context).update { ClockActions.pruneHistory(it, System.currentTimeMillis(), days) }
    }

    /** The settings screen the "next alarm" icon in the status bar opens. */
    const val ROUTE = "clock"

    private const val REQUEST_ALARM = 1
    private const val REQUEST_TIMER = 2
    private const val REQUEST_SHOW = 3
    private const val REQUEST_UPCOMING = 4
    private const val REQUEST_SILENCE = 5
}

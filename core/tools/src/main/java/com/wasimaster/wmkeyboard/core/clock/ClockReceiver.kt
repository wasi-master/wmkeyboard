package com.wasimaster.wmkeyboard.core.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Where the clock's wake-ups and notification buttons land.
 *
 * Declared in the app manifest, not exported, direct-boot aware: an alarm has
 * to ring before the first unlock after a night-time reboot. Also listens for
 * the three system events that make registered wake-ups wrong: the clock set
 * by hand, the time zone changed, and the exact-alarm grant changing, and for
 * the app's own update, which drops every registration.
 */
class ClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val id = intent.getLongExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_ALARM -> ClockEngine.onAlarmWake(app, now)
            ACTION_TIMER -> ClockEngine.onTimerWake(app, now)
            ACTION_DISMISS_ALL -> ClockEngine.dismissAll(app)
            ACTION_SNOOZE_ALL -> ClockEngine.snoozeAll(app)
            ACTION_RING_SWIPED -> ClockNotifications.reshowRing(app)
            ACTION_SILENCE -> ClockEngine.onSilenceWake(app, now)
            ACTION_UPCOMING -> ClockNotifications.refresh(app, ClockStore.get(app).data)
            ACTION_END_SNOOZE -> ClockEngine.endSnooze(app, id)
            ACTION_SKIP_COMING -> ClockEngine.skipComing(app, id)
            ACTION_TIMER_PAUSE -> ClockEngine.update(app) { ClockActions.pauseTimer(it, id, now) }
            ACTION_TIMER_RESUME -> ClockEngine.update(app) { ClockActions.resumeTimer(it, id, now) }
            ACTION_TIMER_ADD -> ClockEngine.update(app) { ClockActions.extendTimer(it, id, ONE_MINUTE) }
            ACTION_TIMER_CANCEL -> ClockEngine.update(app) { ClockActions.cancelTimer(it, id, now) }
            ACTION_STOPWATCH_LAP -> ClockEngine.update(app) { ClockActions.stopwatchLap(it, now) }
            ACTION_STOPWATCH_PAUSE -> ClockEngine.update(app) { ClockActions.stopwatchPause(it, now) }
            ACTION_STOPWATCH_RESUME -> ClockEngine.update(app) { ClockActions.stopwatchStart(it, now) }
            ACTION_STOPWATCH_RESET -> ClockEngine.update(app) { ClockActions.stopwatchReset(it, now) }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_GRANT_CHANGED,
            -> if (ClockStore.exists(app)) {
                ClockEngine.onTimerWake(app, now)
                ClockEngine.rearm(app)
                ClockNotifications.refresh(app, ClockStore.get(app).data)
            }
        }
    }

    companion object {
        private const val P = "com.wasimaster.wmkeyboard.clock."
        const val ACTION_ALARM = P + "ALARM"
        const val ACTION_TIMER = P + "TIMER"
        const val ACTION_DISMISS_ALL = P + "DISMISS_ALL"
        const val ACTION_SNOOZE_ALL = P + "SNOOZE_ALL"
        const val ACTION_RING_SWIPED = P + "RING_SWIPED"
        const val ACTION_SILENCE = P + "SILENCE"
        const val ACTION_UPCOMING = P + "UPCOMING"
        const val ACTION_END_SNOOZE = P + "END_SNOOZE"
        const val ACTION_SKIP_COMING = P + "SKIP_COMING"
        const val ACTION_TIMER_PAUSE = P + "TIMER_PAUSE"
        const val ACTION_TIMER_RESUME = P + "TIMER_RESUME"
        const val ACTION_TIMER_ADD = P + "TIMER_ADD"
        const val ACTION_TIMER_CANCEL = P + "TIMER_CANCEL"
        const val ACTION_STOPWATCH_LAP = P + "SW_LAP"
        const val ACTION_STOPWATCH_PAUSE = P + "SW_PAUSE"
        const val ACTION_STOPWATCH_RESUME = P + "SW_RESUME"
        const val ACTION_STOPWATCH_RESET = P + "SW_RESET"
        const val EXTRA_ID = "id"

        /**
         * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`,
         * spelled out: the constant is API 31 and lint (InlinedApi, at error
         * here) cannot see that an older phone simply never sends it.
         */
        const val ACTION_EXACT_GRANT_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        const val ONE_MINUTE = 60_000L
    }
}

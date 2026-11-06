package com.wasimaster.wmkeyboard.ime.clock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast
import com.wasimaster.wmkeyboard.core.clock.Alarm
import com.wasimaster.wmkeyboard.core.clock.Weekdays
import com.wasimaster.wmkeyboard.ime.R

/**
 * The phone's own clock app, reached through Android's alarm intents (#1144),
 * for someone who wants every alarm in one place. Nothing is read from it: an
 * alarm handed over lives there from then on, and the Clock tool keeps its own.
 *
 * `SET_ALARM` is an install-time permission, granted without asking; the
 * phone's clock app is what checks it.
 */
internal object PhoneClock {

    /** Sets [alarm] in the phone's clock app without opening it. False when no app took it. */
    fun setAlarm(context: Context, alarm: Alarm): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            .putExtra(AlarmClock.EXTRA_VIBRATE, alarm.vibrate)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (alarm.label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, alarm.label)
        if (alarm.days != 0) {
            val days = ArrayList<Int>()
            for (i in 0 until 7) if (Weekdays.has(alarm.days, i)) days += Weekdays.calendarDay(i)
            intent.putExtra(AlarmClock.EXTRA_DAYS, days)
        }
        return start(context, intent)
    }

    /** Opens the phone's clock app on its list of alarms. False when there is none. */
    fun showAlarms(context: Context): Boolean = start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    /** Says that no clock app answered. */
    fun missing(context: Context) {
        Toast.makeText(context, R.string.ime_clock_phone_clock_missing, Toast.LENGTH_SHORT).show()
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

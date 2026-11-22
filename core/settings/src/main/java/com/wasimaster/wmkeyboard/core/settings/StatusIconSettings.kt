package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/**
 * The small icons at the far end of the toolbar that say something is going
 * on (#1453): an alarm about to ring, a birthday or a countdown that falls
 * today, a timer, a Pomodoro or the stopwatch running, a task that has fallen
 * due. The alarm and the special day first show as a chip on the idle
 * suggestion strip; closing the chip leaves the icon, and a tap on the icon
 * brings the chip back.
 *
 * Nested in [ToolbarBehavior] for the reason [GallerySettings] gives, with
 * flat `status_icon_*` DataStore keys read and written by [StatusIconPrefs].
 */
data class StatusIconSettings(
    /** An alarm ringing within [alarmMinutes], from the Clock tool or the phone's own clock app. */
    val alarm: Boolean = true,
    /** How long before an alarm it shows, in minutes. See [AlarmMinutesRange]. */
    val alarmMinutes: Int = 30,
    /** A birthday found in a note, or a Clock countdown, that falls today. */
    val specialDays: Boolean = true,
    /** A timer, a Pomodoro or the stopwatch of the Clock tool running. */
    val clock: Boolean = true,
    /** A task of the To-do tool that has fallen due, once its strip chip is closed. */
    val todo: Boolean = true,
    /**
     * The alarm and the special day show as a chip on the idle strip first,
     * and as the icon once the chip is closed. Off, they go straight to the icon.
     */
    val chips: Boolean = true,
) {
    companion object {
        val AlarmMinutesRange = 5..180
    }
}

/** The DataStore half of [StatusIconSettings]. */
object StatusIconPrefs {
    private val ALARM = booleanPreferencesKey("status_icon_alarm")
    private val ALARM_MINUTES = intPreferencesKey("status_icon_alarm_minutes")
    private val SPECIAL_DAYS = booleanPreferencesKey("status_icon_special_days")
    private val CLOCK = booleanPreferencesKey("status_icon_clock")
    private val TODO = booleanPreferencesKey("status_icon_todo")
    private val CHIPS = booleanPreferencesKey("status_icon_chips")

    fun read(p: Preferences, d: StatusIconSettings): StatusIconSettings = StatusIconSettings(
        alarm = p[ALARM] ?: d.alarm,
        alarmMinutes = p[ALARM_MINUTES]?.coerceIn(StatusIconSettings.AlarmMinutesRange) ?: d.alarmMinutes,
        specialDays = p[SPECIAL_DAYS] ?: d.specialDays,
        clock = p[CLOCK] ?: d.clock,
        todo = p[TODO] ?: d.todo,
        chips = p[CHIPS] ?: d.chips,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: StatusIconSettings, new: StatusIconSettings) {
        if (new.alarm != old.alarm) p[ALARM] = new.alarm
        if (new.alarmMinutes != old.alarmMinutes) {
            p[ALARM_MINUTES] = new.alarmMinutes.coerceIn(StatusIconSettings.AlarmMinutesRange)
        }
        if (new.specialDays != old.specialDays) p[SPECIAL_DAYS] = new.specialDays
        if (new.clock != old.clock) p[CLOCK] = new.clock
        if (new.todo != old.todo) p[TODO] = new.todo
        if (new.chips != old.chips) p[CHIPS] = new.chips
    }
}

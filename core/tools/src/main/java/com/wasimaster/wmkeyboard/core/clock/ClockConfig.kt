package com.wasimaster.wmkeyboard.core.clock

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.TimeZone
import kotlinx.serialization.Serializable

/**
 * The clock tool's settings.
 *
 * Not in `KeyboardSettings`, on purpose. The receiver an alarm wakes and the
 * service that rings both need the snooze length, the fade-in and the sound,
 * and both run below `:core:settings`, before the first unlock, where the
 * settings DataStore cannot be read. So the settings live here, in
 * device-protected preferences, read the same way by the keyboard, the
 * settings app and the ringer; the clock's backup section carries them.
 */
@Serializable
data class ClockConfig(
    /** Null follows the phone's 12/24-hour setting. */
    val hour24: Boolean? = null,
    val showSeconds: Boolean = true,
    val zones: List<WorldZone> = emptyList(),
    /** The world clocks list earliest time first rather than in the order they were put in (#970). */
    val sortZonesByTime: Boolean = false,
    /** The world clocks two to a row, smaller, rather than one card each (#970). */
    val zonesGrid: Boolean = false,
    val insertFormat: String = InsertFormat.TIME_PLACE.id,
    /** The [DiscordStyle.code] the Discord format types. */
    val discordStyle: String = "F",
    val workStartMinutes: Int = 9 * 60,
    val workEndMinutes: Int = 17 * 60,
    val snoozeMinutes: Int = 10,
    val fadeInSeconds: Int = 20,
    val autoSilenceMinutes: Int = 10,
    /**
     * How many times an alarm nobody answers snoozes itself, at the end of
     * [autoSilenceMinutes], before it counts as missed (#970). 0, the default,
     * stops it the first time; [SNOOZE_UNTIL_ANSWERED] never counts it missed,
     * so an important alarm is still ringing when someone comes back to it.
     */
    val autoSnoozes: Int = 0,
    /**
     * Minutes before an alarm a notice says it is coming, with a button that
     * skips it (#970). 0, the default, posts none.
     */
    val upcomingNoticeMinutes: Int = 0,
    /** A notice in the shade while an alarm is snoozed, with a button that ends the snooze (#970). */
    val snoozedNotice: Boolean = true,
    /** A notice in the shade for an alarm that rang with nobody there (#970). */
    val missedNotice: Boolean = true,
    val vibrate: Boolean = true,
    /** Null rings the system's default alarm sound; empty rings nothing. */
    val alarmSound: String? = null,
    val timerSound: String? = null,
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val longBreakEvery: Int = 4,
    /** Days of history to keep; 0 keeps it until the cap. */
    val historyDays: Int = 90,
    /** Volume keys snooze a ringing alarm (otherwise they dismiss nothing and only silence). */
    val volumeKeysSnooze: Boolean = true,
    /** The phone's own zone and UTC, held ahead of the cities (#970). */
    val pinLocalZone: Boolean = false,
    val pinUtc: Boolean = false,
    /** The panel tab last open, by [ClockTab.id]. */
    val lastTab: String = "clocks",
) {
    companion object {
        /** [autoSnoozes] for an alarm that snoozes itself until someone answers it (#970). */
        const val SNOOZE_UNTIL_ANSWERED = Int.MAX_VALUE
    }
    val pomodoro: PomodoroPlan
        get() = PomodoroPlan(focusMinutes, shortBreakMinutes, longBreakMinutes, longBreakEvery)

    fun is24(systemIs24: Boolean): Boolean = hour24 ?: systemIs24

    /**
     * [zones] in the order the world clocks show them at [now]: as arranged,
     * or by the time there, earliest first, when [sortZonesByTime] is on.
     * Stable, so two cities on one offset keep the order they were put in.
     */
    fun shownZones(now: Long = System.currentTimeMillis()): List<WorldZone> {
        val cities = if (sortZonesByTime) zones.sortedBy { TimeZone.getTimeZone(it.zoneId).getOffset(now) } else zones
        val pinned = buildList {
            if (pinLocalZone) add(WorldZone(TimeZone.getDefault().id))
            if (pinUtc) add(WorldZone("UTC", label = "UTC"))
        }
        return (pinned + cities).distinctBy { it.zoneId }
    }
}

/** The panel's tabs, in order. */
enum class ClockTab(val id: String) {
    CLOCKS("clocks"), ALARMS("alarms"), TIMERS("timers"), STOPWATCH("stopwatch"),
    PLANNER("planner"), DATES("dates"), HISTORY("history");

    companion object {
        fun of(id: String?): ClockTab = entries.firstOrNull { it.id == id } ?: CLOCKS
    }
}

/** Where [ClockConfig] is kept: one JSON string in device-protected preferences. */
object ClockPrefs {

    private const val PREFS = "clock"
    private const val KEY = "config"

    @Volatile
    private var flow: MutableStateFlow<ClockConfig>? = null

    fun state(context: Context): StateFlow<ClockConfig> = flowFor(context)

    fun get(context: Context): ClockConfig = flowFor(context).value

    fun update(context: Context, change: (ClockConfig) -> ClockConfig) {
        val f = flowFor(context)
        synchronized(this) {
            val next = change(f.value)
            if (next == f.value) return
            f.value = next
            val text = ClockStore.json.encodeToString(ClockConfig.serializer(), next)
            runCatching { prefs(context).edit { putString(KEY, text) } }
        }
    }

    /** The stored JSON, for the backup. */
    fun raw(context: Context): String? = runCatching { prefs(context).getString(KEY, null) }.getOrNull()

    /** Restores the JSON a backup carried. Unknown or broken text is ignored. */
    fun restore(context: Context, text: String) {
        val parsed = runCatching { ClockStore.json.decodeFromString(ClockConfig.serializer(), text) }.getOrNull()
            ?: return
        update(context) { parsed }
    }

    private fun flowFor(context: Context): MutableStateFlow<ClockConfig> =
        flow ?: synchronized(this) {
            flow ?: MutableStateFlow(load(context)).also { flow = it }
        }

    private fun load(context: Context): ClockConfig {
        val text = runCatching { prefs(context).getString(KEY, null) }.getOrNull() ?: return ClockConfig()
        return runCatching { ClockStore.json.decodeFromString(ClockConfig.serializer(), text) }
            .getOrElse { ClockConfig() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.wasimaster.wmkeyboard.ime.clock

import com.wasimaster.wmkeyboard.core.clock.Alarm
import com.wasimaster.wmkeyboard.core.clock.ClockConfig
import com.wasimaster.wmkeyboard.core.clock.ClockData
import com.wasimaster.wmkeyboard.core.clock.ClockTab
import com.wasimaster.wmkeyboard.core.clock.DateMath
import com.wasimaster.wmkeyboard.core.clock.DiscordStyle
import com.wasimaster.wmkeyboard.core.clock.HistoryEntry
import com.wasimaster.wmkeyboard.core.clock.HistoryKind
import com.wasimaster.wmkeyboard.core.clock.InsertFormat
import com.wasimaster.wmkeyboard.core.clock.LocalDay
import com.wasimaster.wmkeyboard.core.clock.Weekdays
import com.wasimaster.wmkeyboard.core.clock.ZoneEntry

/**
 * Everything the Clock panel draws, owned by [ClockController] rather than
 * `KeyboardUiState`: the panel is the only reader, and a stopwatch ticking
 * should not be a reason for the whole keyboard state to change.
 *
 * The one piece that does live in `KeyboardUiState` is the text being typed
 * into the panel ([ClockTextEntry]), because the keyboard's shared capture
 * path (caret, glide, suggestions, backspace) works on that state.
 */
data class ClockUi(
    val tab: ClockTab = ClockTab.CLOCKS,
    val data: ClockData = ClockData(),
    val config: ClockConfig = ClockConfig(),
    /** The phone's own 12/24-hour setting, which [ClockConfig.hour24] may follow. */
    val systemHour24: Boolean = false,
    /** Zone search results while the search box is open; null when it is closed. */
    val search: List<ZoneEntry>? = null,
    /** The clock whose insert sheet is open: a zone id, or "" for the local one. */
    val insertZone: String? = null,
    /** The moment the open insert sheet types; null types the time now. */
    val insertWhen: InsertWhen? = null,
    val alarmEdit: AlarmEdit? = null,
    /** The timer keypad's digits, right-aligned into h:mm:ss like a microwave. */
    val timerDigits: String = "",
    /** A label for the next timer started from the keypad. */
    val timerLabel: String = "",
    /** Whether the Timers tab is in Pomodoro mode. */
    val pomodoroMode: Boolean = false,
    val planner: PlannerUi = PlannerUi(),
    val dates: DatesUi = DatesUi(),
    val historyFilter: HistoryKind? = null,
    /** History rows open to show their laps. */
    val historyOpen: Set<Long> = emptySet(),
    val exactAllowed: Boolean = true,
    val fullScreenAllowed: Boolean = true,
    val notificationsAllowed: Boolean = true,
    /** Something just removed, with the data to put it back. */
    val undo: ClockUndo? = null,
) {
    val hour24: Boolean get() = config.is24(systemHour24)

    val insertFormat: InsertFormat get() = InsertFormat.of(config.insertFormat) ?: InsertFormat.TIME_PLACE

    val discordStyle: DiscordStyle get() = DiscordStyle.of(config.discordStyle) ?: DiscordStyle.LONG_DATE_TIME
}

/**
 * A moment picked in the insert sheet, on the clock of the card it was
 * opened from: [dayOffset] days from today there, at [hour]:[minute].
 */
data class InsertWhen(val dayOffset: Int, val hour: Int, val minute: Int)

/** What the keyboard's own text box in the panel is for. */
enum class ClockTextField { SEARCH, ALARM_LABEL, TIMER_LABEL, COUNTDOWN_TITLE, ZONE_LABEL, DATE }

/**
 * The text box the panel is typing into: lives in `KeyboardUiState` so the
 * shared capture path can type into it. [ref] says which zone a
 * [ClockTextField.ZONE_LABEL] renames, and which [DateField] a
 * [ClockTextField.DATE] sets.
 */
data class ClockTextEntry(val field: ClockTextField, val text: String = "", val ref: String = "")

/** An alarm being made or changed. [id] is null for a new one. */
data class AlarmEdit(
    val id: Long?,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val label: String = "",
    val vibrate: Boolean = true,
    /** 0 takes the tool's default. */
    val snoozeMinutes: Int = 0,
) {
    fun toAlarm(base: Alarm?): Alarm =
        (base ?: Alarm(id = 0, hour = hour, minute = minute)).copy(
            hour = hour, minute = minute, days = days, label = label.trim(),
            vibrate = vibrate, snoozeMinutes = snoozeMinutes,
        )

    companion object {
        fun of(a: Alarm) = AlarmEdit(a.id, a.hour, a.minute, a.days, a.label, a.vibrate, a.snoozeMinutes)
        val WEEKDAY_PRESETS = listOf(Weekdays.WORKDAYS, Weekdays.WEEKEND, Weekdays.ALL)
    }
}

/** The meeting planner: which day, and which half hour the cursor sits on (null: the current one). */
data class PlannerUi(val dayOffset: Int = 0, val slot: Int? = null)

enum class DatesMode { BETWEEN, ADD, COUNTDOWNS }

/** Which date a stepper row is moving. */
enum class DateField { FROM, TO, BASE, COUNTDOWN }

data class DatesUi(
    val mode: DatesMode = DatesMode.BETWEEN,
    /** Null means today, so the panel stays right across midnight. */
    val from: LocalDay? = null,
    val to: LocalDay? = null,
    val base: LocalDay? = null,
    val amount: Int = 30,
    val unit: DateMath.Unit = DateMath.Unit.DAYS,
    /** The day a new countdown is being set for, while one is being made. */
    val newCountdown: LocalDay? = null,
)

sealed interface ClockUndo {
    data class AlarmRemoved(val alarm: Alarm) : ClockUndo
    data class HistoryCleared(val entries: List<HistoryEntry>) : ClockUndo
    data class ZoneRemoved(val index: Int, val zone: com.wasimaster.wmkeyboard.core.clock.WorldZone) : ClockUndo
}

/** Everything the panel can ask for, as one type, so the callback bundle stays one field. */
sealed interface ClockAction {
    data class Tab(val tab: ClockTab) : ClockAction
    data object OpenSettings : ClockAction
    data object OpenApp : ClockAction

    // Clocks
    data object SearchOpen : ClockAction
    data object SearchClose : ClockAction
    data class AddZone(val zone: ZoneEntry) : ClockAction
    data class RemoveZone(val zoneId: String) : ClockAction
    data class MoveZone(val zoneId: String, val by: Int) : ClockAction
    data class RenameZone(val zoneId: String) : ClockAction
    data class InsertSheet(val zoneId: String?) : ClockAction
    data class Insert(val zoneId: String, val format: InsertFormat, val at: Long? = null) : ClockAction
    data class InsertDefault(val zoneId: String) : ClockAction
    data class SetDefaultFormat(val format: InsertFormat) : ClockAction
    data class InsertDiscord(val zoneId: String, val style: DiscordStyle) : ClockAction

    /** Makes [style] the Discord style and Discord the default format. */
    data class SetDefaultDiscord(val style: DiscordStyle) : ClockAction

    /** Sets the moment the insert sheet types; null goes back to now. */
    data class SetInsertWhen(val at: InsertWhen?) : ClockAction
    data object InsertAllClocks : ClockAction

    // Alarms
    data object NewAlarm : ClockAction
    data class QuickAlarm(val minutesFromNow: Int) : ClockAction
    data class EditAlarm(val id: Long) : ClockAction
    data class AlarmDraft(val edit: AlarmEdit) : ClockAction
    data object AlarmLabel : ClockAction
    data object SaveAlarm : ClockAction
    data object CancelAlarmEdit : ClockAction
    data class ToggleAlarm(val id: Long, val on: Boolean) : ClockAction
    data class SkipAlarm(val id: Long) : ClockAction
    data class DeleteAlarm(val id: Long) : ClockAction

    /** Hands the alarm to the phone's own clock app and drops it here (#1144). */
    data class MoveAlarmToPhone(val id: Long) : ClockAction

    /** Opens the phone's own clock app on its alarms (#1144). */
    data object PhoneAlarms : ClockAction
    data object AskExact : ClockAction
    data object AskFullScreen : ClockAction

    // Timers
    data class TimerDigit(val digit: Char) : ClockAction
    data object TimerBackspace : ClockAction
    data object TimerClear : ClockAction
    data class TimerAdd(val ms: Long) : ClockAction
    data object TimerLabel : ClockAction
    data object StartTimer : ClockAction
    data class StartPreset(val id: Long) : ClockAction
    data object SavePreset : ClockAction
    data class DeletePreset(val id: Long) : ClockAction
    data class PomodoroMode(val on: Boolean) : ClockAction
    data object StartPomodoro : ClockAction
    data class PauseTimer(val id: Long) : ClockAction
    data class ResumeTimer(val id: Long) : ClockAction
    data class ExtendTimer(val id: Long, val ms: Long) : ClockAction
    data class ResetTimer(val id: Long) : ClockAction
    data class CancelTimer(val id: Long) : ClockAction

    // Stopwatch
    data object StopwatchToggle : ClockAction
    data object StopwatchLap : ClockAction
    data object StopwatchReset : ClockAction
    data object StopwatchInsert : ClockAction

    // Planner
    data class PlannerDay(val by: Int) : ClockAction
    data class PlannerSlot(val slot: Int?) : ClockAction
    data object PlannerBest : ClockAction
    data object PlannerInsert : ClockAction
    data object PlannerDiscord : ClockAction

    // Dates
    data class DatesModeSet(val mode: DatesMode) : ClockAction
    data class DateStep(val field: DateField, val days: Long, val months: Long = 0) : ClockAction
    data class DateToday(val field: DateField) : ClockAction

    /** A date typed into the panel's box instead of stepped to. */
    data class DateType(val field: DateField) : ClockAction
    data object DatesSwap : ClockAction
    data class AddAmount(val by: Int) : ClockAction
    data class AddUnit(val unit: DateMath.Unit) : ClockAction
    data object DatesInsert : ClockAction
    data object NewCountdown : ClockAction
    data object CountdownTitle : ClockAction
    data object SaveCountdown : ClockAction
    data object CancelCountdown : ClockAction
    data class DeleteCountdown(val id: Long) : ClockAction
    data class InsertCountdown(val id: Long) : ClockAction

    // History
    data class HistoryFilter(val kind: HistoryKind?) : ClockAction
    data class HistoryToggle(val id: Long) : ClockAction
    data class HistoryInsert(val id: Long) : ClockAction
    data class HistoryDelete(val id: Long) : ClockAction
    data object HistoryClear : ClockAction

    /** The Done beside the panel's text box: what Enter would do. */
    data object TextDone : ClockAction

    data object Undo : ClockAction
    data object UndoDismiss : ClockAction
}

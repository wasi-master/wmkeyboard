package com.wasimaster.wmkeyboard.core.clock

import kotlinx.serialization.Serializable

/**
 * Everything the clock tool keeps, in one value: alarms, timer presets, the
 * timers and the stopwatch that are running, countdowns to dates, and the
 * history of what rang and what was timed.
 *
 * One value rather than a file per kind because the keyboard panel, the
 * settings app's Clock screen, the receiver an alarm wakes and the service that
 * rings all read it, often within the same second, and one snapshot can never
 * disagree with itself. See [ClockStore].
 *
 * The world clocks are not here: they are a setting (`ClockSettings.zones`),
 * so they travel with the user's other settings to a new phone and sync. What
 * is here belongs to this phone: two phones that both ring for one alarm is a
 * bug, not a feature.
 */
@Serializable
data class ClockData(
    val alarms: List<Alarm> = emptyList(),
    val presets: List<TimerPreset> = DefaultPresets,
    val timers: List<RunningTimer> = emptyList(),
    val stopwatch: Stopwatch = Stopwatch(),
    val countdowns: List<Countdown> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    /** What is ringing right now, oldest first. Empty almost always. */
    val ringing: List<Ringing> = emptyList(),
    /** The last id handed out, shared by every kind so ids never collide. */
    val lastId: Long = 0,
) {
    /** A fresh id, with the copy of this value that has used it. */
    fun nextId(): Pair<Long, ClockData> = (lastId + 1).let { it to copy(lastId = it) }

    fun alarm(id: Long): Alarm? = alarms.firstOrNull { it.id == id }

    fun timer(id: Long): RunningTimer? = timers.firstOrNull { it.id == id }

    companion object {
        val DefaultPresets: List<TimerPreset> = listOf(
            TimerPreset(id = -1, label = "", durationMs = 60_000),
            TimerPreset(id = -2, label = "", durationMs = 3 * 60_000),
            TimerPreset(id = -3, label = "", durationMs = 5 * 60_000),
            TimerPreset(id = -4, label = "", durationMs = 10 * 60_000),
            TimerPreset(id = -5, label = "", durationMs = 25 * 60_000),
        )
    }
}

/**
 * One alarm.
 *
 * [days] is a bit set, Monday in bit 0 to Sunday in bit 6 (see [Weekdays]). An
 * alarm with no days rings once, at the next [hour]:[minute], and turns itself
 * off after it has rung, the way every phone's clock behaves.
 */
@Serializable
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
    /**
     * A ringtone URI. Null rings the system's default alarm sound; an empty
     * string rings nothing and only vibrates.
     */
    val sound: String? = null,
    val vibrate: Boolean = true,
    /** Minutes a snooze lasts; 0 takes the tool's default. */
    val snoozeMinutes: Int = 0,
    /** Seconds the sound takes to reach full volume; -1 takes the tool's default. */
    val fadeInSeconds: Int = -1,
    /**
     * Occurrences before this instant are skipped: "skip the next one" on a
     * repeating alarm, without turning it off for the rest of the week.
     */
    val skipUntil: Long = 0,
    /** A snooze in progress: the alarm rings again at this instant. */
    val snoozedUntil: Long = 0,
    /** How many times the current ring has been snoozed. */
    val snoozeCount: Int = 0,
    /**
     * How often this alarm snoozes itself when nobody answers (#970), as
     * [ClockConfig.autoSnoozes] counts it. -1 takes the tool's setting.
     */
    val autoSnoozes: Int = -1,
) {
    val repeats: Boolean get() = days != 0
}

/** A duration the user keeps reaching for: tea, eggs, a plank. */
@Serializable
data class TimerPreset(
    val id: Long,
    val label: String,
    val durationMs: Long,
)

/**
 * A timer that has been started.
 *
 * Running, it ends at [endsAt] (wall clock). Paused, [pausedRemainingMs] holds
 * what is left and [endsAt] means nothing. Wall clock rather than the uptime
 * clock so the timer survives the process being killed, which for a keyboard
 * happens all the time; the price is a timer that moves when the user changes
 * the phone's clock by hand, which they can see and forgive.
 */
@Serializable
data class RunningTimer(
    val id: Long,
    val label: String,
    val totalMs: Long,
    val endsAt: Long,
    val pausedRemainingMs: Long = -1,
    val startedAt: Long = 0,
    /** Set while this timer is one phase of a Pomodoro run. */
    val pomodoro: PomodoroRun? = null,
) {
    val paused: Boolean get() = pausedRemainingMs >= 0

    fun remainingAt(now: Long): Long =
        if (paused) pausedRemainingMs else (endsAt - now).coerceAtLeast(0)

    /** 0 at the start, 1 at the end. */
    fun progressAt(now: Long): Float =
        if (totalMs <= 0) 1f else (1f - remainingAt(now).toFloat() / totalMs).coerceIn(0f, 1f)
}

/** Which Pomodoro phase a timer is, and how many focus phases came before it. */
@Serializable
data class PomodoroRun(
    val phase: PomodoroPhase,
    /** Focus phases finished in this run, counting from 0. */
    val completed: Int,
)

@Serializable
enum class PomodoroPhase { FOCUS, SHORT_BREAK, LONG_BREAK }

/**
 * The stopwatch. Running, it counts from [startedAt] on top of [accumulatedMs];
 * stopped, [accumulatedMs] is the whole reading. [laps] holds the reading at
 * each lap press, so a lap's own length is the difference from the one before.
 */
@Serializable
data class Stopwatch(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val accumulatedMs: Long = 0,
    val laps: List<Long> = emptyList(),
) {
    fun elapsedAt(now: Long): Long =
        accumulatedMs + if (running) (now - startedAt).coerceAtLeast(0) else 0

    val isReset: Boolean get() = !running && accumulatedMs == 0L && laps.isEmpty()

    /** Each lap's own length, first lap first. */
    val lapLengths: List<Long>
        get() = laps.mapIndexed { i, at -> at - (if (i == 0) 0 else laps[i - 1]) }
}

/** A date to count down to: a trip, a deadline, a birthday. */
@Serializable
data class Countdown(
    val id: Long,
    val title: String,
    val target: Long,
    /** True when only the day matters, and the count is in days. */
    val allDay: Boolean = true,
)

/** Something that is ringing now and waits for the user. */
@Serializable
data class Ringing(
    val kind: RingKind,
    /** The alarm's or the timer's id. */
    val ref: Long,
    val since: Long,
    val label: String = "",
)

@Serializable
enum class RingKind { ALARM, TIMER }

/** One line in the clock's history. */
@Serializable
data class HistoryEntry(
    val id: Long,
    val kind: HistoryKind,
    val at: Long,
    val label: String = "",
    val durationMs: Long = 0,
    val outcome: HistoryOutcome = HistoryOutcome.DONE,
    /** A stopwatch run's lap readings, the same shape as [Stopwatch.laps]. */
    val laps: List<Long> = emptyList(),
)

@Serializable
enum class HistoryKind { ALARM, TIMER, STOPWATCH, POMODORO }

@Serializable
enum class HistoryOutcome {
    /** Rang and was dismissed, or ran to the end. */
    DONE,

    /** Rang and was snoozed. */
    SNOOZED,

    /** Rang until it gave up by itself, with nobody there. */
    MISSED,

    /** Stopped before it finished. */
    CANCELLED,
}

/**
 * A city on the world clock. [zoneId] is a tz database id. [label] overrides
 * the city name the system knows for the zone ("Home", "Mum"), and the
 * coordinates, when the city was found by name online, give it a sunrise and a
 * sunset.
 */
@Serializable
data class WorldZone(
    val zoneId: String,
    val label: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** The [Alarm.days] bit set. Monday is bit 0, the ISO order. */
object Weekdays {
    const val ALL = 0b111_1111
    const val WORKDAYS = 0b001_1111
    const val WEEKEND = 0b110_0000

    /** The bit for a `Calendar.DAY_OF_WEEK` value. */
    fun bitFor(calendarDay: Int): Int = 1 shl isoIndex(calendarDay)

    /** 0 for Monday to 6 for Sunday, from a `Calendar.DAY_OF_WEEK` value (Sunday = 1). */
    fun isoIndex(calendarDay: Int): Int = (calendarDay + 5) % 7

    /** The `Calendar.DAY_OF_WEEK` value for an ISO index. */
    fun calendarDay(isoIndex: Int): Int = (isoIndex + 1) % 7 + 1

    fun has(days: Int, isoIndex: Int): Boolean = days and (1 shl isoIndex) != 0

    fun toggle(days: Int, isoIndex: Int): Int = days xor (1 shl isoIndex)
}

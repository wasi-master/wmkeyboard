package com.wasimaster.wmkeyboard.ime

import android.app.AlarmManager
import android.content.Context
import com.wasimaster.wmkeyboard.core.clock.AlarmMath
import com.wasimaster.wmkeyboard.core.clock.ClockData
import com.wasimaster.wmkeyboard.core.clock.ClockStore
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlightKind
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlights
import com.wasimaster.wmkeyboard.core.notes.assist.NoteReminders
import com.wasimaster.wmkeyboard.core.settings.StatusIconSettings
import java.util.Locale
import java.util.TimeZone

/** What a status icon on the toolbar stands for (#1453). */
enum class StatusKind { ALARM, SPECIAL_DAY, TIMER, POMODORO, STOPWATCH, TODO }

/**
 * One thing going on, as the toolbar's icon and, for an alarm or a special
 * day, the strip's chip show it (#1453).
 */
data class StatusItem(
    val kind: StatusKind,
    /** The alarm's label, the birthday's line in its note, the countdown's or the task's words; may be empty. */
    val title: String = "",
    /** When: the alarm's ring, a running timer's end. 0 when it has none. */
    val at: Long = 0,
    /** The Clock tool's alarm, the note or the countdown it is about; -1 for none. */
    val ref: Long = -1,
    /** An alarm of the phone's own clock app, which the keyboard can only ask to stop. */
    val foreign: Boolean = false,
    /** A special day found in a note, rather than a Clock countdown. */
    val fromNote: Boolean = false,
) {
    /** Identity across refreshes, so a chip closed by hand stays closed for this one occurrence. */
    val key: String get() = "$kind:$ref:$at:$title"

    /** An alarm or a special day: these show as a chip first. */
    val chipFirst: Boolean get() = kind == StatusKind.ALARM || kind == StatusKind.SPECIAL_DAY
}

/** The strip's status chip, if any, and the toolbar's status icons. */
data class StatusUi(val chip: StatusItem? = null, val icons: List<StatusItem> = emptyList())

/** What a status chip or icon was asked to do. */
enum class StatusAction {
    /** The chip's face: where the thing lives (the Clock tool, the note, the list). */
    OPEN,

    /** The alarm chip's Stop: this ring will not happen. */
    STOP,

    /** The chip's ✕: the icon takes its place. */
    CLOSE,

    /** The toolbar icon: the chip comes back, or the tool opens. */
    ICON,
}

/**
 * Where the chips and icons send their taps. A holder rather than a callback
 * on the strip, for the reason [TodoChipActions] gives.
 */
object StatusActions {
    @Volatile
    var handler: (StatusItem, StatusAction) -> Unit = { _, _ -> }
}

/**
 * Which of [items] goes on the strip and which on the toolbar (#1453). Pure,
 * so the rules are tested off the device.
 *
 * One chip at a time, the first alarm or special day not closed by hand, and
 * none while [stripBusy] (a due task's chip has the strip) or with [chips]
 * off. Every other item, a closed chip's included, is an icon, in the order
 * [items] gives.
 */
object StatusLayout {
    fun arrange(items: List<StatusItem>, closed: Set<String>, chips: Boolean, stripBusy: Boolean): StatusUi {
        val chip = if (chips && !stripBusy) items.firstOrNull { it.chipFirst && it.key !in closed } else null
        return StatusUi(chip = chip, icons = items.filter { it != chip })
    }

    /**
     * The next moment the items may change by themselves: an alarm entering
     * its window or ringing, a timer ending, midnight. Null when none is
     * within [horizonMs] of [now].
     */
    fun nextChange(items: List<StatusItem>, upcomingAlarm: Long?, windowMs: Long, now: Long, midnight: Long, horizonMs: Long): Long? {
        val times = ArrayList<Long>()
        upcomingAlarm?.let { at ->
            times += at - windowMs
            times += at
        }
        items.forEach { if (it.at > 0) times += it.at }
        times += midnight
        return times.filter { it > now && it - now <= horizonMs }.minOrNull()
    }
}

/**
 * Works out what the toolbar's status icons say (#1453), from the Clock
 * tool's data, the phone's next alarm, today's birthdays in the notes and the
 * Clock's countdowns. Blocking reads; call off the main thread.
 */
internal class StatusIconSource(private val context: Context) {

    /** The birthdays of today, worked out once a day and again when a note changes. */
    private var specialCache: Pair<String, List<StatusItem>>? = null

    /** The alarm next in line, whether or not it is in its window yet; set by [alarm]. */
    private var upcomingAlarm: Long? = null

    /**
     * Everything going on now, and when the next alarm rings (in its window
     * or not), for the refresh that waits for it. [notes] is the readable
     * notes as (id, body, edited-at), locked ones already left out. [todoDue]
     * is the words of a due task whose strip chip is not up, or null.
     * Synchronized: a refresh cancelled half way may still be reading when
     * the next one starts.
     */
    @Synchronized
    fun items(
        settings: StatusIconSettings,
        notes: () -> List<Triple<Long, String, Long>>,
        todoDue: String?,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Pair<List<StatusItem>, Long?> {
        val out = ArrayList<StatusItem>()
        val clock = if (ClockStore.exists(context)) ClockStore.get(context).data else null
        upcomingAlarm = null
        if (settings.alarm) alarm(clock, settings.alarmMinutes * MINUTE_MS, now)?.let(out::add)
        if (settings.specialDays) out += specialDays(clock, notes, now, zone)
        if (settings.clock && clock != null) out += running(clock)
        if (settings.todo && todoDue != null) out += StatusItem(StatusKind.TODO, todoDue)
        return out to upcomingAlarm
    }

    /** The soonest alarm ringing within [windowMs], the Clock tool's or the phone clock app's. */
    private fun alarm(clock: ClockData?, windowMs: Long, now: Long): StatusItem? {
        val own = clock?.takeIf { it.ringing.isEmpty() }?.let { AlarmMath.nextOfAll(it.alarms, now) }
        val info = runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull()
            ?.takeIf { it.showIntent?.creatorPackage != context.packageName }
        val ownAt = own?.second
        val otherAt = info?.triggerTime
        upcomingAlarm = listOfNotNull(ownAt, otherAt).minOrNull()
        val inWindow = { at: Long -> at - now in 1..windowMs }
        return when {
            own != null && ownAt != null && inWindow(ownAt) && (otherAt == null || ownAt <= otherAt || !inWindow(otherAt)) ->
                StatusItem(StatusKind.ALARM, own.first.label, ownAt, own.first.id)
            otherAt != null && inWindow(otherAt) -> StatusItem(StatusKind.ALARM, "", otherAt, foreign = true)
            else -> null
        }
    }

    /** A timer or a Pomodoro running, and the stopwatch running. */
    private fun running(clock: ClockData): List<StatusItem> {
        val out = ArrayList<StatusItem>()
        val pomodoro = clock.timers.firstOrNull { it.pomodoro != null }
        val timer = clock.timers.filter { it.pomodoro == null }.minByOrNull { if (it.paused) Long.MAX_VALUE else it.endsAt }
        if (pomodoro != null) out += StatusItem(StatusKind.POMODORO, pomodoro.label, if (pomodoro.paused) 0 else pomodoro.endsAt, pomodoro.id)
        if (timer != null) out += StatusItem(StatusKind.TIMER, timer.label, if (timer.paused) 0 else timer.endsAt, timer.id)
        if (clock.stopwatch.running) out += StatusItem(StatusKind.STOPWATCH)
        return out
    }

    /** Birthdays in notes and Clock countdowns that fall today. */
    private fun specialDays(
        clock: ClockData?,
        notes: () -> List<Triple<Long, String, Long>>,
        now: Long,
        zone: TimeZone,
    ): List<StatusItem> {
        val today = NoteReminders.dayStart(now, zone)
        val out = ArrayList<StatusItem>()
        clock?.countdowns?.forEach { c ->
            if (NoteReminders.dayStart(c.target, zone) == today) {
                out += StatusItem(StatusKind.SPECIAL_DAY, c.title, today, c.id)
            }
        }
        val list = notes()
        val stamp = "$today:" + list.sumOf { (id, _, edited) -> id * 31 + edited }
        val cached = specialCache?.takeIf { it.first == stamp }?.second
        val birthdays = cached ?: birthdaysToday(list, today, now, zone).also { specialCache = stamp to it }
        out += birthdays
        return out.distinctBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun birthdaysToday(
        notes: List<Triple<Long, String, Long>>,
        today: Long,
        now: Long,
        zone: TimeZone,
    ): List<StatusItem> {
        val out = ArrayList<StatusItem>()
        for ((id, body, edited) in notes.take(MAX_NOTES)) {
            if (body.isBlank()) continue
            val text = if (body.length > MAX_NOTE_CHARS) body.substring(0, MAX_NOTE_CHARS) else body
            val found = runCatching { NoteHighlights.find(text, edited.takeIf { it > 0 } ?: now, zone, Locale.getDefault()) }
                .getOrDefault(emptyList())
            for (h in found) {
                if (h.kind != NoteHighlightKind.BIRTHDAY) continue
                val at = h.whenMillis ?: continue
                val eventZone = h.zoneId?.let(TimeZone::getTimeZone) ?: zone
                if (NoteReminders.nextYearly(at, now, eventZone) != today) continue
                out += StatusItem(StatusKind.SPECIAL_DAY, h.line.ifBlank { h.text }.trim(), today, id, fromNote = true)
            }
        }
        return out
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /** Notes read for birthdays, newest first; the rest wait for an edit to bring them forward. */
        private const val MAX_NOTES = 400

        /** Characters of one note read for birthdays. */
        private const val MAX_NOTE_CHARS = 20_000
    }
}

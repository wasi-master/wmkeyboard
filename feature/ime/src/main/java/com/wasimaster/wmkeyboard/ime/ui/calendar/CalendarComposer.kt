package com.wasimaster.wmkeyboard.ime.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DoNotDisturbOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EventRepeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.calendar.Availability
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDraft
import com.wasimaster.wmkeyboard.core.tools.calendar.Frequency
import com.wasimaster.wmkeyboard.core.tools.calendar.Guest
import com.wasimaster.wmkeyboard.core.tools.calendar.NthWeekday
import com.wasimaster.wmkeyboard.core.tools.calendar.Recurrence
import com.wasimaster.wmkeyboard.core.tools.calendar.RepeatEnd
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarAction
import com.wasimaster.wmkeyboard.ime.calendar.CalendarError
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUi
import com.wasimaster.wmkeyboard.ime.calendar.ComposeSheet
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockSwitch
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.clock.TimeWheel
import com.wasimaster.wmkeyboard.ime.ui.clock.clockChip
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import java.text.DateFormatSymbols
import java.util.TimeZone

/**
 * The event composer as a tray: a preview card of the event being built,
 * which updates as it is built, and under it the pieces of the event as
 * chips (title, when, where, who, repeat, reminders, calendar, notes, busy or
 * free). A piece that is set reads full; one that is not reads as its hint.
 * Tapping a text piece sends the keys to it; tapping any other unfolds its
 * editor under the tray, one at a time. Along the bottom, Add to calendar
 * (or Save changes) as the pill, Insert as text and the calendar app as
 * round buttons. Cancel lives in the header.
 */
@Composable
internal fun CalendarComposer(d: EventDraft, ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any && !kb.reduceMotion
    fun change(next: EventDraft) = act(CalendarAction.Draft(next))
    var section by remember { mutableStateOf<Section?>(null) }
    fun toggle(s: Section) {
        section = if (section == s) null else s
        // A date or time drum left open under the old section closes with it.
        ui.sheet?.let { act(CalendarAction.Sheet(it)) }
    }
    val calendar = ui.calendars.firstOrNull { it.id == d.calendarId }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.error?.let { e ->
                // Where the keyboard cannot write, the calendar app still can.
                val handOff = e == CalendarError.NO_ACCESS || e == CalendarError.NO_CALENDAR || e == CalendarError.WRITE_FAILED
                Banner(
                    errorText(e),
                    stringResource(if (handOff && !ui.editing) R.string.ime_calendar_open_app else R.string.ime_calendar_done),
                ) { act(if (handOff && !ui.editing) CalendarAction.DraftInApp else CalendarAction.ErrorDismiss) }
            }
            DraftPreview(d, ui, animate) { act(CalendarAction.Type(CalendarTextField.TITLE)) }
            Tray(d, ui, calendar != null, section, animate, onToggle = ::toggle, act = act)
            val open = section
            if (open != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .popIn(0, animate, key = open)
                        .padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (open) {
                        Section.WHEN -> WhenEditor(d, ui, act, ::change)
                        Section.REPEAT -> RepeatEditor(d, ui.today) { change(d.copy(recurrence = it)) }
                        Section.REMINDERS -> RemindersEditor(d, ui, ::change)
                        Section.CALENDAR -> CalendarPicker(d, ui) {
                            change(d.copy(calendarId = it))
                            section = null
                        }
                        Section.GUESTS -> GuestsEditor(d, act)
                    }
                }
            }
        }
        ActionBar(
            buildList {
                add(
                    KitAction(
                        stringResource(if (ui.editing) R.string.ime_calendar_save_changes else R.string.ime_calendar_add_to_calendar),
                        Icons.Outlined.Check,
                        enabled = !ui.busy && !d.endsBeforeStart,
                        primary = true,
                    ) { act(CalendarAction.Save) },
                )
                add(KitAction(stringResource(R.string.ime_calendar_insert), Icons.Outlined.EditNote) { act(CalendarAction.InsertDraft) })
                if (!ui.editing) {
                    add(KitAction(stringResource(R.string.ime_calendar_open_app), Icons.Outlined.OpenInNew) { act(CalendarAction.DraftInApp) })
                }
            },
            Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

/** The editors the tray unfolds, one at a time. Text pieces have none: the keys type into them. */
private enum class Section { WHEN, REPEAT, REMINDERS, CALENDAR, GUESTS }

/**
 * The event as it stands, as the card it will be in the month: the colour of
 * its calendar down the edge, when, the title (or the hint for one), where,
 * and what it overlaps. A tap on it sends the keys to the title. Also what
 * the folded panel shows while the keys type, with [onTitle] null.
 */
@Composable
internal fun DraftPreview(d: EventDraft, ui: CalendarUi, animate: Boolean, onTitle: (() -> Unit)?) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val calendar = ui.calendars.firstOrNull { it.id == d.calendarId }
    val colour = calendar?.color?.takeIf { it != 0 }?.let { Color(it) } ?: kb.accent
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .popIn(0, animate)
            .pressScale(source, animate && onTitle != null)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
            .background(colour.copy(alpha = TINT_ALPHA))
            .chipBorder(kb, shape)
            .drawBehind { drawRect(colour, size = Size(STRIPE.toPx(), size.height)) }
            .then(
                if (onTitle != null) {
                    Modifier.clickable(interactionSource = source, indication = null) {
                        feedback()
                        onTitle()
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (d.allDay) {
                AllDayBadge(colour)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                rangeText(d.startMillis, d.endMillis, d.allDay, d.zone, ui.hour24),
                color = if (d.endsBeforeStart) kb.accent else kb.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            d.title.ifBlank { stringResource(R.string.ime_calendar_title_hint) },
            color = if (d.title.isBlank()) kb.secondaryText else kb.suggestionText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (d.location.isNotBlank()) {
            Text(d.location, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.conflicts.isNotEmpty()) {
            val first = ui.conflicts.first().title
            val more = ui.conflicts.size - 1
            Text(
                if (more == 0) stringResource(R.string.ime_calendar_overlaps, first)
                else pluralStringResource(R.plurals.ime_calendar_overlaps_more, more, first, more),
                color = kb.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The pieces of the event as chips that wrap: each names what it holds, or
 * its hint while empty. The open section's chip is drawn selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tray(
    d: EventDraft,
    ui: CalendarUi,
    hasCalendar: Boolean,
    section: Section?,
    animate: Boolean,
    onToggle: (Section) -> Unit,
    act: (CalendarAction) -> Unit,
) {
    val calendar = ui.calendars.firstOrNull { it.id == d.calendarId }
    var i = 0
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PieceChip(
            icon = Icons.Outlined.Schedule,
            label = whenText(d, ui),
            filled = true,
            active = section == Section.WHEN,
            index = i++,
            animate = animate,
        ) { onToggle(Section.WHEN) }
        PieceChip(
            icon = Icons.Outlined.LocationOn,
            label = d.location.ifBlank { stringResource(R.string.ime_calendar_location_hint) },
            filled = d.location.isNotBlank(),
            active = false,
            index = i++,
            animate = animate,
        ) { act(CalendarAction.Type(CalendarTextField.LOCATION)) }
        PieceChip(
            icon = Icons.Outlined.Group,
            label = if (d.guests.isEmpty()) {
                stringResource(R.string.ime_calendar_add_guest)
            } else {
                pluralStringResource(R.plurals.ime_calendar_guests_count, d.guests.size, d.guests.size)
            },
            filled = d.guests.isNotEmpty(),
            active = section == Section.GUESTS,
            index = i++,
            animate = animate,
        ) { onToggle(Section.GUESTS) }
        PieceChip(
            icon = Icons.Outlined.EventRepeat,
            label = recurrenceText(d.recurrence, d.startDate, ui.today),
            filled = d.recurrence != null,
            active = section == Section.REPEAT,
            index = i++,
            animate = animate,
        ) { onToggle(Section.REPEAT) }
        PieceChip(
            icon = Icons.Outlined.Notifications,
            label = if (d.reminders.isEmpty()) {
                stringResource(R.string.ime_calendar_reminder_none)
            } else {
                d.reminders.sorted().map { reminderText(it) }.joinToString(", ")
            },
            filled = d.reminders.isNotEmpty(),
            active = section == Section.REMINDERS,
            index = i++,
            animate = animate,
        ) { onToggle(Section.REMINDERS) }
        if (ui.calendars.size > 1 || hasCalendar) {
            PieceChip(
                icon = Icons.Outlined.CalendarMonth,
                label = calendar?.let { listOf(it.name, it.account).filter { s -> s.isNotBlank() }.distinct().joinToString(" · ") }.orEmpty(),
                filled = calendar != null,
                active = section == Section.CALENDAR,
                dot = calendar?.color,
                index = i++,
                animate = animate,
            ) { onToggle(Section.CALENDAR) }
        }
        PieceChip(
            icon = Icons.Outlined.Notes,
            label = d.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: stringResource(R.string.ime_calendar_notes_hint),
            filled = d.notes.isNotBlank(),
            active = false,
            index = i++,
            animate = animate,
        ) { act(CalendarAction.Type(CalendarTextField.NOTES)) }
        val busy = d.availability == Availability.BUSY
        PieceChip(
            icon = if (busy) Icons.Outlined.DoNotDisturbOn else Icons.Outlined.CheckCircle,
            label = stringResource(if (busy) R.string.ime_calendar_busy else R.string.ime_calendar_free),
            filled = true,
            active = false,
            index = i++,
            animate = animate,
        ) { act(CalendarAction.Draft(d.copy(availability = if (busy) Availability.FREE else Availability.BUSY))) }
        if (!d.allDay && d.zoneId != TimeZone.getDefault().id) {
            val zone = TimeZone.getTimeZone(d.zoneId)
            PieceChip(
                icon = Icons.Outlined.Public,
                label = stringResource(R.string.ime_calendar_zone_note, zone.getDisplayName(zone.inDaylightTime(java.util.Date(d.startMillis)), TimeZone.SHORT)),
                filled = true,
                active = false,
                index = i++,
                animate = animate,
                onClick = null,
            )
        }
    }
}

/** "Mon, 19 Oct · 9:00 AM – 10:00 AM", or the days of an all-day event. */
@Composable
private fun whenText(d: EventDraft, ui: CalendarUi): String {
    val start = CalendarText.date(d.startDate, ui.today)
    return when {
        d.allDay && d.endDate != d.startDate -> "$start – ${CalendarText.date(d.endDate, ui.today)}"
        d.allDay -> "$start · ${stringResource(R.string.ime_calendar_all_day_label)}"
        d.endDate != d.startDate -> "$start ${CalendarText.time(d.startMinute, ui.hour24)} – ${CalendarText.date(d.endDate, ui.today)} ${CalendarText.time(d.endMinute, ui.hour24)}"
        else -> "$start · ${CalendarText.time(d.startMinute, ui.hour24)} – ${CalendarText.time(d.endMinute, ui.hour24)}"
    }
}

/**
 * One piece of the event: its icon, its words, a colour dot where it has
 * one. Dim while unset, selected while its editor is open, squashed while
 * pressed. With [onClick] null it only informs.
 */
@Composable
private fun PieceChip(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    active: Boolean,
    index: Int,
    animate: Boolean,
    dot: Int? = null,
    onClick: (() -> Unit)?,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val ink = when {
        active -> kb.chipActiveText
        filled -> kb.chipText
        else -> kb.chipText.copy(alpha = kb.chipText.alpha * 0.6f)
    }
    Row(
        Modifier
            .popIn(index.coerceAtMost(MAX_STAGGER), animate)
            .pressScale(source, animate && onClick != null)
            .clockChip(selected = active)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null) {
                        feedback()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) kb.chipActiveText else if (filled) kb.accent else ink,
            modifier = Modifier.size(14.dp),
        )
        if (dot != null) ColorDot(dot, size = 8.dp)
        Text(
            label,
            color = ink,
            fontSize = 12.sp,
            fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = CHIP_MAX_TEXT),
        )
    }
}

// ---- Section editors ---------------------------------------------------------

/**
 * When: all day, the start and the end as date and time chips (each opening
 * its picker under the row), the length chips, and the time zone when it is
 * not the phone's.
 */
@Composable
private fun WhenEditor(d: EventDraft, ui: CalendarUi, act: (CalendarAction) -> Unit, change: (EventDraft) -> Unit) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RowIcon(Icons.Outlined.Schedule)
        Text(
            stringResource(R.string.ime_calendar_all_day_label),
            color = kb.suggestionText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        ClockSwitch(on = d.allDay, description = stringResource(R.string.ime_calendar_all_day_label)) {
            change(d.withAllDay(it, defaultMinute = 9 * 60, defaultLength = 60))
        }
    }
    WhenRow(
        label = stringResource(R.string.ime_calendar_starts),
        date = d.startDate,
        minute = if (d.allDay) null else d.startMinute,
        ui = ui,
        dateSheet = ComposeSheet.START_DATE,
        timeSheet = ComposeSheet.START_TIME,
        act = act,
    )
    AnimatedVisibility(ui.sheet == ComposeSheet.START_DATE) {
        MiniMonth(selected = d.startDate, today = ui.today) { change(d.withStart(it)) }
    }
    AnimatedVisibility(ui.sheet == ComposeSheet.START_TIME && !d.allDay) {
        Wheel(d.startMinute, ui.hour24) { change(d.withStart(d.startDate, it)) }
    }
    WhenRow(
        label = stringResource(R.string.ime_calendar_ends),
        date = d.endDate,
        minute = if (d.allDay) null else d.endMinute,
        ui = ui,
        dateSheet = ComposeSheet.END_DATE,
        timeSheet = ComposeSheet.END_TIME,
        act = act,
        wrong = d.endsBeforeStart,
    )
    AnimatedVisibility(ui.sheet == ComposeSheet.END_DATE) {
        MiniMonth(selected = d.endDate, today = ui.today, min = d.startDate) { change(d.withEnd(it)) }
    }
    AnimatedVisibility(ui.sheet == ComposeSheet.END_TIME && !d.allDay) {
        Wheel(d.endMinute, ui.hour24) { change(d.withEnd(d.endDate, it)) }
    }
    if (!d.allDay) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 26.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (m in LENGTHS) {
                ToolPanelChip(lengthText(m), selected = d.lengthMinutes == m) { change(d.withLength(m)) }
            }
        }
    }
    if (d.endsBeforeStart) {
        Text(
            stringResource(R.string.ime_calendar_error_end),
            color = kb.accent,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 26.dp),
        )
    }
}

/** The reminder choices, the set ones selected; a tap adds or removes one, up to the calendar's limit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersEditor(d: EventDraft, ui: CalendarUi, change: (EventDraft) -> Unit) {
    val max = ui.calendars.firstOrNull { it.id == d.calendarId }?.maxReminders ?: MAX_REMINDERS
    Row(verticalAlignment = Alignment.Top) {
        RowIcon(Icons.Outlined.Notifications)
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (m in REMINDERS) {
                val on = m in d.reminders
                ToolPanelChip(reminderText(m), selected = on, enabled = on || d.reminders.size < max) {
                    change(d.copy(reminders = if (on) d.reminders - m else (d.reminders + m).distinct()))
                }
            }
        }
    }
}

/** The calendars the event can go in, the chosen one selected. */
@Composable
private fun CalendarPicker(d: EventDraft, ui: CalendarUi, onPick: (Long) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.padding(start = 26.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in ui.calendars) {
            val on = c.id == d.calendarId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clockChip(selected = on, shape = kb.cardShape())
                    .clickable { onPick(c.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(c.color)
                Column(Modifier.weight(1f)) {
                    val fg = if (on) kb.chipActiveText else kb.chipText
                    Text(c.name.ifBlank { c.account }, color = fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.account.isNotBlank() && c.account != c.name) {
                        Text(c.account, color = fg.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.chipActiveText, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The guests as chips with a cross each, Add guest, and the note that the account may invite them. */
@Composable
private fun GuestsEditor(d: EventDraft, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.Top) {
        RowIcon(Icons.Outlined.Group)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GuestChips(d.guests, act)
            ToolPanelChip(stringResource(R.string.ime_calendar_add_guest), icon = null) {
                act(CalendarAction.Type(CalendarTextField.GUEST))
            }
            if (d.guests.isNotEmpty()) {
                Text(stringResource(R.string.ime_calendar_guests_invite_note), color = kb.secondaryText, fontSize = 11.sp)
            }
        }
    }
}

/** Guests as chips with a cross each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuestChips(guests: List<Guest>, act: (CalendarAction) -> Unit) {
    if (guests.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in guests) RemovableChip(g.label) { act(CalendarAction.RemoveGuest(g)) }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .clockChip()
            .clickable(onClick = onRemove)
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = kb.chipText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ime_calendar_remove_desc), tint = kb.chipText, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    val kb = LocalKbTheme.current
    Icon(
        icon,
        contentDescription = null,
        tint = kb.secondaryText,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp),
    )
}

/** "Starts  Mon, 19 Oct  9:00 AM": a date chip and a time chip, each opening its picker. */
@Composable
private fun WhenRow(
    label: String,
    date: EventDate,
    minute: Int?,
    ui: CalendarUi,
    dateSheet: ComposeSheet,
    timeSheet: ComposeSheet,
    act: (CalendarAction) -> Unit,
    wrong: Boolean = false,
) {
    val kb = LocalKbTheme.current
    Row(
        Modifier.padding(start = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = if (wrong) kb.accent else kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        ToolPanelChip(CalendarText.date(date, ui.today), selected = ui.sheet == dateSheet) { act(CalendarAction.Sheet(dateSheet)) }
        if (minute != null) {
            ToolPanelChip(CalendarText.time(minute, ui.hour24), selected = ui.sheet == timeSheet) { act(CalendarAction.Sheet(timeSheet)) }
        }
    }
}

@Composable
private fun Wheel(minute: Int, hour24: Boolean, onChange: (Int) -> Unit) {
    TimeWheel(
        hour = minute / 60,
        minute = minute % 60,
        hour24 = hour24,
        amLabel = remember { DateFormatSymbols.getInstance().amPmStrings[0] },
        pmLabel = remember { DateFormatSymbols.getInstance().amPmStrings[1] },
        hourDescription = stringResource(R.string.ime_clock_hour_desc),
        minuteDescription = stringResource(R.string.ime_clock_minute_desc),
        onChange = { h, m -> onChange(h * 60 + m) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A small month to pick a day from, opened on the month of [selected]. */
@Composable
private fun MiniMonth(selected: EventDate, today: EventDate, min: EventDate? = null, onPick: (EventDate) -> Unit) {
    val kb = LocalKbTheme.current
    var shown by remember(selected.year, selected.month) { mutableStateOf(EventDate(selected.year, selected.month, 1)) }
    val initials = stringArrayResource(R.array.ime_calendar_weekday_initials)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_calendar_previous_month_desc), size = 28.dp) {
                shown = shown.plusMonths(-1)
            }
            Text(
                CalendarText.month(shown.year, shown.month),
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_calendar_next_month_desc), size = 28.dp) {
                shown = shown.plusMonths(1)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            initials.forEach {
                Text(it, color = kb.secondaryText, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val firstDow = shown.dayOfWeek
        val days = shown.monthLength
        val weeks = (firstDow + days + 6) / 7
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val day = week * 7 + dow - firstDow + 1
                    if (day < 1 || day > days) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val date = EventDate(shown.year, shown.month, day)
                        val isSelected = date == selected
                        val enabled = min == null || date >= min
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1.6f)
                                .padding(1.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) kb.accent else Color.Transparent)
                                .clickable(enabled = enabled) { onPick(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.toString(),
                                color = when {
                                    isSelected -> maxContrastOn(kb.accent)
                                    !enabled -> kb.secondaryText.copy(alpha = 0.4f)
                                    date == today -> kb.accent
                                    else -> kb.suggestionText
                                },
                                fontSize = 12.sp,
                                fontWeight = if (isSelected || date == today) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How the event repeats: the common rules as chips, and under More options every knob. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatEditor(d: EventDraft, today: EventDate, onChange: (Recurrence?) -> Unit) {
    val kb = LocalKbTheme.current
    val start = d.startDate
    val current = d.recurrence
    var custom by remember { mutableStateOf(current != null && current !in presets(start)) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolPanelChip(stringResource(R.string.ime_calendar_repeat_none), selected = current == null) { onChange(null) }
            for (p in presets(start)) {
                ToolPanelChip(recurrenceText(p, start, today), selected = current == p && !custom) {
                    custom = false
                    onChange(p)
                }
            }
            if (current?.isCustom == true) {
                ToolPanelChip(stringResource(R.string.ime_calendar_repeat_custom), selected = true) {}
            }
            ToolPanelChip(stringResource(R.string.ime_calendar_more_repeats), selected = custom) {
                custom = !custom
                if (custom && (current == null || current.isCustom)) onChange(Recurrence.weekly(start))
            }
        }
        val r = current
        if (custom && r != null && !r.isCustom) {
            // Every N units.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ime_calendar_repeat_every), color = kb.secondaryText, fontSize = 12.sp)
                Stepper(r.interval, 1..99) { onChange(r.copy(interval = it)) }
                for (f in Frequency.entries) {
                    ToolPanelChip(unitText(f, r.interval), selected = r.frequency == f) {
                        onChange(
                            when (f) {
                                Frequency.WEEKLY -> r.copy(frequency = f, weekdays = if (r.weekdays == 0) Recurrence.bit(start.dayOfWeek) else r.weekdays, nth = null)
                                Frequency.MONTHLY -> r.copy(frequency = f, weekdays = 0)
                                else -> r.copy(frequency = f, weekdays = 0, nth = null)
                            },
                        )
                    }
                }
            }
            if (r.frequency == Frequency.WEEKLY) {
                val letters = stringArrayResource(R.array.ime_calendar_weekday_initials)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until 7) {
                        val on = Recurrence.has(r.weekdays, i)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clockChip(selected = on, shape = CircleShape)
                                .clickable {
                                    val next = r.weekdays xor Recurrence.bit(i)
                                    if (next != 0) onChange(r.copy(weekdays = next))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(letters[i], color = if (on) kb.chipActiveText else kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (r.frequency == Frequency.MONTHLY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolPanelChip(pluralStringResource(R.plurals.ime_calendar_repeat_monthly_day, 1, 1, start.day), selected = r.nth == null) {
                        onChange(r.copy(nth = null))
                    }
                    val nth = NthWeekday.of(start)
                    if (nth.ordinal <= 4) {
                        ToolPanelChip(pluralStringResource(R.plurals.ime_calendar_repeat_monthly_nth, 1, 1, nthWeekdayText(nth)), selected = r.nth == nth) {
                            onChange(r.copy(nth = nth))
                        }
                    }
                    NthWeekday.lastOf(start)?.let { last ->
                        ToolPanelChip(pluralStringResource(R.plurals.ime_calendar_repeat_monthly_nth, 1, 1, nthWeekdayText(last)), selected = r.nth == last) {
                            onChange(r.copy(nth = last))
                        }
                    }
                }
            }
            // How it ends.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ime_calendar_repeat_ends), color = kb.secondaryText, fontSize = 12.sp)
                ToolPanelChip(stringResource(R.string.ime_calendar_repeat_never), selected = r.end == RepeatEnd.Never) {
                    onChange(r.copy(end = RepeatEnd.Never))
                }
                val until = (r.end as? RepeatEnd.Until)?.date ?: start.plusMonths(3)
                ToolPanelChip(stringResource(R.string.ime_calendar_repeat_on_date, CalendarText.date(until, today)), selected = r.end is RepeatEnd.Until) {
                    onChange(r.copy(end = RepeatEnd.Until(until)))
                }
                val count = (r.end as? RepeatEnd.Count)?.times ?: 10
                ToolPanelChip(pluralStringResource(R.plurals.ime_calendar_repeat_after, count, count), selected = r.end is RepeatEnd.Count) {
                    onChange(r.copy(end = RepeatEnd.Count(count)))
                }
            }
            when (val end = r.end) {
                is RepeatEnd.Until -> MiniMonth(selected = end.date, today = today, min = start) { onChange(r.copy(end = RepeatEnd.Until(it))) }
                is RepeatEnd.Count -> Stepper(end.times, 1..999) { onChange(r.copy(end = RepeatEnd.Count(it))) }
                RepeatEnd.Never -> Unit
            }
        }
    }
}

/** The common rules for an event starting on [start]. */
private fun presets(start: EventDate): List<Recurrence> = buildList {
    add(Recurrence.daily())
    add(Recurrence.weekly(start))
    add(Recurrence.workdays())
    add(Recurrence.monthly())
    if (NthWeekday.of(start).ordinal <= 4) add(Recurrence.monthlyOnWeekday(start))
    if (NthWeekday.lastOf(start) != null) add(Recurrence.monthlyOnWeekday(start, last = true))
    add(Recurrence.yearly())
}.distinct()

@Composable
private fun unitText(f: Frequency, n: Int): String = when (f) {
    Frequency.DAILY -> pluralStringResource(R.plurals.ime_calendar_unit_days, n)
    Frequency.WEEKLY -> pluralStringResource(R.plurals.ime_calendar_unit_weeks, n)
    Frequency.MONTHLY -> pluralStringResource(R.plurals.ime_calendar_unit_months, n)
    Frequency.YEARLY -> pluralStringResource(R.plurals.ime_calendar_unit_years, n)
}

/** A number with − and + either side. */
@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", value > range.first) { onChange((value - 1).coerceIn(range)) }
        Text(
            value.toString(),
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        StepButton("+", value < range.last) { onChange((value + 1).coerceIn(range)) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Box(
        Modifier
            .size(28.dp)
            .clockChip(shape = CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) kb.chipText else kb.chipText.copy(alpha = 0.4f), fontSize = 16.sp)
    }
}

private val LENGTHS = listOf(15, 30, 45, 60, 90, 120, 180)
private val REMINDERS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)
private const val MAX_REMINDERS = 5

/** The most a piece's words take before they are cut: a long place or note stays one chip. */
private val CHIP_MAX_TEXT = 200.dp

package com.wasimaster.wmkeyboard.ime.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Today
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EventRepeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.AltCalendars
import com.wasimaster.wmkeyboard.core.tools.CalendarSystems
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.StoredEvent
import com.wasimaster.wmkeyboard.core.tools.calendar.EditScope
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.core.tools.calendar.RRule
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarAction
import com.wasimaster.wmkeyboard.ime.calendar.CalendarCallbacks
import com.wasimaster.wmkeyboard.ime.calendar.CalendarError
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextEntry
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUi
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUndo
import com.wasimaster.wmkeyboard.ime.calendar.CalendarView
import com.wasimaster.wmkeyboard.ime.calendar.ScopePrompt
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.blockTouches
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.clock.clockChip
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.SegmentedPill
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringBouncy
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringGentle
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import com.wasimaster.wmkeyboard.ime.ui.swipeTabs
import java.text.DateFormatSymbols
import java.util.TimeZone
import kotlinx.coroutines.launch

/**
 * The Calendar tool in the revamped panel language: the month as a grid with
 * an accent disc that springs between days and the day's events as a
 * carousel of tinted cards under it, the same month as an agenda down a day
 * rail, an event's own card with the ways to act on it along the bottom, and
 * the composer as a tray of chips that fill in. The state is
 * [com.wasimaster.wmkeyboard.ime.calendar.CalendarController]'s; this file
 * only draws it. Month or agenda is the panel's own choice, not the
 * controller's: both are the controller's `MONTH` view.
 *
 * While the keys type into the composer the panel folds down over them, as
 * the Clock's does, and the header becomes the text box.
 */
@Composable
internal fun CalendarPanel(
    state: KeyboardUiState,
    callbacks: CalendarCallbacks,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.calendarText
    val reduce = state.settings.reduceMotion
    val animate = kb.motion.any && !reduce
    val local = remember { CalendarPanelLocal() }
    val face = when {
        ui.view == CalendarView.COMPOSE -> Face.COMPOSE
        ui.view == CalendarView.DETAIL -> Face.DETAIL
        local.agenda -> Face.AGENDA
        else -> Face.MONTH
    }
    // The month and agenda faces publish the ring's targets; anywhere else
    // they are published empty, so a count left from the grid never steers it.
    if (typing != null || face == Face.COMPOSE || face == Face.DETAIL) {
        PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.ACTIONS, count = 0, columns = 3) {}
        PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.RESULTS, count = 0, columns = 7) {}
    }
    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        // Grown a little, as the clock is: the month grid, the day's line and
        // its cards do not fit the key rows' height at cells one can tap.
        extraHeight = CalendarExtraHeight,
        compact = typing != null,
        compactHeight = 150.dp,
        headerActions = {
            when {
                typing != null -> TypingHeader(typing, act)
                face == Face.COMPOSE -> ComposeHeader(ui, act)
                face == Face.DETAIL -> DetailHeader(ui, act)
                else -> MonthHeader(state, ui, local, act)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (typing != null) {
                TypingBody(ui, typing, act)
            } else {
                MotionCrossfade(
                    target = face,
                    animate = kb.motion.panels && !reduce,
                    modifier = Modifier.fillMaxSize(),
                    label = "calendar-face",
                ) { shown ->
                    Box(Modifier.fillMaxSize().blockTouches(shown != face)) {
                        when (shown) {
                            Face.MONTH -> MonthFace(state, ui, animate, act)
                            Face.AGENDA -> AgendaFace(state, ui, animate, act)
                            Face.DETAIL -> ui.detail?.let { EventSheet(it, ui, animate, act) }
                            Face.COMPOSE -> ui.draft?.let { CalendarComposer(it, ui, act) }
                        }
                    }
                }
                if (local.monthPicker && (face == Face.MONTH || face == Face.AGENDA)) {
                    MonthPicker(ui, animate, onPick = { act(CalendarAction.Month(it)) }) { local.monthPicker = false }
                }
            }
            ui.scopePrompt?.let {
                ScopeSheet(it, animate, act, Modifier.align(Alignment.BottomCenter))
            }
            val undo = ui.undo
            PanelUndoBar(
                text = undo?.let { undoText(it) }.orEmpty(),
                visible = undo != null && typing == null,
                key = undo,
                reduceMotion = reduce,
                onUndo = { act(CalendarAction.Undo) },
                onDismiss = { act(CalendarAction.UndoDismiss) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** What the panel shows: the controller's view, split where the panel adds a face of its own. */
private enum class Face { MONTH, AGENDA, DETAIL, COMPOSE }

/** The panel's own state, which the controller has no reason to carry. */
private class CalendarPanelLocal {
    /** The month shown as a list down a day rail rather than a grid. */
    var agenda by mutableStateOf(false)

    /** The month picker dropped from the header's title. */
    var monthPicker by mutableStateOf(false)
}

@Composable
private fun undoText(undo: CalendarUndo): String = stringResource(
    when (undo.kind) {
        CalendarUndo.Kind.ADDED -> R.string.ime_calendar_undo_added
        CalendarUndo.Kind.CHANGED -> R.string.ime_calendar_undo_changed
        CalendarUndo.Kind.DELETED -> R.string.ime_calendar_undo_deleted
    },
)

@Composable
internal fun errorText(error: CalendarError): String = stringResource(
    when (error) {
        CalendarError.END_BEFORE_START -> R.string.ime_calendar_error_end
        CalendarError.NO_CALENDAR -> R.string.ime_calendar_error_no_calendar
        CalendarError.NO_ACCESS -> R.string.ime_calendar_error_no_access
        CalendarError.WRITE_FAILED -> R.string.ime_calendar_error_failed
        CalendarError.NO_APP -> R.string.ime_calendar_error_no_app
    },
)

// ---- Headers ----------------------------------------------------------------

/**
 * The month and year as the title, which drops a month picker; Today as a
 * chip; Month | Agenda on one pill; a new event and the settings as round
 * buttons. The ring's three ACTIONS slots are previous month, Today and next
 * month, as they were: the first and last light the title, since the arrows
 * are now a swipe across the grid.
 */
@Composable
private fun RowScope.MonthHeader(state: KeyboardUiState, ui: CalendarUi, local: CalendarPanelLocal, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val label = remember(ui.shownYear, ui.shownMonth) { CalendarText.month(ui.shownYear, ui.shownMonth) }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val shape = kb.chipShape()
    Row(
        Modifier
            .weight(1f)
            .padding(start = 2.dp)
            .clip(shape)
            .focusRing(focusedAction == 0 || focusedAction == 2, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                feedback()
                local.monthPicker = !local.monthPicker
            }
            .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = kb.modifierKeyText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Outlined.ArrowDropDown,
            contentDescription = stringResource(R.string.ime_calendar_pick_month_desc),
            tint = kb.toolbarIcon,
            modifier = Modifier
                .size(18.dp)
                .rotate(if (local.monthPicker) 180f else 0f),
        )
    }
    val onToday = ui.selected == ui.today && ui.shownYear == ui.today.year && ui.shownMonth == ui.today.month
    // A glyph, not a word: the header has five things in it and the month's
    // name was the one being cut short.
    RoundButton(Icons.Outlined.Today, stringResource(R.string.ime_calendar_today_label), size = 30.dp, primary = onToday, focused = focusedAction == 1) {
        act(CalendarAction.Today)
    }
    Spacer(Modifier.width(6.dp))
    SegmentedPill(
        labels = listOf(stringResource(R.string.ime_calendar_view_month), stringResource(R.string.ime_calendar_view_agenda)),
        selected = if (local.agenda) 1 else 0,
        onSelect = { local.agenda = it == 1 },
        segmentWidth = VIEW_SEGMENT,
        height = 28.dp,
    )
    Spacer(Modifier.width(6.dp))
    RoundButton(Icons.Outlined.Add, stringResource(R.string.ime_calendar_new_event), size = 30.dp, primary = true) {
        act(CalendarAction.NewEvent())
    }
    Spacer(Modifier.width(4.dp))
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_calendar_settings_desc),
        active = false,
    ) { act(CalendarAction.OpenSettings) }
}

/** Back to the month, and the calendar the open event is in. */
@Composable
private fun RowScope.DetailHeader(ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ime_calendar_back_desc), size = 30.dp) {
        act(CalendarAction.CloseDetail)
    }
    val event = ui.detail
    if (event != null && event.calendarName.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        ColorDot(event.color)
        Text(
            event.calendarName,
            color = kb.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.weight(1f))
}

/** What the composer is for, and Cancel; Save lives on the bar along the bottom. */
@Composable
private fun RowScope.ComposeHeader(ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    Text(
        stringResource(if (ui.editing) R.string.ime_calendar_edit_event else R.string.ime_calendar_new_event),
        color = kb.modifierKeyText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp),
    )
    PillButton(stringResource(R.string.ime_calendar_cancel), height = 30.dp) { act(CalendarAction.CancelCompose) }
    Spacer(Modifier.width(4.dp))
}

/** While the keys type into the composer, the header is the text box and a Done. */
@Composable
private fun RowScope.TypingHeader(entry: CalendarTextEntry, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val placeholder = stringResource(
        when (entry.field) {
            CalendarTextField.TITLE -> R.string.ime_calendar_title_hint
            CalendarTextField.LOCATION -> R.string.ime_calendar_location_hint
            CalendarTextField.NOTES -> R.string.ime_calendar_notes_hint
            CalendarTextField.GUEST -> R.string.ime_calendar_guest_hint
        },
    )
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 6.dp)
            .height(32.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(fieldIcon(entry.field), contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = entry.text,
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    PillButton(stringResource(R.string.ime_calendar_done), primary = true, height = 30.dp) { act(CalendarAction.TextDone) }
    Spacer(Modifier.width(4.dp))
}

private fun fieldIcon(field: CalendarTextField): ImageVector = when (field) {
    CalendarTextField.TITLE -> Icons.Outlined.Event
    CalendarTextField.LOCATION -> Icons.Outlined.LocationOn
    CalendarTextField.NOTES -> Icons.Outlined.Notes
    CalendarTextField.GUEST -> Icons.Outlined.Group
}

/**
 * What the folded panel shows while the keys type: contacts to pick as a
 * guest, or the draft's preview so the user keeps their bearings.
 */
@Composable
private fun TypingBody(ui: CalendarUi, entry: CalendarTextEntry, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val draft = ui.draft ?: return
    if (entry.field == CalendarTextField.GUEST) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(ui.guestSuggestions, key = { it.email }) { g ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clockChip(shape = kb.cardShape())
                        .clickable { act(CalendarAction.AddGuest(g)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.label, color = kb.suggestionText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (g.name.isNotBlank()) {
                            Text(g.email, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                }
            }
            if (ui.guestSuggestions.isEmpty()) {
                item {
                    GuestChips(draft.guests, act)
                    if (!ui.contactsAllowed) {
                        Text(
                            stringResource(R.string.ime_calendar_contacts_action),
                            color = kb.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable { act(CalendarAction.AskContacts) },
                        )
                    }
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        DraftPreview(draft, ui, animate = false, onTitle = null)
    }
}

// ---- Month ----------------------------------------------------------------

/**
 * The month: the weekday initials, the grid with a disc on the selected day
 * that springs to the next one tapped, a dot under every day with something
 * on, today outlined, and under it the selected day in words and its events
 * as a carousel of cards. A swipe across the grid slides to the month
 * beside. A press selects a day; a long press starts an event on it.
 */
@Composable
private fun MonthFace(state: KeyboardUiState, ui: CalendarUi, animate: Boolean, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shownYear = ui.shownYear
    val shownMonth = ui.shownMonth
    val tool = state.settings.calendarTool
    val altCalendars = listOf(tool.altOne, tool.altTwo).filter { it != AltCalendar.NONE }.distinct()
    val cellCalendar = altCalendars.firstOrNull { it.hasDayLabel }
    val hijriAdjust = tool.hijriAdjustDays
    val daysInMonth = CalendarSystems.gregorianMonthLength(shownYear, shownMonth)
    val spanLabel = remember(shownYear, shownMonth, altCalendars, hijriAdjust) {
        altCalendars
            .map { AltCalendars.monthSpan(it, shownYear, shownMonth, hijriAdjust) }
            .filter { it.isNotEmpty() }
            .joinToString("  ·  ")
    }

    // Ring regions: three fixed ACTIONS slots (Today no-ops while already on
    // today, rather than renumbering next); the day grid is seven columns, so
    // Up/Down walk weeks the way the eye does.
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.ACTIONS, count = 3, columns = 3) { index ->
        when (index) {
            0 -> act(CalendarAction.Month(-1))
            1 -> act(CalendarAction.Today)
            2 -> act(CalendarAction.Month(1))
        }
    }
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.RESULTS, count = daysInMonth, columns = 7) { index ->
        val day = index + 1
        if (day in 1..daysInMonth) act(CalendarAction.SelectDay(EventDate(shownYear, shownMonth, day)))
    }
    val focusedDay = state.focusedIndex(FocusRegion.RESULTS)?.plus(1)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        if (ui.resumable && ui.draft != null) {
            Banner(
                stringResource(R.string.ime_calendar_resume_draft, ui.draft.title.ifBlank { stringResource(R.string.ime_calendar_untitled) }),
                stringResource(R.string.ime_calendar_resume_action),
            ) { act(CalendarAction.ResumeDraft) }
            Spacer(Modifier.height(4.dp))
        }
        if (spanLabel.isNotEmpty()) {
            Text(
                spanLabel,
                color = kb.toolbarIcon,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        WeekdayRow(tool.weekend.days)
        MonthGrid(
            ui = ui,
            cellCalendar = cellCalendar,
            hijriAdjust = hijriAdjust,
            focusedDay = focusedDay,
            animate = animate,
            act = act,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .swipeTabs(index = 1, count = 3) { act(CalendarAction.Month(if (it == 0) -1 else 1)) },
        )
        DaySummary(ui, altCalendars, hijriAdjust)
        DayCards(ui, animate, act, Modifier.fillMaxWidth())
    }
}

/** The weekday initials, the weekend's in the accent. */
@Composable
private fun WeekdayRow(weekend: Set<Int>) {
    val kb = LocalKbTheme.current
    val initials = stringArrayResource(R.array.ime_calendar_weekday_initials)
    Row(Modifier.fillMaxWidth()) {
        initials.forEachIndexed { index, initial ->
            Text(
                initial,
                color = if (index in weekend) kb.accent else kb.toolbarIcon,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The grid for the shown month, sliding sideways to the next when the month
 * changes: forward slides in from the right, back from the left. Drawn
 * straight with motion off.
 */
@Composable
private fun MonthGrid(
    ui: CalendarUi,
    cellCalendar: AltCalendar?,
    hijriAdjust: Int,
    focusedDay: Int?,
    animate: Boolean,
    act: (CalendarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val monthIndex = ui.shownYear * 12 + (ui.shownMonth - 1)
    if (!animate) {
        Box(modifier) { MonthCells(ui.shownYear, ui.shownMonth, ui, cellCalendar, hijriAdjust, focusedDay, animate, act) }
        return
    }
    AnimatedContent(
        targetState = monthIndex,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState > initialState
            (slideInHorizontally(tween(MONTH_MS)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(MONTH_MS))) togetherWith
                (slideOutHorizontally(tween(MONTH_MS)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(MONTH_MS / 2)))
        },
        label = "calendar-month",
    ) { index ->
        // From the index, not the state: the month on its way out keeps its own days.
        MonthCells(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1, ui, cellCalendar, hijriAdjust, focusedDay, animate, act)
    }
}

@Composable
private fun MonthCells(
    year: Int,
    month: Int,
    ui: CalendarUi,
    cellCalendar: AltCalendar?,
    hijriAdjust: Int,
    focusedDay: Int?,
    animate: Boolean,
    act: (CalendarAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val today = ui.today
    val selected = ui.selected
    val firstDow = CalendarSystems.dayOfWeek(CalendarSystems.gregorianToJdn(year, month, 1))
    val daysInMonth = CalendarSystems.gregorianMonthLength(year, month)
    val weeks = (firstDow + daysInMonth + 6) / 7
    val current = year == ui.shownYear && month == ui.shownMonth
    val selectedHere = current && selected.year == year && selected.month == month
    // The selected day's own events colour its dots; every other busy day gets the accent.
    val selectedDots = remember(ui.dayEvents, kb.accent) {
        ui.dayEvents.orEmpty().take(MAX_DOTS).map { if (it.color != 0) Color(it.color) else kb.accent }.ifEmpty { listOf(kb.accent) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellW = maxWidth / 7
        val cellH = maxHeight / weeks
        val index = if (selectedHere) firstDow + selected.day - 1 else -1
        SelectedDisc(cellW, cellH, col = index % 7, row = index / 7, visible = index >= 0, animate = animate)
        Column(Modifier.fillMaxSize()) {
            for (week in 0 until weeks) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    for (dow in 0 until 7) {
                        val day = week * 7 + dow - firstDow + 1
                        if (day < 1 || day > daysInMonth) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val isSelected = selectedHere && selected.day == day
                            val busy = current && day in ui.busyDays
                            DayCell(
                                day = day,
                                isToday = today.year == year && today.month == month && today.day == day,
                                isSelected = isSelected,
                                dots = if (!busy) emptyList() else if (isSelected) selectedDots else listOf(kb.accent),
                                focused = current && day == focusedDay,
                                altDay = cellCalendar?.let { AltCalendars.dayLabel(it, year, month, day, hijriAdjust) },
                                animate = animate,
                                onClick = { act(CalendarAction.SelectDay(EventDate(year, month, day))) },
                                onLongClick = { act(CalendarAction.NewEvent(EventDate(year, month, day))) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The accent disc under the selected day. It springs from the day it was on
 * to the one tapped, grows in when the selection enters the shown month and
 * shrinks away when it leaves; with motion off it is simply where it should
 * be. Its position and size are read in the draw alone, so a frame of the
 * spring redraws the disc and not the grid.
 */
@Composable
private fun SelectedDisc(cellW: Dp, cellH: Dp, col: Int, row: Int, visible: Boolean, animate: Boolean) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current
    val target = with(density) {
        if (visible) Offset((cellW * col + cellW / 2).toPx(), (cellH * row + cellH / 2).toPx()) else Offset.Unspecified
    }
    val pos = remember { Animatable(if (target.isSpecified) target else Offset.Zero, Offset.VectorConverter) }
    val appear = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(target, visible, animate) {
        if (!animate) {
            if (target.isSpecified) pos.snapTo(target)
            appear.snapTo(if (visible) 1f else 0f)
            return@LaunchedEffect
        }
        if (!visible) {
            appear.animateTo(0f, kitSpringGentle())
            return@LaunchedEffect
        }
        // Arriving from off the month: grow in place rather than fly from a stale spot.
        if (appear.value < 0.05f) pos.snapTo(target)
        launch { appear.animateTo(1f, kitSpringBouncy()) }
        pos.animateTo(target, kitSpringGentle())
    }
    val radius = ((minOf(cellW, cellH) - DISC_INSET) / 2).coerceAtLeast(8.dp)
    val accent = kb.accent
    Canvas(Modifier.fillMaxSize()) {
        val a = appear.value.coerceIn(0f, 1f)
        if (a <= 0f) return@Canvas
        drawCircle(accent.copy(alpha = accent.alpha * a), radius.toPx() * (0.85f + 0.15f * a), pos.value)
    }
}

/**
 * One day: its number (with the alternate calendar's beside it), today as a
 * thin accent ring, the dots of what is on under it. The selected day's disc
 * is drawn by [SelectedDisc] behind the whole grid, so the cell only changes
 * its ink.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    dots: List<Color>,
    focused: Boolean,
    altDay: String?,
    animate: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val accent = kb.accent
    val inset = DISC_INSET
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .pressScale(source, animate)
            .focusRing(focused, CircleShape)
            .drawBehind {
                if (isToday && !isSelected) {
                    val r = (size.minDimension - inset.toPx()) / 2f
                    drawCircle(accent, r, center, style = Stroke(1.5.dp.toPx()))
                }
            }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = {
                    feedback()
                    onClick()
                },
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val ink = when {
            isSelected -> maxContrastOn(accent)
            isToday -> accent
            else -> kb.modifierKeyText
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day.toString(),
                color = ink,
                fontSize = 11.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            if (altDay != null) {
                Text(
                    altDay,
                    color = if (isSelected) ink.copy(alpha = 0.75f) else kb.toolbarIcon,
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        if (dots.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (c in dots) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) ink else c),
                    )
                }
            }
        }
    }
}

/** The selected day in words: its date and how far off on one line, the alternate calendars under. */
@Composable
private fun DaySummary(ui: CalendarUi, altCalendars: List<AltCalendar>, hijriAdjust: Int) {
    val kb = LocalKbTheme.current
    val selected = ui.selected
    val dateText = remember(selected) { CalendarText.longDate(selected) }
    val relativeText = relativeDayText(ui.today, selected)
    val altText = remember(selected, altCalendars, hijriAdjust) {
        altCalendars
            .map { AltCalendars.fullDate(it, selected.year, selected.month, selected.day, hijriAdjust) }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            "$dateText · $relativeText",
            color = kb.modifierKeyText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (altText.isNotEmpty()) {
            Text(altText, color = kb.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Today", "Tomorrow", "in 3 days", "2 days ago". */
@Composable
private fun relativeDayText(today: EventDate, date: EventDate): String {
    val days = today.daysUntil(date).toInt()
    return when {
        days == 0 -> stringResource(R.string.ime_calendar_today_label)
        days == 1 -> stringResource(R.string.ime_calendar_tomorrow_label)
        days == -1 -> stringResource(R.string.ime_calendar_yesterday_label)
        days > 0 -> pluralStringResource(R.plurals.ime_calendar_in_days_info, days, days)
        else -> pluralStringResource(R.plurals.ime_calendar_days_ago_info, -days, -days)
    }
}

/**
 * The selected day's events as a carousel of tinted cards, popping in one
 * after another whenever the day changes; the prompt for calendar access
 * until it is granted; a quiet line and a way to add one when the day is
 * clear. A press opens the event.
 */
@Composable
private fun DayCards(ui: CalendarUi, animate: Boolean, act: (CalendarAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Box(modifier.height(CARD_ROW_HEIGHT), contentAlignment = Alignment.CenterStart) {
        if (!ui.canRead) {
            Banner(stringResource(R.string.ime_calendar_permission_info), stringResource(R.string.ime_calendar_allow_action)) {
                act(CalendarAction.AskRead)
            }
            return@Box
        }
        val list = ui.dayEvents
        when {
            list == null -> Unit
            list.isEmpty() -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ime_calendar_no_events_empty),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                PillButton(stringResource(R.string.ime_calendar_new_event), icon = Icons.Outlined.Add, height = 28.dp) {
                    act(CalendarAction.NewEvent())
                }
            }
            else -> LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                // No key: EVENT_ID repeats across the instances of a recurring
                // event, and duplicate keys are a crash rather than a glitch.
                itemsIndexed(list) { i, event ->
                    EventCard(
                        event = event,
                        hour24 = ui.hour24,
                        animate = animate,
                        modifier = Modifier
                            .width(CARD_WIDTH)
                            .popIn(i.coerceAtMost(MAX_STAGGER), animate, key = ui.selected),
                    ) { act(CalendarAction.OpenEvent(event)) }
                }
            }
        }
    }
}

/**
 * One event as a card tinted with its calendar's colour: the time (or an
 * all-day badge) with the repeat mark, the title, and the place. Squashes
 * while pressed.
 */
@Composable
internal fun EventCard(
    event: DeviceCalendarEvent,
    hour24: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val colour = if (event.color != 0) Color(event.color) else kb.accent
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .height(CARD_HEIGHT)
            .pressScale(source, animate)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
            .background(colour.copy(alpha = TINT_ALPHA))
            .chipBorder(kb, shape)
            .drawBehind { drawRect(colour, size = Size(STRIPE.toPx(), size.height)) }
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (event.allDay) {
                AllDayBadge(colour)
            } else {
                Text(
                    eventTimeText(event, hour24),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (event.recurring) {
                Icon(Icons.Outlined.EventRepeat, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(12.dp))
            }
        }
        Text(
            event.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
            color = kb.suggestionText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val place = event.location?.takeIf { it.isNotBlank() }
        if (place != null) {
            Text(place, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "9:00 – 10:00 AM", the start and end of a timed event on the device's clock. */
private fun eventTimeText(event: DeviceCalendarEvent, hour24: Boolean): String {
    val zone = TimeZone.getDefault()
    val start = CalendarText.time(EventTimes.minuteOfDay(event.begin, zone), hour24)
    if (event.end <= event.begin) return start
    val end = CalendarText.time(EventTimes.minuteOfDay(event.end, zone), hour24)
    return "$start – $end"
}

/** A small "All day" pill in the event's colour. */
@Composable
internal fun AllDayBadge(colour: Color) {
    val kb = LocalKbTheme.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colour.copy(alpha = 0.28f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            stringResource(R.string.ime_calendar_all_day_label),
            color = kb.suggestionText,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
        )
    }
}

/** A calendar's colour as a small dot, the accent where it has none. */
@Composable
internal fun ColorDot(color: Int, size: Dp = 10.dp) {
    val kb = LocalKbTheme.current
    Box(
        Modifier
            .padding(end = 8.dp)
            .size(size)
            .clip(CircleShape)
            .background(if (color != 0) Color(color) else kb.accent),
    )
}

// ---- Month picker -----------------------------------------------------------

/**
 * Dropped from the header's title: the year with arrows either side and the
 * twelve months as chips, the shown one filled and this month in the accent.
 * A pick moves the controller by the difference in months, so the busy days
 * reload as they do for a swipe. A tap outside puts it away.
 */
@Composable
private fun MonthPicker(ui: CalendarUi, animate: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    var year by remember(ui.shownYear) { mutableIntStateOf(ui.shownYear) }
    val names = remember { DateFormatSymbols.getInstance().shortMonths.take(12) }
    val shape = kb.cardShape()
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
                .popIn(0, animate)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CARDS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                // Taps on the card are the card's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_calendar_previous_year_desc), size = 28.dp) { year-- }
                Text(
                    year.toString(),
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_calendar_next_year_desc), size = 28.dp) { year++ }
            }
            for (row in 0 until 3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 4) {
                        val m = row * 4 + c + 1
                        val on = year == ui.shownYear && m == ui.shownMonth
                        val now = year == ui.today.year && m == ui.today.month
                        Box(
                            Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clockChip(selected = on)
                                .clickable {
                                    feedback()
                                    onPick((year * 12 + m) - (ui.shownYear * 12 + ui.shownMonth))
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                names[m - 1],
                                color = when {
                                    on -> kb.chipActiveText
                                    now -> kb.accent
                                    else -> kb.chipText
                                },
                                fontSize = 12.sp,
                                fontWeight = if (on || now) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Agenda -----------------------------------------------------------------

/**
 * The shown month as a list: a rail down the left with a dot and a name for
 * each day that has something on (Today, Tomorrow, or the date), the day's
 * events as cards to the right. The controller loads one day's events at a
 * time, so the open day's cards are live, days visited before keep theirs,
 * and a day not yet opened shows a quiet card that opens it. A swipe across
 * moves a month, as the grid does.
 */
@Composable
private fun AgendaFace(state: KeyboardUiState, ui: CalendarUi, animate: Boolean, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val year = ui.shownYear
    val month = ui.shownMonth
    val today = ui.today
    val list = ui.dayEvents
    val seen = remember { mutableStateMapOf<EventDate, List<DeviceCalendarEvent>>() }
    LaunchedEffect(ui.selected, list) { if (list != null) seen[ui.selected] = list }
    val days = remember(ui.busyDays, ui.selected, year, month, today) {
        buildList {
            ui.busyDays.forEach { add(EventDate(year, month, it)) }
            if (ui.selected.year == year && ui.selected.month == month) add(ui.selected)
            if (today.year == year && today.month == month) add(today)
        }.distinct().sorted()
    }
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.ACTIONS, count = 3, columns = 3) { index ->
        when (index) {
            0 -> act(CalendarAction.Month(-1))
            1 -> act(CalendarAction.Today)
            2 -> act(CalendarAction.Month(1))
        }
    }
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.RESULTS, count = days.size, columns = 1) { index ->
        days.getOrNull(index)?.let { act(CalendarAction.SelectDay(it)) }
    }
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)

    Box(
        Modifier
            .fillMaxSize()
            .swipeTabs(index = 1, count = 3) { act(CalendarAction.Month(if (it == 0) -1 else 1)) },
    ) {
        when {
            !ui.canRead -> EmptyState(
                Icons.Outlined.Event,
                stringResource(R.string.ime_calendar_agenda_allow_title),
                stringResource(R.string.ime_calendar_permission_info),
                action = {
                    PillButton(stringResource(R.string.ime_calendar_allow_action), primary = true) { act(CalendarAction.AskRead) }
                },
            )
            days.isEmpty() -> EmptyState(
                Icons.Outlined.Event,
                stringResource(R.string.ime_calendar_agenda_empty_title),
                stringResource(R.string.ime_calendar_agenda_empty_body),
                action = {
                    PillButton(stringResource(R.string.ime_calendar_new_event), icon = Icons.Outlined.Add, primary = true) {
                        act(CalendarAction.NewEvent())
                    }
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 44.dp),
            ) {
                itemsIndexed(days, key = { _, d -> d.jdn }) { g, day ->
                    val open = day == ui.selected
                    val events = if (open) list else seen[day]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        AgendaRail(
                            label = when (day) {
                                today -> stringResource(R.string.ime_calendar_today_label)
                                today.plusDays(1) -> stringResource(R.string.ime_calendar_tomorrow_label)
                                today.plusDays(-1) -> stringResource(R.string.ime_calendar_yesterday_label)
                                else -> remember(day) { CalendarText.dayMonth(day) }
                            },
                            current = day == today,
                            open = open,
                            first = g == 0,
                            last = g == days.lastIndex,
                            modifier = Modifier
                                .width(RAIL_WIDTH)
                                .fillMaxHeight()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    act(CalendarAction.SelectDay(day))
                                },
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(bottom = 6.dp)
                                .focusRing(focusedRow == g, kb.cardShape()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            when {
                                events == null && open -> Spacer(Modifier.height(CARD_HEIGHT))
                                events == null -> PeekCard(day, ui.today, animate) { act(CalendarAction.SelectDay(day)) }
                                events.isEmpty() -> Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.ime_calendar_no_events_empty),
                                        color = kb.secondaryText,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (open) {
                                        PillButton(stringResource(R.string.ime_calendar_new_event), icon = Icons.Outlined.Add, height = 28.dp) {
                                            act(CalendarAction.NewEvent(day))
                                        }
                                    }
                                }
                                else -> events.forEachIndexed { i, e ->
                                    EventCard(
                                        event = e,
                                        hour24 = ui.hour24,
                                        animate = animate,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .popIn(i.coerceAtMost(MAX_STAGGER), animate, key = day),
                                    ) { act(CalendarAction.OpenEvent(e)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One day on the rail: a dot (the accent for today, a ring around the open
 * day), its name under, and a line down the middle joining it to the days
 * before and after. The Notes timeline's rail, in the calendar's words.
 */
@Composable
private fun AgendaRail(label: String, current: Boolean, open: Boolean, first: Boolean, last: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val line = kb.divider
    val dot = if (current) kb.accent else kb.secondaryText.copy(alpha = 0.5f)
    val ring = kb.accent
    Box(
        modifier.drawBehind {
            val x = size.width / 2f
            val top = if (first) RAIL_DOT_Y.toPx() else 0f
            val bottom = if (last) size.height * 0.6f else size.height + RAIL_JOIN.toPx()
            drawLine(line, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(dot, 3.dp.toPx(), Offset(x, RAIL_DOT_Y.toPx()))
            if (open) drawCircle(ring, 6.dp.toPx(), Offset(x, RAIL_DOT_Y.toPx()), style = Stroke(1.5.dp.toPx()))
        },
    ) {
        Text(
            label.uppercase(),
            color = if (current || open) kb.accent else kb.secondaryText,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = RAIL_DOT_Y + 8.dp)
                .fillMaxWidth(),
        )
    }
}

/** A busy day the agenda has not opened yet: its date in full, and a line that a tap loads it. */
@Composable
private fun PeekCard(day: EventDate, today: EventDate, animate: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(source, animate)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.5f))
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Text(
            CalendarText.date(day, today),
            color = kb.suggestionText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(stringResource(R.string.ime_calendar_agenda_peek), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
    }
}

// ---- Event sheet ------------------------------------------------------------

/**
 * One event read whole: a card in its calendar's colour with the title and
 * when, then how it repeats, where, notes, guests and reminders as lines,
 * and along the bottom the ways to act on it: Edit as the pill where it can
 * be changed (else Insert as text), the rest as round buttons.
 */
@Composable
private fun EventSheet(event: StoredEvent, ui: CalendarUi, animate: Boolean, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val colour = if (event.color != 0) Color(event.color) else kb.accent
    val end = event.instanceEnd.takeIf { it > event.instanceBegin } ?: (event.dtEnd ?: event.instanceBegin)
    val zone = event.zone
    val start = EventDate.of(event.instanceBegin, zone)
    val shape = kb.cardShape()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .popIn(0, animate, key = event.id)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CARDS)
                    .background(kb.chip.copy(alpha = kb.chip.alpha * 0.75f))
                    .background(colour.copy(alpha = TINT_ALPHA))
                    .chipBorder(kb, shape)
                    .drawBehind { drawRect(colour, size = Size(STRIPE.toPx(), size.height)) }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.allDay) {
                        AllDayBadge(colour)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        rangeText(event.instanceBegin, end, event.allDay, zone, ui.hour24),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    event.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
                    color = kb.suggestionText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            var line = 1
            ui.series?.let { s ->
                DetailLine(Icons.Outlined.EventRepeat, recurrenceText(RRule.decode(s.rule, start, zone), start, ui.today), index = line++, animate = animate, key = event.id)
            }
            if (event.location.isNotBlank()) DetailLine(Icons.Outlined.LocationOn, event.location, index = line++, animate = animate, key = event.id)
            if (event.notes.isNotBlank()) DetailLine(Icons.Outlined.Notes, event.notes, maxLines = 8, index = line++, animate = animate, key = event.id)
            val guests = event.invited
            if (guests.isNotEmpty()) {
                val yes = stringResource(R.string.ime_calendar_guest_yes)
                val no = stringResource(R.string.ime_calendar_guest_no)
                val maybe = stringResource(R.string.ime_calendar_guest_maybe)
                DetailLine(
                    Icons.Outlined.Group,
                    guests.joinToString("\n") { g ->
                        val status = when (g.status) {
                            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> " · $yes"
                            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> " · $no"
                            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> " · $maybe"
                            else -> ""
                        }
                        g.name.ifBlank { g.email } + status
                    },
                    maxLines = 12,
                    index = line++,
                    animate = animate,
                    key = event.id,
                )
            }
            if (event.reminders.isNotEmpty()) {
                val texts = event.reminders.sorted().map { reminderText(it) }
                DetailLine(Icons.Outlined.Notifications, texts.joinToString(", "), index = line++, animate = animate, key = event.id)
            }
            if (!event.canModify) {
                Text(
                    stringResource(R.string.ime_calendar_read_only),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                )
            }
            ui.error?.let { e ->
                Banner(errorText(e), stringResource(R.string.ime_calendar_done)) { act(CalendarAction.ErrorDismiss) }
            }
        }
        ActionBar(
            buildList {
                if (event.canModify) {
                    add(KitAction(stringResource(R.string.ime_calendar_edit), Icons.Outlined.Edit, enabled = !ui.busy, primary = true) { act(CalendarAction.EditEvent) })
                    add(KitAction(stringResource(R.string.ime_calendar_insert), Icons.Outlined.EditNote) { act(CalendarAction.InsertEvent) })
                } else {
                    add(KitAction(stringResource(R.string.ime_calendar_insert), Icons.Outlined.EditNote, primary = true) { act(CalendarAction.InsertEvent) })
                }
                add(KitAction(stringResource(R.string.ime_calendar_open_app), Icons.Outlined.OpenInNew) { act(CalendarAction.OpenInApp) })
                if (event.canModify) {
                    add(KitAction(stringResource(R.string.ime_calendar_delete), Icons.Outlined.Delete, enabled = !ui.busy) { act(CalendarAction.DeleteEvent) })
                }
            },
            Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun DetailLine(icon: ImageVector, text: String, maxLines: Int = 3, index: Int = 0, animate: Boolean = false, key: Any? = Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .popIn(index.coerceAtMost(MAX_STAGGER), animate, key)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = kb.secondaryText,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = kb.suggestionText, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** The one question a repeating event's change or delete needs answered first. */
@Composable
private fun ScopeSheet(prompt: ScopePrompt, animate: Boolean, act: (CalendarAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    AnimatedVisibility(visible = true, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .popIn(0, animate, key = prompt)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CARDS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(if (prompt.delete) R.string.ime_calendar_scope_delete_title else R.string.ime_calendar_scope_edit_title),
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            for (s in prompt.scopes) {
                PillButton(
                    stringResource(
                        when (s) {
                            EditScope.THIS -> R.string.ime_calendar_scope_this
                            EditScope.FOLLOWING -> R.string.ime_calendar_scope_following
                            EditScope.ALL -> R.string.ime_calendar_scope_all
                        },
                    ),
                    primary = s == prompt.scopes.first(),
                    height = 32.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { act(CalendarAction.Scope(s)) }
            }
            PillButton(stringResource(R.string.ime_calendar_cancel), height = 32.dp, modifier = Modifier.fillMaxWidth()) {
                act(CalendarAction.CancelScope)
            }
        }
    }
}

/** How much of its colour a card wears over the chip fill. */
internal const val TINT_ALPHA = 0.16f

/** The colour stripe down a card's left edge. */
internal val STRIPE = 3.dp

/** The most neighbours that pop in one after another before the rest arrive together. */
internal const val MAX_STAGGER = 6

private val VIEW_SEGMENT = 52.dp

/** Room the panel grows the keyboard by: a month of 30 dp cells plus the day's cards. */
private val CalendarExtraHeight = 48.dp
private val CARD_WIDTH = 190.dp
private val CARD_HEIGHT = 64.dp
private val CARD_ROW_HEIGHT = 68.dp
private val RAIL_WIDTH = 50.dp
private val RAIL_DOT_Y = 10.dp
private val RAIL_JOIN = 6.dp

/** Space between the selected day's disc (and today's ring) and the cell's edge. */
private val DISC_INSET = 6.dp
private const val MAX_DOTS = 3
private const val MONTH_MS = 220

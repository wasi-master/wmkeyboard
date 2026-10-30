package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.ClockNotifications
import com.wasimaster.wmkeyboard.core.clock.RunningTimer
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockController
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.HeroRing
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.SegmentedPill
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.keyFace
import com.wasimaster.wmkeyboard.ime.ui.keyShape
import com.wasimaster.wmkeyboard.ime.ui.keysHaveFaces
import com.wasimaster.wmkeyboard.ime.ui.panelKeyBorder
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.tools.R as ToolsR

/**
 * Timers. With timers running the tab is their tiles, each a hero ring that
 * drains as the time runs out with the time left in the middle, two to a row
 * once there are two; the presets ride a chip row above and "New timer" is
 * the bar along the bottom. With none, or while one is being set, it is a
 * keypad that fills from the right, the way a microwave's does: 1, 3, 0 is a
 * minute and a half.
 */
@Composable
internal fun TimersTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    var entering by remember { mutableStateOf(false) }
    val timers = ui.data.timers
    if (timers.isEmpty() || entering || ui.timerDigits.isNotEmpty()) {
        TimerEntry(
            ui = ui,
            canGoBack = timers.isNotEmpty(),
            onBack = {
                entering = false
                act(ClockAction.TimerClear)
            },
            onStarted = { entering = false },
            act = act,
        )
    } else {
        RunningList(ui, onNew = { entering = true }, act = act)
    }
}

@Composable
private fun RunningList(ui: ClockUi, onNew: () -> Unit, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val now = rememberNowState(RING_TICK_MS)
    val timers = ui.data.timers
    // One timer takes the width; two or more tile in two columns.
    val columns = if (timers.size >= 2) 2 else 1
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ui.data.presets.forEach { p ->
                ToolPanelChip(presetLabel(p.label, p.durationMs)) { act(ClockAction.StartPreset(p.id)) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(timers, key = { _, t -> t.id }) { i, t ->
                TimerTile(t, now, ui, act, index = i, animate = animate, big = columns == 1, modifier = Modifier.animateItem())
            }
        }
        ActionBar(
            listOf(KitAction(stringResource(R.string.ime_clock_new_timer), Icons.Outlined.Add, primary = true, onClick = onNew)),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            height = 36.dp,
        )
    }
}

/**
 * One timer: a ring that drains as the time runs out, the time left in the
 * middle, its name and when it ends under, and its buttons in a row. The
 * ring's fraction and the digits read the clock in their own scopes, so a
 * tick redraws those and not the tile.
 */
@Composable
private fun TimerTile(
    t: RunningTimer,
    now: State<Long>,
    ui: ClockUi,
    act: (ClockAction) -> Unit,
    index: Int,
    animate: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val title = t.label.ifEmpty {
        t.pomodoro?.let { stringResource(ClockNotifications.phaseRes(it.phase)) } ?: stringResource(R.string.ime_clock_timer)
    }
    ClockCard(
        modifier = modifier
            .fillMaxWidth()
            .popIn(index, animate, key = t.id),
        padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HeroRing(
                fraction = { if (t.totalMs <= 0L) 0f else t.remainingAt(now.value).toFloat() / t.totalMs },
                stroke = if (big) 9.dp else 7.dp,
                modifier = Modifier.size(if (big) RING_BIG else RING_SMALL),
            ) {
                Remaining(t, now, big)
            }
            Text(title, color = kb.suggestionText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (t.paused) {
                    stringResource(R.string.ime_clock_paused)
                } else {
                    stringResource(R.string.ime_clock_ends_at, ClockFormats.time(t.endsAt, java.util.TimeZone.getDefault(), ui.hour24))
                },
                color = if (t.paused) kb.accent else kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
            )
            t.pomodoro?.let { run ->
                val plan = ui.config.pomodoro
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 3.dp)) {
                    repeat(plan.longEvery.coerceAtLeast(1)) { i ->
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (i < plan.dotsFilled(run)) kb.accent else kb.secondaryText.copy(alpha = 0.3f)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.paused) {
                    RoundButton(Icons.Outlined.PlayArrow, stringResource(R.string.ime_clock_resume), primary = true, size = 34.dp) {
                        act(ClockAction.ResumeTimer(t.id))
                    }
                } else {
                    RoundButton(Icons.Outlined.Pause, stringResource(R.string.ime_clock_pause), primary = true, size = 34.dp) {
                        act(ClockAction.PauseTimer(t.id))
                    }
                }
                ToolPanelChip(stringResource(R.string.ime_clock_plus_minute)) { act(ClockAction.ExtendTimer(t.id, MINUTE_MS)) }
                if (t.paused && t.pausedRemainingMs < t.totalMs) {
                    RoundButton(Icons.Outlined.Replay, stringResource(R.string.ime_clock_reset), size = 34.dp) {
                        act(ClockAction.ResetTimer(t.id))
                    }
                } else {
                    RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_clock_stop), size = 34.dp) {
                        act(ClockAction.CancelTimer(t.id))
                    }
                }
            }
        }
    }
}

/** The time left, in the ring, in its own scope. */
@Composable
private fun Remaining(t: RunningTimer, now: State<Long>, big: Boolean) {
    val kb = LocalKbTheme.current
    val left = t.remainingAt(now.value)
    val long = left >= HOUR_MS
    Text(
        ClockFormats.countdown(left),
        color = kb.suggestionText,
        style = digits(
            when {
                big && long -> 22.sp
                big -> 30.sp
                long -> 15.sp
                else -> 20.sp
            },
            FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

@Composable
private fun TimerEntry(
    ui: ClockUi,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    act: (ClockAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val ms = ClockController.msOfDigits(ui.timerDigits)
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedPill(
                    labels = listOf(stringResource(R.string.ime_clock_mode_timer), stringResource(R.string.ime_clock_mode_pomodoro)),
                    selected = if (ui.pomodoroMode) 1 else 0,
                    onSelect = { act(ClockAction.PomodoroMode(it == 1)) },
                    segmentWidth = 84.dp,
                )
                Spacer(Modifier.weight(1f))
                if (canGoBack) {
                    RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_clock_cancel), size = 30.dp, onClick = onBack)
                }
            }
            if (ui.pomodoroMode) {
                PomodoroIntro(ui)
                Spacer(Modifier.weight(1f))
                ActionBar(
                    listOf(
                        KitAction(stringResource(R.string.ime_clock_start_focus), Icons.Outlined.PlayArrow, primary = true) {
                            act(ClockAction.StartPomodoro)
                            onStarted()
                        },
                    ),
                    height = 36.dp,
                )
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Readout(ui.timerDigits)
            Text(
                if (ms > 0) ClockText.duration(ms, seconds = true) else stringResource(R.string.ime_clock_type_duration),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            PresetRow(ui, act)
            Spacer(Modifier.weight(1f))
            ActionBar(
                listOf(
                    KitAction(stringResource(R.string.ime_clock_start), Icons.Outlined.PlayArrow, enabled = ms > 0, primary = true) {
                        act(ClockAction.StartTimer)
                        onStarted()
                    },
                    KitAction(ui.timerLabel.ifEmpty { stringResource(R.string.ime_clock_add_label) }, Icons.Outlined.Label) {
                        act(ClockAction.TimerLabel)
                    },
                ),
                height = 36.dp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Keypad(act, Modifier.width(150.dp))
    }
}

/** hh mm ss, the typed digits bright and the padding zeros faint. */
@Composable
private fun Readout(digits: String) {
    val kb = LocalKbTheme.current
    val padded = digits.padStart(6, '0')
    val typedFrom = 6 - digits.length
    Row(verticalAlignment = Alignment.Bottom) {
        listOf(0 to "h", 2 to "m", 4 to "s").forEach { (at, unit) ->
            for (i in at until at + 2) {
                Text(
                    padded[i].toString(),
                    color = if (i >= typedFrom) kb.suggestionText else kb.secondaryText.copy(alpha = 0.5f),
                    style = digits(32.sp),
                )
            }
            Text(unit, color = kb.secondaryText, fontSize = 13.sp, modifier = Modifier.padding(start = 1.dp, end = 6.dp, bottom = 6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ui.data.presets.forEach { p ->
            val shape = kb.chipShape()
            Text(
                presetLabel(p.label, p.durationMs),
                color = kb.chipText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clockChip(shape = shape)
                    .combinedClickable(
                        onClick = { act(ClockAction.TimerClear); act(ClockAction.TimerAdd(p.durationMs)) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            act(ClockAction.DeletePreset(p.id))
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (ui.timerDigits.isNotEmpty()) {
            ToolPanelChip(stringResource(R.string.ime_clock_save_preset), icon = null) { act(ClockAction.SavePreset) }
        }
    }
}

@Composable
private fun presetLabel(label: String, ms: Long): String {
    val time = ClockText.duration(ms, seconds = true)
    return if (label.isEmpty()) time else "$label · $time"
}

/** 1 to 9, then 00, 0 and backspace. A hold on backspace clears. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val rows = listOf("123", "456", "789", "#0<")
    // These are keys, so they dress like the key grid, as the calculator's do:
    // the theme's key shape, glass and outline, and no face at all where the
    // theme's keys have none.
    val shape = kb.keyShape()
    val backspace = stringResource(R.string.ime_clock_backspace_desc)
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .then(if (kb.keysHaveFaces) Modifier.frostGlass(kb, shape, FrostSurface.KEYS) else Modifier)
                            .background(kb.keyFace(kb.key))
                            .panelKeyBorder(kb, shape)
                            .combinedClickable(
                                onClick = {
                                    feedback()
                                    when (c) {
                                        '<' -> act(ClockAction.TimerBackspace)
                                        '#' -> { act(ClockAction.TimerDigit('0')); act(ClockAction.TimerDigit('0')) }
                                        else -> act(ClockAction.TimerDigit(c))
                                    }
                                },
                                onLongClick = if (c == '<') {
                                    { act(ClockAction.TimerClear) }
                                } else {
                                    null
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (c) {
                            '<' -> Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = backspace, tint = kb.keyText, modifier = Modifier.size(18.dp))
                            '#' -> Text("00", color = kb.keyText, style = digits(17.sp, FontWeight.Normal))
                            else -> Text(c.toString(), color = kb.keyText, style = digits(18.sp, FontWeight.Normal))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PomodoroIntro(ui: ClockUi) {
    val kb = LocalKbTheme.current
    val plan = ui.config.pomodoro
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.ime_clock_pomodoro_title), color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        listOf(
            ToolsR.string.core_tools_clock_focus to plan.focusMinutes,
            ToolsR.string.core_tools_clock_short_break to plan.shortBreakMinutes,
            ToolsR.string.core_tools_clock_long_break to plan.longBreakMinutes,
        ).forEach { (res, minutes) ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text(stringResource(res), color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Text(ClockText.duration(minutes * MINUTE_MS), color = kb.suggestionText, fontSize = 12.sp)
            }
        }
        Text(
            pluralStringResource(R.plurals.ime_clock_pomodoro_rhythm, plan.longEvery, plan.longEvery),
            color = kb.secondaryText,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private const val RING_TICK_MS = 250L

/** The ring alone on its row, and the ring two to a row. */
private val RING_BIG = 128.dp
private val RING_SMALL = 96.dp
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

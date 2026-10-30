package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.Stopwatch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn

/**
 * The stopwatch: the reading as hero digits, as large as the width allows,
 * with a thin line under them that fills once a minute; the laps as compact
 * cards under that, newest first, each with its own length and how it
 * compares with the lap before; and the bar: Start or Stop as the primary,
 * Lap, Reset and Insert as round buttons. It keeps running with the panel
 * closed, and the shade has its buttons.
 *
 * The reading is a state read only by the digits and the line, so a frame of
 * the run redraws those and leaves the laps and the bar alone.
 */
@Composable
internal fun StopwatchTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val sw = ui.data.stopwatch
    val elapsed = rememberElapsed(sw, animate = !state.settings.reduceMotion)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Reading(elapsed, sw)
            MinuteLine(
                elapsed,
                sw.running,
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(LINE_SHARE)
                    .height(3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (sw.laps.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            Laps(sw, animate, Modifier.weight(1f))
        }
        ActionBar(
            listOf(
                KitAction(
                    stringResource(if (sw.running) R.string.ime_clock_stop else R.string.ime_clock_start),
                    if (sw.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    primary = true,
                ) { act(ClockAction.StopwatchToggle) },
                KitAction(stringResource(R.string.ime_clock_lap), Icons.Outlined.Flag, enabled = sw.running) { act(ClockAction.StopwatchLap) },
                KitAction(stringResource(R.string.ime_clock_reset), Icons.Outlined.Replay, enabled = !sw.running && !sw.isReset) { act(ClockAction.StopwatchReset) },
                KitAction(stringResource(R.string.ime_clock_insert_laps), Icons.Outlined.ContentPaste) { act(ClockAction.StopwatchInsert) },
            ),
            modifier = Modifier.padding(vertical = 4.dp),
            height = 36.dp,
        )
    }
}

/**
 * The reading, as a state: moved every frame while it runs (every tenth of a
 * second with reduced motion), still while it is stopped.
 */
@Composable
private fun rememberElapsed(sw: Stopwatch, animate: Boolean): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sw.running, animate) {
        if (!sw.running) return@LaunchedEffect
        while (true) {
            if (animate) {
                withFrameMillis { now.longValue = System.currentTimeMillis() }
            } else {
                now.longValue = System.currentTimeMillis()
                kotlinx.coroutines.delay(SLOW_TICK_MS)
            }
        }
    }
    return remember(sw) { derivedStateOf { sw.elapsedAt(now.longValue) } }
}

/** The hero digits and, under them, how long the lap under way has run. */
@Composable
private fun Reading(elapsed: State<Long>, sw: Stopwatch) {
    val kb = LocalKbTheme.current
    val e = elapsed.value
    Text(
        ClockFormats.stopwatch(e),
        color = kb.suggestionText,
        style = digits(if (e >= HOUR_MS) 40.sp else 52.sp),
        maxLines = 1,
    )
    if (sw.laps.isNotEmpty()) {
        Text(
            ClockFormats.stopwatch(e - sw.laps.last()),
            color = kb.secondaryText,
            style = digits(13.sp, FontWeight.Normal),
            maxLines = 1,
        )
    }
}

/** A thin line that fills once a minute, read in the draw alone. */
@Composable
private fun MinuteLine(elapsed: State<Long>, running: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val track = kb.divider
    val fill = if (running) kb.accent else kb.secondaryText.copy(alpha = 0.5f)
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        val fraction = (elapsed.value % MINUTE_MS) / MINUTE_MS.toFloat()
        if (fraction > 0f) drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
    }
}

/** The laps, newest first; a new one pops in at the top. */
@Composable
private fun Laps(sw: Stopwatch, animate: Boolean, modifier: Modifier = Modifier) {
    val lengths = sw.lapLengths
    val best = if (lengths.size > 1) lengths.min() else null
    val worst = if (lengths.size > 1) lengths.max() else null
    val listState = rememberLazyListState()
    LaunchedEffect(lengths.size) { listState.animateScrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val rows = lengths.indices.reversed().toList()
        itemsIndexed(rows, key = { _, i -> i }) { pos, i ->
            val len = lengths[i]
            LapCard(
                number = i + 1,
                length = len,
                total = sw.laps[i],
                delta = if (i > 0) len - lengths[i - 1] else null,
                tone = when (len) {
                    best -> BEST
                    worst -> WORST
                    else -> null
                },
                index = pos.coerceAtMost(MAX_STAGGER),
                animate = animate,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** One lap: its number, its length (green for the fastest, red for the slowest), the change from the lap before, the total. */
@Composable
private fun LapCard(
    number: Int,
    length: Long,
    total: Long,
    delta: Long?,
    tone: Color?,
    index: Int,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    ClockCard(
        modifier = modifier
            .fillMaxWidth()
            .popIn(index, animate, key = number),
        padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ime_clock_lap_number, number),
                color = kb.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(48.dp),
                maxLines = 1,
            )
            Text(
                ClockFormats.stopwatch(length),
                color = tone ?: kb.suggestionText,
                style = digits(15.sp, FontWeight.Medium),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (delta != null) {
                Text(
                    (if (delta < 0) "−" else "+") + ClockFormats.stopwatch(kotlin.math.abs(delta)),
                    color = if (delta < 0) BEST else kb.secondaryText,
                    style = digits(11.sp, FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(ClockFormats.stopwatch(total), color = kb.secondaryText, style = digits(11.sp, FontWeight.Normal), maxLines = 1)
        }
    }
}

private val BEST = Color(0xFF43A047)
private val WORST = Color(0xFFE53935)
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val SLOW_TICK_MS = 100L

/** How much of the width the minute line takes under the digits. */
private const val LINE_SHARE = 0.6f

/** Laps further down the list than this pop in with the last beat, not later. */
private const val MAX_STAGGER = 6

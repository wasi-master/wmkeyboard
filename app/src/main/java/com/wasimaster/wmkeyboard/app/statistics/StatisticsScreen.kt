package com.wasimaster.wmkeyboard.app.statistics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.ChoiceControl
import com.wasimaster.wmkeyboard.app.SectionHeader
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.storage.ConfirmDeleteDialog
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.StatsPeriod
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import com.wasimaster.wmkeyboard.core.tools.TypingStatsMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How much you type: all-time totals, a day/week/month history with charts,
 * and the switch that turns the counting off.
 *
 * The screen is a *reader* of the keyboard's [TypingStats] file — it builds
 * its own instance on the same path and never calls save, so it cannot race
 * the keyboard into losing counts. Deleting goes the other way round: empty
 * the file, then bump [KeyboardSettings.statsVersion] so the keyboard drops
 * its in-memory copy instead of writing the old numbers back.
 */
@Composable
internal fun StatisticsScreen(repository: SettingsRepository, settings: KeyboardSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    var entries by remember { mutableStateOf<List<TypingStats.DayEntry>>(emptyList()) }
    var totals by remember { mutableStateOf<TypingStats.Totals?>(null) }
    // statsVersion is the reload signal both here and in the keyboard: the
    // delete below bumps it, and this effect re-reads the emptied file.
    LaunchedEffect(settings.statsVersion) {
        val read = withContext(Dispatchers.IO) {
            val stats = TypingStats(File(context.filesDir, TypingStats.FILE_PATH))
            stats.dayEntries() to stats.lifetime()
        }
        entries = read.first
        totals = read.second
    }

    ToggleSetting(
        R.string.statistics_toggle_title,
        stringResource(R.string.statistics_toggle_subtitle),
        settings.typingStatsEnabled,
        info = stringResource(R.string.statistics_toggle_info),
        default = SettingsDefaults.typingStatsEnabled,
    ) { scope.launch { repository.setTypingStatsEnabled(it) } }

    val lifetime = totals
    if (!settings.typingStatsEnabled) {
        CaptionText(stringResource(R.string.statistics_off_body))
    } else if (lifetime != null && lifetime.chars == 0L) {
        CaptionText(stringResource(R.string.statistics_empty_body))
    }
    if (lifetime == null || lifetime.chars == 0L) {
        DeleteStatistics(enabled = false) { }
        return
    }

    SectionHeader(stringResource(R.string.statistics_totals_section))
    TotalsTiles(lifetime)

    SectionHeader(stringResource(R.string.statistics_history_section))
    HistoryCard(entries)

    if (lifetime.hourHistogram.any { it > 0L }) {
        SectionHeader(stringResource(R.string.statistics_hours_section))
        HoursCard(lifetime.hourHistogram)
    }

    DeleteStatistics(enabled = true) { confirmDelete = true }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.statistics_delete_confirm_title),
            body = stringResource(R.string.statistics_delete_confirm_body),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch(Dispatchers.IO) {
                    TypingStats(File(context.filesDir, TypingStats.FILE_PATH)).clear()
                    // The keyboard's cue not to save the old numbers back —
                    // and this screen's own cue to re-read, via the effect.
                    repository.bumpStatsVersion()
                }
            },
        )
    }
}

/** The five all-time numbers, three to a row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TotalsTiles(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val wpm = TypingStatsMath.wpm(totals.chars, totals.activeMs)
    val speed = if (totals.activeMs >= TypingStats.MIN_ACTIVE_MS) {
        stringResource(R.string.statistics_wpm_value, wpm.toInt())
    } else {
        stringResource(R.string.statistics_value_none)
    }
    SettingsGroup {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                StatTile(numbers.format(totals.chars), stringResource(R.string.statistics_chars_label))
                StatTile(numbers.format(totals.words), stringResource(R.string.statistics_words_label))
                StatTile(speed, stringResource(R.string.statistics_speed_label))
                StatTile(formatDuration(totals.activeMs), stringResource(R.string.statistics_active_label))
                StatTile(
                    numbers.format(totals.backspaces),
                    stringResource(R.string.statistics_backspaces_label),
                )
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The history chart with its two pickers, and the per-bucket rows below it.
 * The chart is hidden from screen readers — the rows restate every number.
 */
@Composable
private fun HistoryCard(entries: List<TypingStats.DayEntry>) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.DAY) }
    var metric by rememberSaveable { mutableStateOf(Metric.WORDS) }
    val today = remember {
        TypingStatsMath.localEpochDay(System.currentTimeMillis(), TimeZone.getDefault())
    }
    val buckets = remember(entries, period) {
        TypingStatsMath.rollup(entries, period, today)
    }
    val values = buckets.map { bucket ->
        when (metric) {
            Metric.WORDS -> bucket.words.toDouble()
            Metric.CHARS -> bucket.chars.toDouble()
            Metric.SPEED -> TypingStatsMath.wpm(bucket.chars, bucket.activeMs)
        }
    }
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ChoiceControl(
                    options = listOf(
                        StatsPeriod.DAY to stringResource(R.string.statistics_period_day_label),
                        StatsPeriod.WEEK to stringResource(R.string.statistics_period_week_label),
                        StatsPeriod.MONTH to stringResource(R.string.statistics_period_month_label),
                    ),
                    selected = period,
                    label = stringResource(R.string.statistics_period_label),
                ) { period = it }
                ChoiceControl(
                    options = listOf(
                        Metric.WORDS to stringResource(R.string.statistics_chart_words_label),
                        Metric.CHARS to stringResource(R.string.statistics_chart_chars_label),
                        Metric.SPEED to stringResource(R.string.statistics_chart_speed_label),
                    ),
                    selected = metric,
                    modifier = Modifier.padding(top = 8.dp),
                    label = stringResource(R.string.statistics_chart_metric_label),
                ) { metric = it }
                if (metric == Metric.SPEED) {
                    SpeedLine(values, modifier = Modifier.padding(top = 16.dp))
                } else {
                    HistoryBars(values, modifier = Modifier.padding(top = 16.dp))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        bucketLabel(buckets.first().startEpochDay, period),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        bucketLabel(buckets.last().startEpochDay, period),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    BucketRows(buckets, period)
}

/** Newest first, empty buckets skipped: the words the chart cannot say. */
@Composable
private fun BucketRows(buckets: List<TypingStatsMath.Bucket>, period: StatsPeriod) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val nonEmpty = buckets.filter { it.chars > 0L || it.words > 0L }.asReversed()
    if (nonEmpty.isEmpty()) return
    SettingsGroup {
        for (bucket in nonEmpty) {
            item {
                WmRow(
                    title = bucketLabel(bucket.startEpochDay, period),
                    subtitle = stringResource(
                        R.string.statistics_bucket_detail,
                        numbers.format(bucket.words),
                        numbers.format(bucket.chars),
                    ),
                    trailing = {
                        if (bucket.activeMs > 0L) {
                            Text(
                                stringResource(
                                    R.string.statistics_wpm_value,
                                    TypingStatsMath.wpm(bucket.chars, bucket.activeMs).toInt(),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The 24 hours of the day, with the busiest one named in words below. */
@Composable
private fun HoursCard(histogram: List<Long>) {
    val context = LocalContext.current
    val busiest = histogram.indices.maxBy { histogram[it] }
    val busiestLabel = remember(busiest) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, busiest)
        calendar.set(Calendar.MINUTE, 0)
        android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
    }
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                HistoryBars(
                    histogram.map { it.toDouble() },
                    highlight = busiest,
                )
                Text(
                    stringResource(R.string.statistics_busiest_hour_label, busiestLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DeleteStatistics(enabled: Boolean, onClick: () -> Unit) {
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilledTonalButton(onClick = onClick, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Text(
                        stringResource(R.string.statistics_delete_action),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A plain bar per bucket, the newest (or [highlight]ed) one in the accent and
 * the rest faded — the same shape the typing test draws its history with.
 * Hidden from screen readers; the rows and text below restate the numbers.
 */
@Composable
private fun HistoryBars(
    values: List<Double>,
    modifier: Modifier = Modifier,
    highlight: Int = values.lastIndex,
) {
    val accent = MaterialTheme.colorScheme.primary
    val grown by animateFloatAsState(
        targetValue = if (values.any { it > 0.0 }) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "statsBars",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .clearAndSetSemantics {},
    ) {
        if (values.isEmpty()) return@Canvas
        val peak = values.max().coerceAtLeast(1.0)
        val gap = BarGap.toPx()
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        val radius = CornerRadius(barWidth / 3f)
        values.forEachIndexed { index, value ->
            val height = (value / peak * size.height).toFloat() * grown
            // A quiet bucket still leaves a tick, so the axis reads as a run
            // of days rather than a chart with holes in it.
            val drawn = height.coerceAtLeast(FLOOR_PX)
            drawRoundRect(
                color = if (index == highlight) accent else accent.copy(alpha = FADED_ALPHA),
                topLeft = Offset(index * (barWidth + gap), size.height - drawn),
                size = Size(barWidth, drawn),
                cornerRadius = radius,
            )
        }
    }
}

/** The speed trend as a line — the typing test's graph, one line, Material ink. */
@Composable
private fun SpeedLine(values: List<Double>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val grown by animateFloatAsState(
        targetValue = if (values.any { it > 0.0 }) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "statsLine",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .clearAndSetSemantics {},
    ) {
        val peak = values.max().coerceAtLeast(10.0)
        for (line in 1..GRID_LINES) {
            val y = size.height * line / (GRID_LINES + 1)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        if (values.size < 2) return@Canvas
        fun x(index: Int) = size.width * index / (values.size - 1)
        fun y(value: Double) = size.height * (1f - (value / peak * grown).toFloat())
        val path = Path().apply {
            moveTo(x(0), y(values[0]))
            for (index in 1 until values.size) lineTo(x(index), y(values[index]))
        }
        drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        values.forEachIndexed { index, value ->
            if (value > 0.0) drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(x(index), y(value)))
        }
    }
}

private enum class Metric { WORDS, CHARS, SPEED }

/**
 * Names a bucket for its row and the chart's edges. The epoch day is local,
 * so the formatter reads it back in UTC — the default zone would shift the
 * date by a day for half the world.
 */
@Composable
private fun bucketLabel(epochDay: Int, period: StatsPeriod): String = remember(epochDay, period) {
    val date = Date(epochDay * DAY_MS)
    val utc = TimeZone.getTimeZone("UTC")
    when (period) {
        StatsPeriod.DAY, StatsPeriod.WEEK ->
            DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = utc }.format(date)
        StatsPeriod.MONTH ->
            SimpleDateFormat("LLLL yyyy", Locale.getDefault()).apply { timeZone = utc }.format(date)
    }
}

@Composable
private fun formatDuration(activeMs: Long): String {
    val hours = (activeMs / HOUR_MS).toInt()
    val minutes = ((activeMs % HOUR_MS) / MINUTE_MS).toInt()
    return if (hours > 0) {
        stringResource(R.string.statistics_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.statistics_duration_minutes, minutes)
    }
}

private val ChartHeight = 96.dp
private val BarGap = 3.dp

private const val FADED_ALPHA = 0.35f
private const val FLOOR_PX = 2f
private const val GRID_LINES = 3
private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L
private const val MINUTE_MS = 60_000L

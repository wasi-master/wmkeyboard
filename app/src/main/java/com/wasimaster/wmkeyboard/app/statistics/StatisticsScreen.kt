package com.wasimaster.wmkeyboard.app.statistics

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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.ChoiceControl
import com.wasimaster.wmkeyboard.app.SectionHeader
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.ChartIntroScope
import com.wasimaster.wmkeyboard.app.CountingText
import com.wasimaster.wmkeyboard.app.chartEntrance
import com.wasimaster.wmkeyboard.app.rememberChartEntrance
import com.wasimaster.wmkeyboard.app.rememberValuesMorph
import com.wasimaster.wmkeyboard.app.storage.ConfirmDeleteDialog
import com.wasimaster.wmkeyboard.core.prediction.CorrectionMemory
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LEARNED_CORRECTIONS_FILE
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.StatsPeriod
import com.wasimaster.wmkeyboard.core.tools.TypingInsights
import com.wasimaster.wmkeyboard.core.tools.TypingProgress
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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Abc
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.app.ChoiceDetail

/**
 * How much you type: your level and achievements, where you tap, your typos
 * and the slips behind them, who you are when you type, all-time totals, a
 * day/week/month history with charts, and the switch that turns the counting
 * off.
 *
 * The screen is a *reader* of the keyboard's [TypingStats] file — it builds
 * its own instance on the same path and never calls save, so it cannot race
 * the keyboard into losing counts. Deleting goes the other way round: empty
 * the file, then bump [KeyboardSettings.statsVersion] so the keyboard drops
 * its in-memory copy instead of writing the old numbers back. The slips and
 * the fix pairs are read the same way off the learned corrections store.
 */
@Composable
internal fun StatisticsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenLearnedCorrections: () -> Unit = {},
    onOpenEggGame: () -> Unit = {},
) {
    // The charts and figures fill in when the screen opens, and are simply
    // there when it is composed again for the same visit.
    ChartIntroScope { StatisticsBody(repository, settings, onOpenLearnedCorrections, onOpenEggGame) }
}

/** What the learned corrections store has to say: the slips, and the pairs. */
private class Slips(
    val habits: List<CorrectionMemory.Habit> = emptyList(),
    val pairs: List<Pair<String, CorrectionMemory.Taught>> = emptyList(),
)

@Composable
private fun StatisticsBody(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenLearnedCorrections: () -> Unit,
    onOpenEggGame: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    var entries by remember { mutableStateOf<List<TypingStats.DayEntry>>(emptyList()) }
    var totals by remember { mutableStateOf<TypingStats.Totals?>(null) }
    var heatmaps by remember { mutableStateOf<List<TypingStats.Heatmap>>(emptyList()) }
    // statsVersion is the reload signal both here and in the keyboard: the
    // delete below bumps it, and this effect re-reads the emptied file.
    LaunchedEffect(settings.watch { it.statsVersion }) {
        val read = withContext(Dispatchers.IO) {
            val stats = TypingStats(File(context.filesDir, TypingStats.FILE_PATH))
            // With sync on, every other device's counts are added in (#447).
            // Only read here, never saved: this instance is a reader.
            stats.absorbDevices(
                runCatching { File(context.filesDir, TypingStats.DEVICES_FILE_PATH).readText() }.getOrNull(),
            )
            Triple(stats.dayEntries(), stats.lifetime(), stats.heatmaps())
        }
        entries = read.first
        totals = read.second
        heatmaps = read.third
    }
    // The slips and fix pairs live in the learned corrections store, which
    // has a reload signal of its own; read, never saved, like the counts.
    var slips by remember { mutableStateOf(Slips()) }
    LaunchedEffect(settings.watch { it.suggestionStrip.correctionsVersion }) {
        slips = withContext(Dispatchers.IO) {
            val memory = CorrectionMemory(File(context.filesDir, LEARNED_CORRECTIONS_FILE))
            Slips(memory.habitSummary(), memory.pairs())
        }
    }

    val statsOn = settings.watch { it.typingStatsEnabled }
    ToggleSetting(
        R.string.statistics_toggle_title,
        stringResource(R.string.statistics_toggle_subtitle),
        statsOn,
        info = stringResource(R.string.statistics_toggle_info),
        default = SettingsDefaults.typingStatsEnabled,
    ) { scope.launch { repository.setTypingStatsEnabled(it) } }

    val lifetime = totals
    val empty = lifetime == null ||
        (lifetime.chars == 0L && !TypingProgress.hasProgress(lifetime) && heatmaps.isEmpty())
    if (!statsOn) {
        CaptionText(stringResource(R.string.statistics_off_body))
    } else if (lifetime != null && empty) {
        CaptionText(stringResource(R.string.statistics_empty_body))
    }
    if (lifetime == null || empty) {
        // The game keeps its own figures, so it has a section with or without typing ones.
        KeycapCatcherStats(onOpenEggGame)
        DeleteStatistics(enabled = false) { }
        return
    }

    // Levels and achievements first, the way SwiftKey leads with them (#390):
    // they are the part people open the screen to look at.
    SectionHeader(
        stringResource(R.string.statistics_level_section),
        info = stringResource(R.string.statistics_level_info),
    )
    LevelCard(TypingProgress.level(lifetime))

    SectionHeader(stringResource(R.string.statistics_achievements_section))
    AchievementRows(TypingProgress.achievements(lifetime))

    if (entries.any(TypingInsights::active)) {
        SectionHeader(
            stringResource(R.string.statistics_streak_section),
            info = stringResource(R.string.statistics_streak_info),
        )
        StreakCard(entries, todayEpochDay())
    }

    // Typos: what went wrong and how, once there is a word to be wrong in.
    if (lifetime.words > 0L || lifetime.autocorrects > 0L) {
        SectionHeader(
            stringResource(R.string.statistics_typos_section),
            info = stringResource(R.string.statistics_typos_info),
        )
        TyposTiles(lifetime)
        TypoRows(lifetime)
    }
    if (slips.habits.isNotEmpty() || slips.pairs.isNotEmpty()) {
        SectionHeader(
            stringResource(R.string.statistics_slips_section),
            info = stringResource(R.string.statistics_slips_info),
        )
        SlipRows(slips.habits, slips.pairs, onOpenLearnedCorrections)
    }

    if (heatmaps.isNotEmpty()) {
        SectionHeader(
            stringResource(R.string.statistics_heatmap_section),
            info = stringResource(R.string.statistics_heatmap_info),
        )
        HeatmapCard(heatmaps)
    }

    SectionHeader(stringResource(R.string.statistics_totals_section))
    TotalsTiles(lifetime)

    if (TypingInsights.sources(lifetime).any { it.second > 0L }) {
        SectionHeader(
            stringResource(R.string.statistics_sources_section),
            info = stringResource(R.string.statistics_sources_info),
        )
        SourcesCard(lifetime)
    }

    val badges = remember(lifetime) { TypingInsights.badges(lifetime) }
    if (badges.isNotEmpty()) {
        SectionHeader(
            stringResource(R.string.statistics_persona_section),
            info = stringResource(R.string.statistics_persona_info),
        )
        PersonaRows(badges)
    }

    if (hasPunctuation(lifetime)) {
        SectionHeader(
            stringResource(R.string.statistics_punctuation_section),
            info = stringResource(R.string.statistics_punctuation_info),
        )
        PunctuationCard(lifetime)
    }

    if (hasRecords(lifetime)) {
        SectionHeader(stringResource(R.string.statistics_records_section))
        RecordBookRows(lifetime)
    }

    if (lifetime.wordsByLanguage.values.any { it > 0L }) {
        SectionHeader(
            stringResource(R.string.statistics_languages_section),
            info = stringResource(R.string.statistics_languages_info),
        )
        LanguagesCard(lifetime)
    }

    // Issue #761: dictation, beside the typing and never inside it.
    if (lifetime.voiceWords > 0L) {
        SectionHeader(
            stringResource(R.string.statistics_voice_section),
            info = stringResource(R.string.statistics_voice_info),
        )
        VoiceTiles(lifetime)
    }

    SectionHeader(stringResource(R.string.statistics_bests_section))
    BestsRows(lifetime, entries)

    SectionHeader(stringResource(R.string.statistics_equivalents_section))
    EquivalentsRows(lifetime)

    SectionHeader(stringResource(R.string.statistics_history_section))
    HistoryCard(entries)

    if (lifetime.hourHistogram.any { it > 0L }) {
        SectionHeader(stringResource(R.string.statistics_hours_section))
        HoursCard(lifetime.hourHistogram)
    }

    if (lifetime.weekHourHistogram.any { it > 0L }) {
        SectionHeader(
            stringResource(R.string.statistics_punch_section),
            info = stringResource(R.string.statistics_punch_info),
        )
        PunchCard(lifetime.weekHourHistogram)
    }

    KeycapCatcherStats(onOpenEggGame)

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
                    // The other devices' counts too: the screen shows the
                    // total, and sync carries the deletion to them.
                    File(context.filesDir, TypingStats.DEVICES_FILE_PATH).delete()
                    // The keyboard's cue not to save the old numbers back —
                    // and this screen's own cue to re-read, via the effect.
                    repository.bumpStatsVersion()
                }
            },
        )
    }
}

/**
 * The five all-time numbers, three to a row. Each counts up to itself the
 * first time the tiles are on screen, a beat after the one before it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TotalsTiles(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val timed = totals.activeMs >= TypingStats.MIN_ACTIVE_MS
    val wpm = TypingStatsMath.wpm(totals.chars, totals.activeMs).toLong()
    val none = stringResource(R.string.statistics_value_none)
    // Only for knowing when the tiles are on screen: the counting is theirs.
    val entrance = rememberChartEntrance(ready = true)
    val seen = entrance.seen
    SettingsGroup {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .chartEntrance(entrance),
            ) {
                StatTile(totals.chars, stringResource(R.string.statistics_chars_label), seen, 0) {
                    numbers.format(it)
                }
                StatTile(totals.words, stringResource(R.string.statistics_words_label), seen, 1) {
                    numbers.format(it)
                }
                StatTile(wpm, stringResource(R.string.statistics_speed_label), seen, 2) {
                    if (timed) stringResource(R.string.statistics_wpm_value, it.toInt()) else none
                }
                StatTile(totals.activeMs, stringResource(R.string.statistics_active_label), seen, 3) {
                    formatDuration(it)
                }
                StatTile(totals.backspaces, stringResource(R.string.statistics_backspaces_label), seen, 4) {
                    numbers.format(it)
                }
            }
        }
    }
}

@Composable
internal fun StatTile(
    value: Long,
    label: String,
    seen: Boolean,
    order: Int,
    format: @Composable (Long) -> String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountingText(
            target = value,
            format = format,
            style = MaterialTheme.typography.titleLarge,
            start = seen,
            delayMs = order * TILE_STAGGER_MS,
        )
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
            Metric.SPOKEN -> bucket.voiceWords.toDouble()
            Metric.TYPOS -> bucket.typos.toDouble()
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
                        Metric.SPOKEN to stringResource(R.string.statistics_chart_spoken_label),
                        Metric.TYPOS to stringResource(R.string.statistics_chart_typos_label),
                    ),
                    selected = metric,
                    modifier = Modifier.padding(top = 8.dp),
                    label = stringResource(R.string.statistics_chart_metric_label),
                    detail = { m ->
                        ChoiceDetail(
                            icon = when (m) {
                                Metric.WORDS -> Icons.Outlined.TextFields
                                Metric.CHARS -> Icons.Outlined.Abc
                                Metric.SPEED -> Icons.Outlined.Speed
                                Metric.SPOKEN -> Icons.Outlined.Mic
                                Metric.TYPOS -> Icons.Outlined.Spellcheck
                            },
                        )
                    },
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
    val nonEmpty = buckets.filter { it.chars > 0L || it.words > 0L || it.voiceWords > 0L || it.typos > 0L }.asReversed()
    if (nonEmpty.isEmpty()) return
    SettingsGroup {
        for (bucket in nonEmpty) {
            item {
                WmRow(
                    title = bucketLabel(bucket.startEpochDay, period),
                    subtitle = if (bucket.voiceWords > 0L) {
                        stringResource(
                            R.string.statistics_bucket_detail_voice,
                            numbers.format(bucket.words),
                            numbers.format(bucket.chars),
                            numbers.format(bucket.voiceWords),
                        )
                    } else {
                        stringResource(
                            R.string.statistics_bucket_detail,
                            numbers.format(bucket.words),
                            numbers.format(bucket.chars),
                        )
                    },
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
 *
 * The bars grow up from their ticks the first time the chart is on screen,
 * left to right a little apart. Another period or metric after that slides
 * each bar to its new height — see [rememberValuesMorph] — rather than
 * throwing them all back to the floor.
 */
@Composable
private fun HistoryBars(
    values: List<Double>,
    modifier: Modifier = Modifier,
    highlight: Int = values.lastIndex,
) {
    val accent = MaterialTheme.colorScheme.primary
    val heights = remember(values) {
        val peak = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        values.map { (it / peak).toFloat() }
    }
    val morph = rememberValuesMorph(heights)
    val entrance = rememberChartEntrance(ready = values.any { it > 0.0 })
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .chartEntrance(entrance)
            .clearAndSetSemantics {},
    ) {
        val count = morph.size
        if (count == 0) return@Canvas
        val gap = BarGap.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius(barWidth / 3f)
        for (index in 0 until count) {
            val height = morph[index] * size.height * entrance.at(index, count, BAR_SPREAD)
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

/**
 * The speed trend as a line — the typing test's graph, one line, Material ink.
 *
 * Draws itself on from the left the first time it is on screen, a dot leading
 * the line like a pen; another period after that slides the points to their
 * new heights, as the bars do.
 */
@Composable
private fun SpeedLine(values: List<Double>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val heights = remember(values) {
        val peak = (values.maxOrNull() ?: 0.0).coerceAtLeast(10.0)
        values.map { (it / peak).toFloat() }
    }
    val morph = rememberValuesMorph(heights)
    val entrance = rememberChartEntrance(ready = values.any { it > 0.0 })
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .chartEntrance(entrance)
            .clearAndSetSemantics {},
    ) {
        for (line in 1..GRID_LINES) {
            val y = size.height * line / (GRID_LINES + 1)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val count = morph.size
        if (count < 2) return@Canvas
        fun x(index: Int) = size.width * index / (count - 1)
        fun y(height: Float) = size.height * (1f - height)
        val path = Path().apply {
            moveTo(x(0), y(morph[0]))
            for (index in 1 until count) lineTo(x(index), y(morph[index]))
        }
        val reveal = entrance.at(0f, spread = 0f)
        val dot = 2.5.dp.toPx()
        clipRect(right = size.width * reveal + dot) {
            drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            for (index in 0 until count) {
                // The dots mark the buckets that have a speed at all. Read off
                // the morph, so a dot leaves with its bucket on a switch.
                if (morph[index] > 0f) drawCircle(accent, radius = dot, center = Offset(x(index), y(morph[index])))
            }
        }
        if (reveal in 0.001f..0.999f) {
            // The pen: where the line has got to, between two buckets.
            val along = reveal * (count - 1)
            val low = along.toInt().coerceIn(0, count - 2)
            val t = along - low
            val height = morph[low] * (1f - t) + morph[low + 1] * t
            drawCircle(accent, radius = dot * PEN_SCALE, center = Offset(size.width * reveal, y(height)))
        }
    }
}

private enum class Metric { WORDS, CHARS, SPEED, SPOKEN, TYPOS }

/**
 * Names a bucket for its row and the chart's edges. The epoch day is local,
 * so the formatter reads it back in UTC — the default zone would shift the
 * date by a day for half the world.
 */
@Composable
internal fun bucketLabel(epochDay: Int, period: StatsPeriod): String = remember(epochDay, period) {
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
internal fun formatDuration(activeMs: Long): String {
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

/** How much of the bars' entrance is spent staggering them, left to right. */
private const val BAR_SPREAD = 0.4f

/** The pen dot at the head of the speed line, against a bucket's dot. */
private const val PEN_SCALE = 1.4f

/** Each total tile starts counting this long after the one before it. */
private const val TILE_STAGGER_MS = 40
private const val FLOOR_PX = 2f
private const val GRID_LINES = 3
private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L
private const val MINUTE_MS = 60_000L

package com.wasimaster.wmkeyboard.app.netlog

import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.providerCredentials
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudDone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lan
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.ChoiceControl
import com.wasimaster.wmkeyboard.app.DOCS_URL
import com.wasimaster.wmkeyboard.app.ExpandableCard
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.LocalReduceMotion
import com.wasimaster.wmkeyboard.app.LocalSettingsSnackbar
import com.wasimaster.wmkeyboard.app.SectionHeader
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.WmIconTile
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.rememberScreenSettled
import com.wasimaster.wmkeyboard.core.netlog.NetDay
import com.wasimaster.wmkeyboard.core.netlog.NetEntry
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.NetTally
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.common.R as CommonR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.TimeZone

/**
 * Settings › Privacy › Network activity: every request the keyboard's own code
 * made, read from [NetLog].
 *
 * Top to bottom: what is happening right now, a period's totals with a chart
 * split by feature, the same totals by feature and by server, the timeline of
 * requests with filters, and the switches. A row opens [NetRequestSheet], which
 * says why the request happened and what it carried.
 *
 * The screen and the keyboard share one process and one [NetLog], so there is
 * nothing to load from elsewhere: [NetLog.version] ticks and the screen reads
 * the log again, off the main thread.
 */
@Composable
internal fun NetworkActivityScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by NetLog.version.collectAsStateWithLifecycle()
    val inFlight by NetLog.inFlight.collectAsStateWithLifecycle()
    val snapshot by produceState<NetSnapshot?>(null, version) {
        value = withContext(Dispatchers.Default) { NetSnapshot.read() }
    }
    var period by rememberSaveable { mutableStateOf(NetPeriod.TODAY) }
    var filter by remember { mutableStateOf(NetFilter()) }
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    var open by remember { mutableStateOf<NetEntry?>(null) }
    val settled = rememberScreenSettled()
    val own = settings.watch { ownHosts(it) }
    val looks = settings.watch { s -> NetSource.entries.associateWith { netSourceLook(it, s) } }

    LiveLine(inFlight.lastOrNull(), inFlight.size, snapshot?.rows?.firstOrNull()?.lastMillis, looks)

    if (!settings.watch { it.networkLog.keep }) CaptionText(stringResource(R.string.netlog_off_body))

    val data = snapshot ?: return
    val summary = remember(data, period) { summarise(period, data.days, data.today, data.firstSeen) }

    if (data.rows.isEmpty() && data.days.all { it.total.requests == 0L }) {
        EmptyCard(data.since)
    } else {
        HeroCard(summary, looks, period) { period = it }
        if (settled) {
            if (summary.sources.isNotEmpty()) {
                SectionHeader(stringResource(R.string.netlog_by_feature_section))
                ByFeature(summary, looks) { source ->
                    filter = NetFilter(source = source)
                    shown = PAGE
                }
            }
            if (summary.hosts.isNotEmpty()) {
                SectionHeader(
                    stringResource(R.string.netlog_servers_section) + "  " +
                        NumberFormat.getIntegerInstance().format(summary.hosts.size),
                )
                Servers(summary.hosts, looks, own, data.today, localDay(data.since, TimeZone.getDefault())) { host ->
                    filter = NetFilter(host = host)
                    shown = PAGE
                }
            }
            SectionHeader(stringResource(R.string.netlog_timeline_section))
            Filters(filter, looks) {
                filter = it
                shown = PAGE
            }
            Timeline(data.rows, filter, shown, looks, onMore = { shown += PAGE }) { open = it }
        }
    }

    if (settled) {
        Spacer(Modifier.height(8.dp))
        ExpandableCard(stringResource(R.string.netlog_invisible_title)) {
            val uri = LocalUriHandler.current
            Text(
                stringResource(R.string.netlog_invisible_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TextButton(
                onClick = { runCatching { uri.openUri(DOCS_GOOGLE_PARTS) } },
                modifier = Modifier.padding(horizontal = 4.dp),
            ) { Text(stringResource(R.string.netlog_invisible_link)) }
        }
        Options(repository, settings, hasRows = data.rows.isNotEmpty(), onExport = {
            scope.launch { export(context, data.rows, looks) }
        })
    }

    open?.let { entry ->
        NetRequestSheet(
            entry = entry,
            look = looks.getValue(entry.source),
            ownServer = entry.host in own,
            onDismiss = { open = null },
            onNavigate = { route ->
                open = null
                onNavigate(route)
            },
        )
    }
}

/** What the screen reads from the log at one moment. */
internal data class NetSnapshot(
    val rows: List<NetEntry>,
    val days: List<NetDay>,
    val firstSeen: Map<String, Int>,
    val today: Int,
    val since: Long,
) {
    companion object {
        fun read() = NetSnapshot(NetLog.rows(), NetLog.days(), NetLog.firstSeen(), NetLog.today(), NetLog.since)
    }
}

// ---- right now -------------------------------------------------------------

@Composable
private fun LiveLine(
    latest: com.wasimaster.wmkeyboard.core.netlog.NetCall?,
    busy: Int,
    lastMillis: Long?,
    looks: Map<NetSource, NetSourceLook>,
) {
    // A clock for the relative time, so "a minute ago" keeps moving while the
    // screen is open and nothing new arrives.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val active = latest != null
    val color = if (active) latest?.let { looks.getValue(it.source).accent } ?: LiveGreen else LiveGreen
    val text = when {
        latest != null && busy > 1 -> pluralStringResource(R.plurals.netlog_live_busy_many, busy, busy)
        latest != null -> stringResource(
            R.string.netlog_live_busy,
            stringResource(looks.getValue(latest.source).label),
            latest.host,
        )
        lastMillis != null -> stringResource(
            R.string.netlog_live_idle,
            DateUtils.getRelativeTimeSpanString(lastMillis, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
        else -> stringResource(R.string.netlog_live_never)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        PulseDot(color, pulsing = active)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val still = LocalReduceMotion.current || !pulsing
    val alpha = if (still) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "netlogPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "netlogPulseAlpha",
        ).value
    }
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

// ---- the period ------------------------------------------------------------

@Composable
private fun HeroCard(
    summary: NetSummary,
    looks: Map<NetSource, NetSourceLook>,
    period: NetPeriod,
    onPeriod: (NetPeriod) -> Unit,
) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val total = summary.total
    val size = Formatter.formatShortFileSize(context, total.bytes)
    val cut = size.lastIndexOf(' ')
    val amount = if (cut > 0) size.substring(0, cut) else size
    val unit = if (cut > 0) size.substring(cut + 1) else ""
    val requests = pluralStringResource(
        R.plurals.netlog_requests_count, total.requests.toQuantity(), numbers.format(total.requests),
    )
    val periodName = stringResource(periodLabel(period))
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ChoiceControl(
                    options = NetPeriod.entries.map { it to stringResource(periodLabel(it)) },
                    selected = period,
                    label = stringResource(R.string.netlog_period_label),
                ) { onPeriod(it) }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Text(amount, style = MaterialTheme.typography.displaySmall)
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            unit,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        requests,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                DirectionLine(total)
                NetActivityChart(
                    buckets = summary.buckets,
                    period = period,
                    colorOf = { looks.getValue(it).accent },
                    modifier = Modifier.padding(top = 16.dp).clearAndSetSemantics {
                        contentDescription = context.getString(R.string.netlog_chart_desc, periodName, requests, size)
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.netlog_typing_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Received, sent, and how many failed, on one line under the big number. */
@Composable
private fun DirectionLine(total: NetTally) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Direction(Icons.Outlined.ArrowDownward, stringResource(R.string.netlog_received_desc), Formatter.formatShortFileSize(context, total.bytesIn))
        Spacer(Modifier.width(12.dp))
        Direction(Icons.Outlined.ArrowUpward, stringResource(R.string.netlog_sent_desc), Formatter.formatShortFileSize(context, total.bytesOut))
        if (total.failures > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                pluralStringResource(R.plurals.netlog_failed_count, total.failures.toQuantity(), numbers.format(total.failures)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Direction(icon: ImageVector, desc: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- by feature, by server -------------------------------------------------

@Composable
private fun ByFeature(summary: NetSummary, looks: Map<NetSource, NetSourceLook>, onPick: (NetSource) -> Unit) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val peak = summary.sources.maxOfOrNull { maxOf(it.second.bytes, 1L) } ?: 1L
    var all by rememberSaveable { mutableStateOf(false) }
    val visible = if (all) summary.sources else summary.sources.take(FEATURES_SHOWN)
    SettingsGroup {
        for ((source, tally) in visible) {
            val look = looks.getValue(source)
            item {
                WmRow(
                    title = stringResource(look.label),
                    leading = { NetSourceTile(look) },
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(look.label), style = MaterialTheme.typography.bodyLarge)
                            if (source.background) {
                                Spacer(Modifier.width(6.dp))
                                Badge(stringResource(R.string.netlog_badge_auto), BadgeTone.WARNING)
                            }
                        }
                    },
                    supporting = {
                        ShareBar(
                            fraction = tally.bytes.toFloat() / peak,
                            color = look.accent,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    },
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Formatter.formatShortFileSize(context, tally.bytes), style = MaterialTheme.typography.labelLarge)
                            Text(
                                pluralStringResource(R.plurals.netlog_requests_count, tally.requests.toQuantity(), numbers.format(tally.requests)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onPick(source) },
                )
            }
        }
        if (summary.sources.size > FEATURES_SHOWN) {
            item {
                TextButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        if (all) {
                            stringResource(R.string.netlog_servers_less)
                        } else {
                            val more = summary.sources.size - FEATURES_SHOWN
                            pluralStringResource(R.plurals.netlog_features_more, more, more)
                        },
                    )
                }
            }
        }
    }
}

/** A thin bar showing one feature's share of the busiest one. */
@Composable
private fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color.copy(alpha = 0.14f), CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(MIN_SHARE, 1f))
                .height(4.dp)
                .background(color, CircleShape),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Servers(
    hosts: List<NetHost>,
    looks: Map<NetSource, NetSourceLook>,
    own: Set<String>,
    today: Int,
    sinceDay: Int,
    onPick: (String) -> Unit,
) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    var all by rememberSaveable { mutableStateOf(false) }
    val visible = if (all) hosts else hosts.take(SERVERS_SHOWN)
    SettingsGroup {
        for (host in visible) {
            val look = looks.getValue(host.source)
            val lan = isLocalHost(host.host)
            // Only once the log is older than the host: on a fresh log every
            // server is "new", which says nothing.
            val fresh = host.firstSeenDay != null && host.firstSeenDay > sinceDay &&
                today - host.firstSeenDay < NEW_DAYS
            item {
                WmRow(
                    title = host.host,
                    leading = { Monogram(host.host, look.accent) },
                    supporting = {
                        Column {
                            Text(
                                stringResource(
                                    R.string.netlog_server_subtitle,
                                    stringResource(look.label),
                                    pluralStringResource(R.plurals.netlog_requests_count, host.tally.requests.toQuantity(), numbers.format(host.tally.requests)),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (fresh || lan || host.host in own) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    if (fresh) Badge(stringResource(R.string.netlog_badge_new), BadgeTone.ACCENT)
                                    if (host.host in own) Badge(stringResource(R.string.netlog_badge_own), BadgeTone.GOOD)
                                    if (lan) Badge(stringResource(R.string.netlog_badge_lan), BadgeTone.NEUTRAL)
                                }
                            }
                        }
                    },
                    onClick = { onPick(host.host) },
                )
            }
        }
        if (hosts.size > SERVERS_SHOWN) {
            item {
                TextButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        if (all) {
                            stringResource(R.string.netlog_servers_less)
                        } else {
                            val more = hosts.size - SERVERS_SHOWN
                            pluralStringResource(R.plurals.netlog_servers_more, more, more)
                        },
                    )
                }
            }
        }
    }
}

/** A server's tile: its first letter, in the colour of the feature that uses it. No favicon: fetching one would be a request. */
@Composable
private fun Monogram(host: String, accent: Color) {
    val letter = host.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    WmIconTile(accent) {
        Text(letter, style = MaterialTheme.typography.titleMedium)
    }
}

// ---- the timeline ----------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(filter: NetFilter, looks: Map<NetSource, NetSourceLook>, onChange: (NetFilter) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
    ) {
        FilterChip(
            selected = filter.isEmpty,
            onClick = { onChange(NetFilter()) },
            label = { Text(stringResource(R.string.netlog_filter_all)) },
        )
        FilterChip(
            selected = filter.background,
            onClick = { onChange(filter.copy(background = !filter.background)) },
            label = { Text(stringResource(R.string.netlog_filter_background)) },
            leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        FilterChip(
            selected = filter.failed,
            onClick = { onChange(filter.copy(failed = !filter.failed)) },
            label = { Text(stringResource(R.string.netlog_filter_failed)) },
        )
        FilterChip(
            selected = filter.incognito,
            onClick = { onChange(filter.copy(incognito = !filter.incognito)) },
            label = { Text(stringResource(R.string.netlog_filter_incognito)) },
        )
        val only = filter.source?.let { stringResource(looks.getValue(it).label) } ?: filter.host
        if (only != null) {
            InputChip(
                selected = true,
                onClick = { onChange(filter.copy(source = null, host = null)) },
                label = { Text(stringResource(R.string.netlog_filter_only, only), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.netlog_filter_remove_desc),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun Timeline(
    rows: List<NetEntry>,
    filter: NetFilter,
    shown: Int,
    looks: Map<NetSource, NetSourceLook>,
    onMore: () -> Unit,
    onOpen: (NetEntry) -> Unit,
) {
    val kept = remember(rows, filter) { rows.filter(filter::keeps) }
    if (kept.isEmpty()) {
        CaptionText(stringResource(R.string.netlog_filter_empty))
        return
    }
    val zone = remember { TimeZone.getDefault() }
    val days = remember(kept, shown) {
        kept.take(shown).groupBy { localDay(it.lastMillis, zone) }.toList()
    }
    val today = remember { localDay(System.currentTimeMillis(), zone) }
    Column(Modifier.animateContentSize()) {
        for ((day, entries) in days) {
            SettingsGroup(dayLabel(day, today)) {
                for (entry in entries) {
                    item { RequestRow(entry, looks.getValue(entry.source)) { onOpen(entry) } }
                }
            }
        }
        if (kept.size > shown) {
            TextButton(onClick = onMore, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.netlog_show_more))
            }
        }
    }
}

@Composable
private fun RequestRow(entry: NetEntry, look: NetSourceLook, onClick: () -> Unit) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val time = remember(entry.lastMillis) {
        android.text.format.DateFormat.getTimeFormat(context).format(Date(entry.lastMillis))
    }
    val label = stringResource(look.label)
    val size = Formatter.formatShortFileSize(context, entry.bytesIn + entry.bytesOut)
    val requests = pluralStringResource(R.plurals.netlog_requests_count, entry.count, numbers.format(entry.count.toLong()))
    val spoken = stringResource(R.string.netlog_row_desc, label, requests, entry.host, size, time)
    WmRow(
        title = label,
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        leading = { NetSourceTile(look) },
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.netlog_row_times, numbers.format(entry.count.toLong())),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.background) MiniGlyph(Icons.Outlined.Schedule)
                if (entry.incognito) MiniGlyph(IncognitoIcon)
                if (isLocalHost(entry.host)) MiniGlyph(Icons.Outlined.Lan)
            }
        },
        supporting = {
            Text(
                addressLine(entry),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                if (entry.failed) {
                    Badge(failureWords(entry), BadgeTone.ERROR)
                } else {
                    Text(size, style = MaterialTheme.typography.labelLarge)
                }
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun MiniGlyph(icon: ImageVector) {
    Spacer(Modifier.width(4.dp))
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** `GET host/route`, with the port when it is not the scheme's own. */
internal fun addressLine(entry: NetEntry): String = buildString {
    append(entry.method).append(' ').append(entry.host)
    if (entry.port > 0) append(':').append(entry.port)
    entry.route?.let(::append)
}

/** A failure in a few words: the status code, or what went wrong before one arrived. */
@Composable
internal fun failureWords(entry: NetEntry): String = when {
    entry.error == null -> stringResource(R.string.netlog_status_code, entry.status)
    entry.error == "SocketTimeoutException" -> stringResource(R.string.netlog_error_timeout)
    entry.error == "UnknownHostException" -> stringResource(R.string.netlog_error_offline)
    entry.error == "ConnectException" || entry.error == "NoRouteToHostException" ->
        stringResource(R.string.netlog_error_refused)
    entry.error?.startsWith("SSL") == true -> stringResource(R.string.netlog_error_tls)
    entry.error == "CancellationException" || entry.error == "InterruptedIOException" ||
        entry.error == "JobCancellationException" -> stringResource(R.string.netlog_error_cancelled)
    entry.status >= 400 -> stringResource(R.string.netlog_status_code, entry.status)
    else -> stringResource(R.string.netlog_error_failed)
}

// ---- empty, options --------------------------------------------------------

@Composable
private fun EmptyCard(since: Long) {
    val date = remember(since) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(since)) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            WmIconTile(LiveGreen, size = 64.dp) {
                Icon(Icons.Outlined.CloudDone, contentDescription = null, modifier = Modifier.size(34.dp))
            }
            Text(
                stringResource(R.string.netlog_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.netlog_empty_body, date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Options(
    repository: SettingsRepository,
    settings: LiveSettings,
    hasRows: Boolean,
    onExport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSettingsSnackbar.current
    val cleared = stringResource(R.string.netlog_cleared)
    val undo = stringResource(CommonR.string.common_undo)
    SettingsGroup(stringResource(R.string.netlog_options_section)) {
        item {
            ToggleSetting(
                R.string.netlog_keep_title,
                stringResource(R.string.netlog_keep_subtitle),
                settings.watch { it.networkLog.keep },
                info = stringResource(R.string.netlog_keep_info),
                default = SettingsDefaults.networkLog.keep,
            ) { scope.launch { repository.setNetworkLogKeep(it) } }
        }
        item {
            ToggleSetting(
                R.string.netlog_keyboard_dot_title,
                stringResource(R.string.netlog_keyboard_dot_subtitle),
                settings.watch { it.networkLog.showOnKeyboard },
                default = SettingsDefaults.networkLog.showOnKeyboard,
                info = stringResource(R.string.netlog_keyboard_dot_info),
            ) { scope.launch { repository.setNetworkLogOnKeyboard(it) } }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                FilledTonalButton(onClick = onExport, enabled = hasRows, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(CommonR.string.common_export), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
                OutlinedButton(
                    enabled = hasRows,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val gone = NetLog.clear()
                        snackbar?.undo(cleared, undo) { NetLog.restore(gone) }
                    },
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(CommonR.string.common_clear), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
    }
}

// ---- small pieces ----------------------------------------------------------

internal enum class BadgeTone { ACCENT, GOOD, WARNING, ERROR, NEUTRAL }

/** A small rounded label: "New", "Your server", a failure. */
@Composable
internal fun Badge(text: String, tone: BadgeTone) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        BadgeTone.ACCENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeTone.GOOD -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BadgeTone.WARNING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BadgeTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        BadgeTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun periodLabel(period: NetPeriod): Int = when (period) {
    NetPeriod.TODAY -> R.string.netlog_period_today
    NetPeriod.WEEK -> R.string.netlog_period_week
    NetPeriod.MONTH -> R.string.netlog_period_month
}

@Composable
private fun dayLabel(day: Int, today: Int): String = when (day) {
    today -> stringResource(R.string.netlog_day_today)
    today - 1 -> stringResource(R.string.netlog_day_yesterday)
    else -> remember(day) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(day * DAY_MS))
    }
}

internal fun localDay(millis: Long, zone: TimeZone): Int = Math.floorDiv(millis + zone.getOffset(millis), DAY_MS).toInt()

/** Hosts the user typed in themselves: self-hosted services and backup servers. */
private fun ownHosts(settings: KeyboardSettings): Set<String> {
    val urls = buildList {
        with(settings.selfHosted) {
            add(libreTranslateUrl)
            add(searxUrl)
            add(commonsUrl)
            addAll(endpoints.values)
        }
        with(settings.ai) {
            add(ollamaUrl)
            add(lmStudioUrl)
            add(compatibleUrl)
        }
        add(settings.whisper.serverUrl)
        add(settings.translate.deepl.endpoint)
        add(settings.translate.server.url)
        // Every server set up in a tool's provider list.
        addAll(settings.providerCredentials().map { it.url })
        // Every backup location's server, not only the one the screen had
        // before there could be several.
        for (location in settings.autoBackup.locations) {
            add(location.webDavUrl)
            add(location.s3.endpoint)
        }
    }
    val ftpHosts = settings.autoBackup.locations.mapNotNull { location ->
        location.ftp.host.trim().lowercase().takeIf { it.isNotEmpty() }
    }
    return (urls.mapNotNull(::hostOf) + ftpHosts).toSet()
}

/** Plural quantity for a count that may exceed an Int. */
private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * Writes the rows as CSV and hands the file to the share sheet. A file rather
 * than EXTRA_TEXT for the debug log's reason: two thousand rows is more than a
 * share intent reliably carries. The column names are data, not language.
 */
private suspend fun export(context: Context, rows: List<NetEntry>, looks: Map<NetSource, NetSourceLook>) {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "wmkeyboard-network-log.csv")
            file.writeText(csv(rows, looks, context))
            FileProvider.getUriForFile(context, "${context.packageName}.clipboard", file)
        }.getOrNull()
    }
    if (uri == null) {
        Toast.makeText(context, context.getString(CommonR.string.common_error_generic), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    AppShareSheet.start(context, intent, context.getString(R.string.netlog_export_chooser))
}

internal fun csv(rows: List<NetEntry>, looks: Map<NetSource, NetSourceLook>, context: Context): String = buildString {
    appendLine("first,last,count,feature,method,scheme,host,port,route,status,error,bytes_in,bytes_out,duration_ms,background,incognito")
    val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
    for (row in rows) {
        val fields = listOf(
            iso.format(Date(row.firstMillis)),
            iso.format(Date(row.lastMillis)),
            row.count.toString(),
            context.getString(looks.getValue(row.source).label),
            row.method,
            row.scheme,
            row.host,
            if (row.port > 0) row.port.toString() else "",
            row.route.orEmpty(),
            if (row.status > 0) row.status.toString() else "",
            row.error.orEmpty(),
            row.bytesIn.toString(),
            row.bytesOut.toString(),
            row.durationMs.toString(),
            row.background.toString(),
            row.incognito.toString(),
        )
        appendLine(fields.joinToString(",") { field -> csvField(field) })
    }
}

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

internal val IncognitoIcon: ImageVector get() = Icons.Outlined.VisibilityOff
private val LiveGreen = Color(0xFF1D9E75)
private const val PAGE = 50
private const val SERVERS_SHOWN = 5
private const val FEATURES_SHOWN = 6
private const val NEW_DAYS = 7
private const val MIN_SHARE = 0.03f
private const val TICK_MS = 30_000L
private const val PULSE_MS = 700
private const val DAY_MS = 86_400_000L
private const val DOCS_GOOGLE_PARTS = "$DOCS_URL/privacy/network/#google-components-and-what-they-report"

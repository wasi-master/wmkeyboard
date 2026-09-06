package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.debug.LogEntry
import com.wasimaster.wmkeyboard.core.debug.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads back what the keyboard recorded about itself, and hands it to whoever
 * asked for it.
 *
 * The reason this screen exists rather than "run adb logcat": a keyboard's
 * failures are invisible ones. The IME dies and Android quietly swaps in another
 * keyboard, a panel comes up empty, a model does not load — none of it reaches
 * the user as something they can quote in a bug report, and pulling a logcat off
 * a phone needs a computer and developer mode.
 *
 * Two sources, because they answer different questions. The app log is what the
 * keyboard chose to record — lifecycle, failures it handled — and never contains
 * anything typed. The system log is this process's own Android log, which
 * catches what the app never saw: a library's stack trace, the framework's
 * complaints about our window. That one is the whole process's output and is
 * shown behind its own switch, with a warning, because we cannot promise what a
 * dependency decided to print into it.
 */
@Composable
internal fun DebugLogScreen() {
    val context = LocalContext.current
    var showSystemLog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    // Bumped to re-read the log after a clear, since neither store is a Flow.
    var revision by remember { mutableIntStateOf(0) }

    val entries by produceState(emptyList<LogEntry>(), revision) {
        value = DebugLog.snapshot().asReversed()
    }
    val crashes by produceState("", revision) {
        value = withContext(Dispatchers.IO) { DebugLog.crashes() }
    }
    val systemLog by produceState("", showSystemLog, revision) {
        value = if (showSystemLog) withContext(Dispatchers.IO) { DebugLog.systemLog() } else ""
    }

    // The three reads above are what a refresh re-runs; the spinner stops when
    // they have all landed.
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(entries, crashes, systemLog) { refreshing = false }
    RegisterPullRefresh(refreshing) {
        refreshing = true
        revision++
    }

    val shown = remember(entries, query, minLevel) {
        entries.filter { it.level.ordinal >= minLevel.ordinal }
            .filter {
                query.isBlank() ||
                    it.message.contains(query, ignoreCase = true) ||
                    it.tag.contains(query, ignoreCase = true)
            }
    }


    SettingsGroup(
        stringResource(R.string.shell_debug_log_report_title),
        info = stringResource(R.string.shell_debug_log_intro_body),
    ) {
        item {
            NavRow(
                R.string.shell_debug_log_share_title,
                stringResource(R.string.shell_debug_log_share_subtitle),
            ) { shareReport(context, showSystemLog, systemLog) }
        }
        item {
            NavRow(
                R.string.shell_debug_log_copy_title,
                stringResource(R.string.shell_debug_log_copy_subtitle),
            ) { copyReport(context, showSystemLog, systemLog) }
        }
        item {
            ToggleSetting(
                R.string.shell_debug_log_system_title,
                stringResource(R.string.shell_debug_log_system_subtitle),
                showSystemLog,
                info = stringResource(R.string.shell_debug_log_system_info),
            ) { showSystemLog = it }
        }
    }

    // Only in a build made with -Pwmkb.enableCrashScreen=true. The row is here
    // rather than hidden behind a gesture because the whole point of such a
    // build is to be handed to someone whose phone is failing, and whoever
    // builds it wants to confirm the report screen actually comes up before
    // sending the APK anywhere.
    if (BuildConfig.ENABLE_CRASH_SCREEN) {
        SettingsGroup(
            stringResource(R.string.shell_debug_log_diagnostic_title),
            info = stringResource(R.string.shell_debug_log_diagnostic_body),
        ) {
            item {
                NavRow(
                    R.string.shell_debug_log_crash_test_title,
                    stringResource(R.string.shell_debug_log_crash_test_subtitle),
                ) { error("Crash screen test, triggered from settings") }
            }
        }
    }

    if (crashes.isNotBlank()) {
        SettingsGroup(stringResource(R.string.shell_debug_log_crashes_title)) {
            item {
                LogBlock(crashes)
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = {
                        DebugLog.clearCrashes()
                        revision++
                    }) { Text(stringResource(R.string.shell_debug_log_delete_crashes_action)) }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.shell_debug_log_app_log_title)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.shell_debug_log_filter_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = minLevel == level,
                        onClick = { minLevel = level },
                        label = { Text(stringResource(levelLabelRes(level))) },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                CaptionText(
                    stringResource(
                        if (entries.isEmpty()) R.string.shell_debug_log_empty
                        else R.string.shell_debug_log_filter_empty,
                    ),
                )
            }
        }
    }

    // Outside SettingsGroup: the entries are their own scrolling list, and
    // wrapping a few hundred rows in cards would make each one a separate
    // surface for no gain.
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .padding(horizontal = 16.dp),
    ) {
        items(shown) { entry -> LogRow(entry, onCopy = { copyLine(context, it) }) }
    }

    if (showSystemLog) {
        SettingsGroup(stringResource(R.string.shell_debug_log_system_log_title)) {
            item {
                val unavailable = stringResource(R.string.shell_debug_log_system_unavailable)
                LogBlock(systemLog.ifBlank { unavailable })
            }
        }
    }

    SettingsGroup {
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    DebugLog.clear()
                    revision++
                }) { Text(stringResource(R.string.shell_debug_log_delete_app_log_action)) }
            }
        }
    }
}

/**
 * The name of one log level on its filter chip.
 *
 * The enum constant used to be title-cased in code. That gave the chips English
 * names on every device, and `replaceFirstChar` follows the device language
 * rather than the language of the word, so a Turkish device turned "Info" into
 * "İnfo".
 */
@StringRes
private fun levelLabelRes(level: LogLevel): Int = when (level) {
    LogLevel.DEBUG -> R.string.shell_debug_log_level_debug_label
    LogLevel.INFO -> R.string.shell_debug_log_level_info_label
    LogLevel.WARN -> R.string.shell_debug_log_level_warn_label
    LogLevel.ERROR -> R.string.shell_debug_log_level_error_label
}

/**
 * One log line. Holding it copies that line alone — the whole report is two
 * taps away above, but a bug report usually turns on one line, and picking it
 * out of a shared file by hand is worse than holding it here.
 */
@Composable
private fun LogRow(entry: LogEntry, onCopy: (String) -> Unit) {
    val color = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCopy("${timeOf(entry.timeMillis)} ${entry.level} ${entry.tag}: ${entry.message}")
                },
            )
            .padding(vertical = 3.dp),
    ) {
        Text(
            "${timeOf(entry.timeMillis)}  ${entry.tag}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            entry.message,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

/** A block of pre-formatted log text: monospaced, and scrolling both ways. */
@Composable
private fun LogBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

private fun timeOf(millis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date(millis))

private fun report(includeSystemLog: Boolean, systemLog: String): String = buildString {
    append(DebugLog.exportText())
    if (includeSystemLog && systemLog.isNotBlank()) {
        appendLine()
        appendLine("== system log ==")
        appendLine(systemLog)
    }
}

/** Puts one log line on the clipboard, for quoting into a bug report. */
private fun copyLine(context: Context, line: String) {
    val label = context.getString(R.string.shell_debug_log_report_subject)
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, line))
    }
    Toast.makeText(
        context,
        context.getString(R.string.shell_debug_log_line_copied_info),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun copyReport(context: Context, includeSystemLog: Boolean, systemLog: String) {
    val label = context.getString(R.string.shell_debug_log_report_subject)
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, report(includeSystemLog, systemLog)))
    }
    Toast.makeText(
        context,
        context.getString(R.string.shell_debug_log_copied_info),
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * Shares the report as a file rather than as EXTRA_TEXT: a log runs to tens of
 * kilobytes, and a share intent that size is silently dropped by half the apps
 * that would receive it.
 */
private fun shareReport(context: Context, includeSystemLog: Boolean, systemLog: String) {
    val uri = runCatching {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "wmkeyboard-log.txt")
        file.writeText(report(includeSystemLog, systemLog))
        FileProvider.getUriForFile(context, "${context.packageName}.clipboard", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(
            context,
            context.getString(R.string.shell_debug_log_write_error),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(
            Intent.EXTRA_SUBJECT,
            context.getString(R.string.shell_debug_log_report_subject),
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.shell_debug_log_share_title),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

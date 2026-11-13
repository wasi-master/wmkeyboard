package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.resources.ResourceProbe
import com.wasimaster.wmkeyboard.core.debug.FieldDebugInfo
import com.wasimaster.wmkeyboard.core.debug.KeyboardDebugSnapshot

/**
 * About › Debug info (FUTO U3): the report the keyboard's Debug info action
 * took, the focused field's EditorInfo decoded with the keyboard's state, the
 * screen and memory, with Copy for a bug report.
 *
 * The report lives in memory only ([KeyboardDebugSnapshot]); opened from
 * settings before the action has run, the screen says how to take one and
 * shows what the app can tell from here (the version, the device, the screen
 * and memory), which is still worth pasting into an issue. Nothing on it is
 * text from a field.
 */
@Composable
internal fun DebugInfoScreen() {
    val context = LocalContext.current
    val taken = KeyboardDebugSnapshot.last
    val report = remember(taken) { taken?.text ?: appDebugInfo(context) }
    SettingsGroup(
        stringResource(if (taken != null) R.string.debug_info_from_keyboard else R.string.debug_info_from_app),
        info = stringResource(R.string.debug_info_info),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (taken != null) {
                        stringResource(
                            R.string.debug_info_taken_at,
                            DateUtils.getRelativeTimeSpanString(taken.atMillis).toString(),
                        )
                    } else {
                        stringResource(R.string.debug_info_how)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copyReport(context, report) }) {
                        Text(stringResource(R.string.debug_info_copy))
                    }
                }
            }
        }
    }
}

/** What the settings app can tell without the keyboard: the build, the device, the screen and memory. */
private fun appDebugInfo(context: Context): String {
    val config = context.resources.configuration
    val metrics = context.resources.displayMetrics
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    val memory = ResourceProbe.readMemory()
    return FieldDebugInfo.render(
        listOf(
            FieldDebugInfo.Section(
                "App",
                listOf(
                    "App version" to (version ?: "unknown"),
                    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                ),
            ),
            FieldDebugInfo.Section(
                "Screen",
                listOf(
                    "Size" to "${config.screenWidthDp}×${config.screenHeightDp} dp, ${metrics.widthPixels}×${metrics.heightPixels} px",
                    "Smallest width" to "${config.smallestScreenWidthDp} dp",
                    "Density" to "${metrics.density} (${metrics.densityDpi} dpi)",
                    "Font scale" to config.fontScale.toString(),
                    "Orientation" to
                        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
                ),
            ),
            FieldDebugInfo.Section(
                "Memory",
                listOf(
                    "Total PSS" to "${memory.totalPssKb / 1024} MB",
                    "Java heap" to "${memory.javaUsedBytes shr 20} of ${memory.javaMaxBytes shr 20} MB",
                    "Native heap" to "${memory.nativeAllocatedBytes shr 20} MB",
                    "Graphics" to "${memory.graphicsKb / 1024} MB",
                ),
            ),
        ),
    )
}

private fun copyReport(context: Context, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.about_debug_info_title), text))
    }
    Toast.makeText(context, context.getString(R.string.debug_info_copied), Toast.LENGTH_SHORT).show()
}

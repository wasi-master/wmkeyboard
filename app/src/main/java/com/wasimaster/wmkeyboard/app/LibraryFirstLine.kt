package com.wasimaster.wmkeyboard.app

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.ModuleState

/**
 * The line a feature's download row adds about the native library its model
 * runs on, while that library is not on the phone. One press then fetches two
 * files, the library and the model, and a row that offered only the model's
 * size let the first arrive unexplained (the handwriting report: 26 MB
 * offered, then 6.9 MB and 26 MB fetched).
 *
 * Before the download: that the library comes too, with its size where the
 * build knows it. While the library is the file coming in: its own bytes.
 * Null once it is here, on a build that carries it in the APK, and where a
 * consent or import banner already says why it cannot come.
 */
@Composable
internal fun libraryFirstLine(gate: ModuleGate): String? {
    val context = LocalContext.current
    val state by gate.state.collectAsState()
    return when (val current = state) {
        ModuleState.Installed -> null
        is ModuleState.Installing -> if (current.totalBytes > 0L) {
            stringResource(
                R.string.feature_libraries_step_sized_line,
                Formatter.formatShortFileSize(context, current.bytes),
                Formatter.formatShortFileSize(context, current.totalBytes),
            )
        } else {
            stringResource(R.string.feature_libraries_step_line)
        }
        is ModuleState.Missing -> when {
            !gate.onDemand || current.consent != null -> null
            gate.bytesToFetch > 0L -> stringResource(
                R.string.feature_libraries_first_sized_line,
                Formatter.formatShortFileSize(context, gate.bytesToFetch),
            )
            else -> stringResource(R.string.feature_libraries_first_line)
        }
    }
}

/** [text] with [line] under it, or [text] alone when there is no line. */
internal fun withLibraryLine(text: String, line: String?): String = if (line == null) text else "$text\n$line"

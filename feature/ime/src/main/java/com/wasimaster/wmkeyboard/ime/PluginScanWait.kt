package com.wasimaster.wmkeyboard.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the QR scanner on screen was opened by a plugin's `scan.qr`. The
 * scanner then hands the first code it reads straight back, as its
 * auto-insert setting does for the field: the user opened it for the plugin,
 * and a second tap on Insert only stood between them and the answer.
 * Main thread.
 */
internal object PluginScanWait {
    var active by mutableStateOf(false)
}

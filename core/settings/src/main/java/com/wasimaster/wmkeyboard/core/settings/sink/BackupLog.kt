package com.wasimaster.wmkeyboard.core.settings.sink

import android.util.Log
import com.wasimaster.wmkeyboard.core.debug.DebugLog

/**
 * Debug trace for the backup destinations: `adb logcat -s WMBackup`, and the
 * app log under About › Diagnostics. The second is the one a user can reach
 * without a computer, and a failed backup used to leave it empty (#411).
 *
 * Status codes, error reasons and sizes only. Never a token, an authorization
 * code or a verifier. Swallows the "method not mocked" a plain JVM unit test
 * gets from android.util.Log.
 */
object BackupLog {
    const val TAG = "WMBackup"

    fun d(message: String) {
        runCatching { Log.d(TAG, message) }
        DebugLog.d(TAG, message)
    }

    fun w(message: String, failure: Throwable? = null) {
        runCatching { Log.w(TAG, message, failure) }
        DebugLog.w(TAG, if (failure == null) message else "$message: ${failure.chain()}")
    }

    /**
     * The cause chain on one line: a [BackupSinkException] alone is just its
     * reason. Running out of memory adds where it happened, because the message
     * alone says only how much was asked for (#1080).
     */
    private fun Throwable.chain(): String =
        generateSequence(this) { it.cause }.take(4)
            .joinToString(" ← ") { failure ->
                val line = "${failure::class.java.simpleName}: ${failure.message.orEmpty()}"
                if (failure !is OutOfMemoryError) line
                else line + failure.stackTrace.take(OOM_FRAMES).joinToString("") { " at $it" }
            }

    private const val OOM_FRAMES = 8
}

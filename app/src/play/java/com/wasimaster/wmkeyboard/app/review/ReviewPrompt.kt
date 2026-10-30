package com.wasimaster.wmkeyboard.app.review

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.play.core.review.ReviewManagerFactory
import com.wasimaster.wmkeyboard.app.updates.AppUpdater
import com.wasimaster.wmkeyboard.app.updates.UpdateState
import com.wasimaster.wmkeyboard.app.updates.findActivity
import com.wasimaster.wmkeyboard.app.updates.installedByPlay
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens Play's rating card once [ReviewPolicy] says the time has come.
 *
 * In the settings activity because the card needs an activity, and the keyboard
 * has none. So someone who never opens the settings app is never asked, which
 * is the right way round: a dialog must not land on top of what they are typing.
 *
 * Nothing is asked first and no button leads here. Play forbids a question
 * before the card, and a "Rate us" button that runs into the quota does nothing
 * when pressed. Play never reports whether the card appeared or what was chosen,
 * so a failure stays silent and the ask counts as soon as it is launched.
 */
@Composable
internal fun ReviewPrompt(updater: AppUpdater) {
    val activity = LocalContext.current.findActivity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activity, updater, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Long enough for the update check to answer, whose dialog goes first.
            delay(SETTLE_MILLIS)
            if (!updater.state.value.mayOpenDialog() && readyToAsk(activity)) {
                ask(activity, lifecycle)
            }
        }
    }
}

private fun UpdateState.mayOpenDialog(): Boolean = when (this) {
    is UpdateState.Available -> !dismissed
    is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> true
    else -> false
}

private suspend fun readyToAsk(context: Context): Boolean = withContext(Dispatchers.IO) {
    if (!context.installedByPlay()) return@withContext false
    val installedAt = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrNull() ?: return@withContext false
    // The cheap half first: for four months after an ask, the file stays unread.
    val now = System.currentTimeMillis()
    val lastAskedAt = context.reviewPrefs().getLong(KEY_LAST_ASKED_AT, 0L)
    if (!ReviewPolicy.shouldAsk(Long.MAX_VALUE, installedAt, lastAskedAt, now)) return@withContext false
    // This device's own count, which stays where it is while statistics are off.
    val words = TypingStats(File(context.filesDir, TypingStats.FILE_PATH)).lifetime().words
    ReviewPolicy.shouldAsk(words, installedAt, lastAskedAt, now)
}

private fun ask(activity: Activity, lifecycle: Lifecycle) {
    val manager = ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnCompleteListener { request ->
        if (!request.isSuccessful) {
            DebugLog.w(TAG, "review request failed: ${request.exception?.message.orEmpty()}")
            return@addOnCompleteListener
        }
        // The answer came over IPC, and the user may have left in the meantime.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@addOnCompleteListener
        activity.reviewPrefs().edit { putLong(KEY_LAST_ASKED_AT, System.currentTimeMillis()) }
        manager.launchReviewFlow(activity, request.result)
    }
}

/** Its own file for the reasons [com.wasimaster.wmkeyboard.app.updates.UpdatePrefs] gives. */
private fun Context.reviewPrefs() =
    applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

private const val SETTLE_MILLIS = 3_000L
private const val PREFS_FILE = "app_review"
private const val KEY_LAST_ASKED_AT = "last_asked_at"
private const val TAG = "AppReview"

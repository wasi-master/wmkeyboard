package com.wasimaster.wmkeyboard.app.language

import android.app.Activity
import android.os.Build
import android.widget.Toast
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.modules.ModuleFailure
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.util.Locale

/**
 * Play delivers interface languages as splits, and installs only the ones the
 * phone is set to. Android 13 and up fetch a missing one themselves when the
 * app language changes. Below that the app has to ask, which is what this file
 * does, and each settings screen has to be told about a split that arrived
 * after the process started. The non-Play version is in `src/noplay/java`.
 */
fun appLanguageSplitCompat(activity: Activity) {
    SplitCompat.installActivity(activity)
}

/**
 * Makes sure the resources for [tag] are on the phone, then runs [onReady].
 *
 * It runs [onReady] on failure too: the app then shows English, which every
 * string has, and the choice stays stored, so the next start tries again.
 */
fun fetchAppLanguage(activity: Activity, tag: String?, onReady: () -> Unit) {
    if (tag == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        onReady()
        return
    }
    val locale = Locale.forLanguageTag(tag)
    val manager = SplitInstallManagerFactory.create(activity)
    if (locale.language in manager.installedLanguages) {
        onReady()
        return
    }
    var finished = false
    lateinit var listener: SplitInstallStateUpdatedListener
    val finish = {
        if (!finished) {
            finished = true
            manager.unregisterListener(listener)
            onReady()
        }
    }
    // Why Play would not bring the language, said over the screen that
    // asked, where it used to fall back to English without a word.
    val refused = { code: Int ->
        DebugLog.w(TAG, "language $tag: Play refused the install, error $code")
        val reason = ModuleFailure.message(ModuleState.Missing(failed = true, error = code), CommonR.string.common_error_network)
        val text = if (reason.arg.isEmpty()) activity.getString(reason.res) else activity.getString(reason.res, reason.arg)
        if (!activity.isFinishing) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        finish()
    }
    listener = SplitInstallStateUpdatedListener { state ->
        if (locale.language !in state.languages()) return@SplitInstallStateUpdatedListener
        when (state.status()) {
            SplitInstallSessionStatus.INSTALLED,
            SplitInstallSessionStatus.CANCELED,
            -> finish()
            SplitInstallSessionStatus.FAILED -> refused(state.errorCode())
            // A large download or mobile data: there is a screen to ask on
            // here, so Play's own question goes up, and its answer arrives
            // as the next state of this session.
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
                val asked = runCatching { manager.startConfirmationDialogForResult(state, activity, CONFIRM_LANGUAGE) }
                if (asked.isFailure) finish()
            }
            else -> Unit
        }
    }
    manager.registerListener(listener)
    manager.startInstall(SplitInstallRequest.newBuilder().addLanguage(locale).build())
        .addOnFailureListener { e -> refused((e as? SplitInstallException)?.errorCode ?: ModuleFailure.PLAY_INTERNAL_ERROR) }
}

/** Any code: the session's own updates say what the user chose. */
private const val CONFIRM_LANGUAGE = 4531

private const val TAG = "modules"

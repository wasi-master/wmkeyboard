package com.wasimaster.wmkeyboard.app.translate

import android.app.Application
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.wasimaster.wmkeyboard.app.modules.PLAY_ERROR_UNKNOWN
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.translate.TranslateModule
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleGate
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play-channel delivery of the on-demand `:feature:translate` module. This
 * directory replaces `src/noplay/java` when `wmkb.enablePlayStore` is on —
 * the same seam as LlmDelivery beside it.
 */

/** The split's name: the last segment of the `:feature:translate` project path. */
private const val TRANSLATE_MODULE = "translate"

/**
 * Publishes the SplitInstall-backed [TranslateModuleGate] so
 * `OnDeviceTranslator` can see and fetch its runtime. Called once from
 * [WMApplication.onCreate]; nothing here touches the network by itself.
 *
 * Unlike the AI module's gate this one reports progress, because the panel
 * that asks for it stays open while it arrives. The listener is registered
 * for the life of the process: it is the only thing that hears the install
 * finish, and the panel that started it may be long gone by then.
 */
fun installTranslateDelivery(app: Application) {
    val manager = SplitInstallManagerFactory.create(app)
    val installed = { TRANSLATE_MODULE in manager.installedModules }
    val state = MutableStateFlow<TranslateModuleState>(
        if (installed()) TranslateModuleState.Installed else TranslateModuleState.Missing(),
    )

    manager.registerListener { session: SplitInstallSessionState ->
        if (TRANSLATE_MODULE !in session.moduleNames()) return@registerListener
        state.value = when (session.status()) {
            SplitInstallSessionStatus.PENDING,
            SplitInstallSessionStatus.DOWNLOADING,
            SplitInstallSessionStatus.DOWNLOADED,
            SplitInstallSessionStatus.INSTALLING,
            -> TranslateModuleState.Installing(session.bytesDownloaded(), session.totalBytesToDownload())

            SplitInstallSessionStatus.INSTALLED -> {
                // Once more, now that there is something new to see: the
                // install at attachBaseContext predates this split.
                SplitCompat.install(app)
                TranslateModuleState.Installed
            }

            // Play wants the user to confirm (a very large download, or no
            // Wi-Fi under the user's Play settings). A keyboard has no
            // activity to show that dialog from, so it reads as a failure
            // here and the settings screen's button is the place to retry.
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION ->
                failedWith(ModuleState.Missing(failed = true, confirm = true))
            SplitInstallSessionStatus.FAILED ->
                failedWith(ModuleState.Missing(failed = true, error = session.errorCode()))

            SplitInstallSessionStatus.CANCELING,
            SplitInstallSessionStatus.CANCELED,
            -> TranslateModuleState.Missing()

            else -> state.value
        }
    }

    TranslateModule.gate = object : TranslateModuleGate {
        override val state: StateFlow<TranslateModuleState> = state

        override fun requestInstall() {
            if (installed()) {
                state.value = TranslateModuleState.Installed
                return
            }
            if (state.value is TranslateModuleState.Installing) return
            state.value = TranslateModuleState.Installing()
            // Re-requesting the module of an active session just returns that
            // session. A failure that never becomes a session (no Play Store,
            // no network) only reaches the failure listener, so it is mapped
            // there as well as above.
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(TRANSLATE_MODULE).build())
                .addOnFailureListener { e ->
                    val code = (e as? SplitInstallException)?.errorCode ?: PLAY_ERROR_UNKNOWN
                    state.value = failedWith(ModuleState.Missing(failed = true, error = code))
                }
        }
    }
}

/** A failed fetch that keeps Play's reason, for [com.wasimaster.wmkeyboard.core.modules.ModuleFailure]. */
private fun failedWith(cause: ModuleState.Missing) = TranslateModuleState.Missing(failed = true, cause = cause)

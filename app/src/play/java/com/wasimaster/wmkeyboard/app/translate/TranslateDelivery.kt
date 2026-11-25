package com.wasimaster.wmkeyboard.app.translate

import android.app.Application
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.wasimaster.wmkeyboard.app.modules.PlayRequests
import com.wasimaster.wmkeyboard.core.modules.ModuleFailure
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
    val refused = { code: Int -> state.value = failedWith(ModuleState.Missing(failed = true, error = code)) }

    manager.registerListener { session: SplitInstallSessionState ->
        if (TRANSLATE_MODULE !in session.moduleNames()) return@registerListener
        state.value = when (session.status()) {
            SplitInstallSessionStatus.PENDING,
            SplitInstallSessionStatus.DOWNLOADING,
            SplitInstallSessionStatus.DOWNLOADED,
            SplitInstallSessionStatus.INSTALLING,
            -> TranslateModuleState.Installing(session.bytesDownloaded(), session.totalBytesToDownload())

            SplitInstallSessionStatus.INSTALLED -> {
                PlayRequests.installed(TRANSLATE_MODULE)
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
            // One that may pass by itself is asked again first ([PlayRequests]).
            SplitInstallSessionStatus.FAILED ->
                if (PlayRequests.failed(manager, TRANSLATE_MODULE, session.errorCode(), refused)) {
                    TranslateModuleState.Installing()
                } else {
                    failedWith(ModuleState.Missing(failed = true, error = session.errorCode()))
                }

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
            // A refusal Play will only repeat keeps saying why (Telegram 12443);
            // Play is asked all the same, and a session it starts moves it on.
            val last = (state.value as? TranslateModuleState.Missing)?.cause
            val refusedForGood = last?.failed == true && ModuleFailure.playWillRefuseAgain(last.error)
            if (!refusedForGood && last?.confirm != true) state.value = TranslateModuleState.Installing()
            PlayRequests.request(manager, TRANSLATE_MODULE, refused)
        }
    }
}

/** A failed fetch that keeps Play's reason, for [com.wasimaster.wmkeyboard.core.modules.ModuleFailure]. */
private fun failedWith(cause: ModuleState.Missing) = TranslateModuleState.Missing(failed = true, cause = cause)

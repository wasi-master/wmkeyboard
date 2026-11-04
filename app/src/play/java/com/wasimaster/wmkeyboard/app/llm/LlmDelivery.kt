package com.wasimaster.wmkeyboard.app.llm

import android.app.Application
import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.localllm.LlmModule
import com.wasimaster.wmkeyboard.core.localllm.LlmModuleGate

/**
 * Play-channel delivery of the on-demand `:feature:llm` module. This
 * directory replaces `src/noplay/java` when `wmkb.enablePlayStore` is on —
 * the same seam that keeps every Play Core binary out of the F-Droid and
 * direct-download APKs (see NoPlayUpdater).
 */

/** The split's name: the last segment of the `:feature:llm` project path. */
private const val LLM_MODULE = "llm"

private const val TAG = "modules"

/**
 * Publishes the SplitInstall-backed [LlmModuleGate] so `LocalLlmEngine` can
 * see and fetch its runtime. Called once from [WMApplication.onCreate];
 * everything it does is lazy, nothing here touches the network by itself.
 */
fun installLlmDelivery(app: Application) {
    val manager = SplitInstallManagerFactory.create(app)
    // Play holds a download for the user's yes (an install Play did not make,
    // a large download on mobile data) and asks only over an activity. The
    // keyboard has none, so it says where the question will come instead of
    // "downloading" (Telegram, 4529); the settings app asks it on resume.
    var waiting = false
    manager.registerListener { session: SplitInstallSessionState ->
        if (LLM_MODULE !in session.moduleNames()) return@registerListener
        waiting = session.status() == SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION
        when (session.status()) {
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION ->
                DebugLog.w(TAG, "$LLM_MODULE: Play asks the user to confirm the download")
            // Play's own reason, in the diagnostics a report carries (#1091).
            SplitInstallSessionStatus.FAILED ->
                DebugLog.w(TAG, "$LLM_MODULE: Play install failed, error ${session.errorCode()}")
            else -> Unit
        }
    }
    LlmModule.gate = object : LlmModuleGate {
        override fun installed() = LLM_MODULE in manager.installedModules

        override fun requestInstall() {
            if (installed()) return
            // Re-requesting the module of an active session just returns that
            // session. A failure (no network, no space) leaves
            // installedModules unchanged, so the next attempt retries rather
            // than wedging; the reason goes to the diagnostics.
            manager.startInstall(
                SplitInstallRequest.newBuilder().addModule(LLM_MODULE).build(),
            ).addOnFailureListener { e ->
                val code = (e as? SplitInstallException)?.errorCode
                DebugLog.w(TAG, "$LLM_MODULE: Play refused the install, error ${code ?: e.javaClass.simpleName}")
            }
        }

        override fun waitingForConfirmation() = waiting && !installed()
    }
}

/**
 * Lets this process load classes and native libraries from splits installed
 * after it started — without this, a freshly downloaded module would need an
 * app restart before [Class.forName] on the bridge succeeds. Called from
 * [WMApplication.attachBaseContext], which covers the IME service and the
 * settings activity alike (one process).
 */
fun llmSplitCompat(context: Context) {
    SplitCompat.install(context)
}

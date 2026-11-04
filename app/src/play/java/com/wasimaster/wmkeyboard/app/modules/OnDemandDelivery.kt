package com.wasimaster.wmkeyboard.app.modules

import android.app.Activity
import android.app.Application
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallHelper
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play-channel delivery of the on-demand modules that share one gate type:
 * every gate of native/gates.json with a `FeatureModules` property and a Play
 * module (`FeatureModules.playModules`), each a `:feature:<name>` split. This
 * directory replaces `src/noplay/java` when `wmkb.enablePlayStore` is on, the
 * same seam as LlmDelivery and TranslateDelivery beside it, and each module
 * here is driven exactly the way TranslateDelivery drives its own.
 */

/**
 * Publishes a SplitInstall-backed gate for each module into [FeatureModules].
 * Called once from [WMApplication.onCreate]; nothing here touches the
 * network by itself. One manager and one listener serve every module: the
 * listener is registered for the life of the process because it is the only
 * thing that hears an install finish, and whatever asked for it may be gone
 * by then.
 */
fun installOnDemandDelivery(app: Application) {
    val manager = SplitInstallManagerFactory.create(app)
    // A module switched off in this build is not in the bundle: :feature:npu
    // without wmkb.enableNpu, :feature:googlehome unless the build links the
    // Home APIs. Its gate stays the default; for the NPU, the "installed"
    // NpuRuntime then finds no assets behind, which reads as no NPU: never
    // reached anyway, because NpuSupport offers no NPU when ENABLE_NPU is off.
    val gates = FeatureModules.playModules.associateWith { PlayModuleGate(manager, it) }
    manager.registerListener { session: SplitInstallSessionState ->
        for (name in session.moduleNames()) gates[name]?.onSession(session)
        if (session.status() == SplitInstallSessionStatus.INSTALLED) {
            // Once more, now that there is something new to see: the install
            // at attachBaseContext predates this split.
            SplitCompat.install(app)
        }
    }
    for ((name, gate) in gates) FeatureModules.set(name, gate)
    // Licence agreements (FUTO Swipe's and NLLB's model weights) persist here too; Play
    // has no pins, so nothing else in NativeDelivery applies.
    NativeConsent.attach(app, needOptIn = false)
    // The library-only splits (`"play": "split"` in native/gates.json) hold
    // bare native libraries for classes in the base. A split installed after
    // this process started is not on the class loader's library path;
    // SplitInstallHelper looks there as well.
    FeatureModules.loadLibrary = { name -> SplitInstallHelper.loadLibrary(app, name) }
    // The Storage screen's row of modules: every installed one, the Translate
    // and LLM modules with their own delivery included.
    PlayModules.installed = { manager.installedModules }
    // Marked at once, so the rescan that follows the delete already shows
    // the module as on its way out; a refusal takes the mark back.
    PlayModules.uninstall = { names ->
        PlayModules.removing += names
        manager.deferredUninstall(names).addOnFailureListener { PlayModules.removing -= names.toSet() }
    }
    PlayModules.confirmPending = { activity -> confirmPlayDownloads(manager, activity) }
}

/**
 * Shows Play's own question for every module download it is holding for the
 * user's yes (Telegram, 4529). The keyboard asks for a module where it is
 * needed, the AI chat for its runtime say, but Play puts that question only
 * over an activity, and a keyboard has none: the download sat waiting and
 * the keyboard said "downloading" each time. Called when the settings app
 * comes to the front; a yes moves the session on to downloading, and the
 * listener above hears the rest.
 */
private fun confirmPlayDownloads(manager: SplitInstallManager, activity: Activity) {
    manager.sessionStates.addOnSuccessListener { sessions ->
        if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
        val waiting = sessions.firstOrNull { it.status() == SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION }
            ?: return@addOnSuccessListener
        DebugLog.i(TAG, "${waiting.moduleNames()}: asking the user to confirm Play's download")
        runCatching { manager.startConfirmationDialogForResult(waiting, activity, CONFIRM_REQUEST) }
            .onFailure { DebugLog.w(TAG, "Play's confirmation did not open: ${it.javaClass.simpleName}") }
    }
}

/** Any code: nothing reads the result, since the session's own updates say what the user chose. */
private const val CONFIRM_REQUEST = 4529

private class PlayModuleGate(
    private val manager: SplitInstallManager,
    private val module: String,
) : ModuleGate {

    private val installedNow: Boolean get() = module in manager.installedModules

    private val mutableState = MutableStateFlow<ModuleState>(
        if (installedNow) ModuleState.Installed else ModuleState.Missing(),
    )

    override val state: StateFlow<ModuleState> = mutableState

    override val onDemand: Boolean get() = true

    fun onSession(session: SplitInstallSessionState) {
        mutableState.value = when (session.status()) {
            SplitInstallSessionStatus.PENDING,
            SplitInstallSessionStatus.DOWNLOADING,
            SplitInstallSessionStatus.DOWNLOADED,
            SplitInstallSessionStatus.INSTALLING,
            -> ModuleState.Installing(session.bytesDownloaded(), session.totalBytesToDownload())

            SplitInstallSessionStatus.INSTALLED -> ModuleState.Installed

            // Play wants the user to confirm (an install Play did not make,
            // or a large download on mobile data). A keyboard has no activity
            // to show that dialog from, so it reads as a failure here, one
            // that says so: the settings app asks Play's question when it
            // next comes to the front ([confirmPlayDownloads]).
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
                DebugLog.w(TAG, "$module: Play asks the user to confirm the download")
                ModuleState.Missing(failed = true, confirm = true)
            }
            // Play's own reason, in the diagnostics a report carries (#1091):
            // without it a refused module reads only as "could not be fetched".
            SplitInstallSessionStatus.FAILED -> {
                DebugLog.w(TAG, "$module: Play install failed, error ${session.errorCode()}")
                ModuleState.Missing(failed = true)
            }

            SplitInstallSessionStatus.CANCELING,
            SplitInstallSessionStatus.CANCELED,
            -> ModuleState.Missing()

            else -> mutableState.value
        }
    }

    override fun requestInstall() {
        if (installedNow) {
            mutableState.value = ModuleState.Installed
            return
        }
        if (mutableState.value is ModuleState.Installing) return
        // A download Play holds for the user's yes still is after another
        // request, which only returns the same session: the state keeps
        // saying so rather than turning back into "downloading".
        val confirming = (mutableState.value as? ModuleState.Missing)?.confirm == true
        if (!confirming) mutableState.value = ModuleState.Installing()
        // Re-requesting the module of an active session just returns that
        // session. A failure that never becomes a session (no Play Store,
        // no network) only reaches the failure listener, so it is mapped
        // there as well as in onSession.
        manager.startInstall(SplitInstallRequest.newBuilder().addModule(module).build())
            .addOnFailureListener { e ->
                val code = (e as? SplitInstallException)?.errorCode
                DebugLog.w(TAG, "$module: Play refused the install, error ${code ?: e.javaClass.simpleName}")
                mutableState.value = ModuleState.Missing(failed = true)
            }
    }
}

private const val TAG = "modules"

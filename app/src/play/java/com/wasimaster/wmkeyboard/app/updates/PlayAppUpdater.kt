package com.wasimaster.wmkeyboard.app.updates

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Binds a Play [AppUpdateManager] to the settings activity for as long as this
 * composition lives, and hands back the [AppUpdater] every screen reads.
 *
 * This is the Play-channel half of the pair; `src/noplay/java` declares the
 * same function and returns [NoAppUpdater]. Only one of the two is compiled.
 */
@Composable
internal fun rememberAppUpdater(): AppUpdater {
    val activity = LocalContext.current.findActivity() ?: return NoAppUpdater
    // Asked before Play Core is touched at all, not after it has failed. An
    // install Play did not make can never be updated by Play, so there is
    // nothing here to degrade to, and asking anyway wrote a warning to the log
    // on every resume for the whole life of the install. See [installedByPlay].
    if (!remember(activity) { activity.installedByPlay() }) {
        DebugLog.d(TAG, "not a Play install, in-app updates are off")
        return NoAppUpdater
    }
    val updater = remember(activity) {
        PlayAppUpdater(
            manager = AppUpdateManagerFactory.create(activity),
            prefs = UpdatePrefs(activity),
        )
    }
    // Play returns an IntentSender, not an Intent, so the flow is launched
    // through the IntentSender contract rather than by starting an activity.
    val launcher = rememberLauncherForIntentSender(updater)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(updater, launcher, lifecycleOwner) {
        updater.attach(launcher)
        // ON_RESUME rather than a one-shot check: a flexible download finishes
        // while the user is in Play, and an interrupted immediate update has to
        // be picked back up the moment the app is in front again. Both are
        // states this activity can only learn about by asking on resume.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updater.check()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            updater.detach()
        }
    }
    return updater
}

@Composable
private fun rememberLauncherForIntentSender(
    updater: PlayAppUpdater,
): ActivityResultLauncher<IntentSenderRequest> =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        updater.onFlowResult(result.resultCode)
    }

/** The activity behind a Compose context, through however many wrappers. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The Play In-App Updates driver.
 *
 * Everything it knows arrives from two places: the `AppUpdateInfo` it asks for
 * on every resume, and the [InstallStateUpdatedListener] Play calls while a
 * flexible download runs. [UpdatePolicy] turns the first into a decision; the
 * second only ever moves the progress bar.
 *
 * ## The one rule
 *
 * `completeUpdate()` restarts the app, and this app's process is also the
 * keyboard's. So it is called from [install] and nowhere else, and [install] is
 * reachable only from a button. Play may still install the update by itself
 * later, through ordinary auto-update — that is Play's business and it picks
 * its own moment. This code does not get to pick one.
 */
internal class PlayAppUpdater(
    private val manager: AppUpdateManager,
    private val prefs: UpdatePrefs,
    private val now: () -> Long = System::currentTimeMillis,
) : AppUpdater {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The result launcher of the activity this updater is attached to. */
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null

    /** The last info Play gave us, kept so [start] does not have to ask again. */
    private var pending: AppUpdateInfo? = null

    /** Which flow [start] should run, decided by [UpdatePolicy] when the info arrived. */
    private var pendingFlow: UpdateFlow? = null

    /**
     * Whether a Play dialog has already been opened without being asked for,
     * in this activity's lifetime.
     *
     * Without it, every return from another app would re-open the dialog the
     * user is in the middle of reading: the flow itself pauses the activity,
     * so its own result arrives as a resume.
     */
    private var promptedAutomatically = false

    private val installListener = InstallStateUpdatedListener { installState ->
        when (installState.installStatus()) {
            // Queued, with no byte counts yet. Mapped to the same state as a
            // running download so the card changes the moment the user accepts
            // Play's dialog rather than a second later; the progress bar draws
            // itself indeterminate while the total is still zero.
            InstallStatus.PENDING -> _state.value = UpdateState.Downloading(0L, 0L)
            InstallStatus.DOWNLOADING -> _state.value = UpdateState.Downloading(
                bytesDownloaded = installState.bytesDownloaded(),
                totalBytes = installState.totalBytesToDownload(),
            )
            InstallStatus.DOWNLOADED -> _state.value = UpdateState.Downloaded
            InstallStatus.INSTALLING -> _state.value = UpdateState.Installing
            InstallStatus.FAILED -> {
                DebugLog.w(TAG, "flexible update failed: error ${installState.installErrorCode()}")
                _state.value = UpdateState.Failed(
                    cancelled = false,
                    reason = UpdateFailure.INSTALL_FAILED,
                )
            }
            InstallStatus.CANCELED -> _state.value = UpdateState.Failed(cancelled = true)
            else -> Unit
        }
    }

    fun attach(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        this.launcher = launcher
        manager.registerListener(installListener)
    }

    fun detach() {
        manager.unregisterListener(installListener)
        launcher = null
    }

    override fun check(userAsked: Boolean) {
        // Off, the resume asks Play nothing; the row still does (#507).
        if (!userAsked && !prefs.autoCheck) return
        if (userAsked) _state.value = UpdateState.Checking
        manager.appUpdateInfo
            .addOnSuccessListener { info -> onInfo(info, userAsked) }
            .addOnFailureListener { error ->
                val code = (error as? InstallException)?.errorCode
                if (code == InstallErrorCode.ERROR_APP_NOT_OWNED ||
                    code == InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND
                ) {
                    // Not a failure, a fact: this install has no Play behind
                    // it. [installedByPlay] catches almost every case of this
                    // before Play Core is ever built, and this is the rest,
                    // such as a device whose Play Store has been removed since.
                    // Saying so puts the whole Updates group away instead of
                    // leaving a row that can only ever fail.
                    DebugLog.d(TAG, "no Play Store behind this install, updates are off")
                    _state.value = UpdateState.Unsupported
                    return@addOnFailureListener
                }
                // Everything else is worth a line but not a dialog: no
                // network, a Play Store that is busy, a transient internal
                // error. None of them are the user's problem.
                DebugLog.w(TAG, "update check failed: ${error.message.orEmpty()}")
                if (userAsked) {
                    _state.value = UpdateState.Failed(
                        cancelled = false,
                        reason = UpdateFailure.NETWORK,
                    )
                }
            }
    }

    override fun start() {
        val info = pending ?: return
        val flow = pendingFlow ?: return
        startFlow(info, flow)
    }

    override fun install() {
        _state.value = UpdateState.Installing
        manager.completeUpdate()
    }

    override fun dismiss() {
        val available = state.value as? UpdateState.Available ?: return
        prefs.snooze(available.versionCode, now())
        // Same carve-out as in [offer]: an urgent release keeps its card.
        _state.value = available.copy(dismissed = !available.immediate)
    }

    override var autoPrompt: Boolean
        get() = prefs.autoPrompt
        set(value) {
            prefs.autoPrompt = value
        }

    override var autoCheck: Boolean
        get() = prefs.autoCheck
        set(value) {
            prefs.autoCheck = value
        }

    override val sourceNameRes: Int = R.string.update_source_play

    // Play does the downloading and the installing, inside its own flow, so
    // none of the controls that belong to a download this app owns apply here.
    // A Play release is also whatever Play is serving: there is no channel to
    // opt into, so the pre-release switch does not exist in this build.
    override var includePrereleases: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /**
     * Everything the resume path decides, in the order the cases have to be
     * tested: an interrupted blocking update first, then a download that has
     * already finished, then whatever Play is offering.
     */
    @Suppress("ReturnCount")
    private fun onInfo(info: AppUpdateInfo, userAsked: Boolean) {
        pending = info

        // An immediate update the user already accepted and that did not
        // finish — Play expects the flow to be re-opened, not restarted.
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            pendingFlow = UpdateFlow.IMMEDIATE
            startFlow(info, UpdateFlow.IMMEDIATE)
            return
        }

        // A flexible download that finished, possibly in an earlier session.
        // The install listener only reports what happens while it is
        // registered, so this is the only way to find out about the rest.
        when (info.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                _state.value = UpdateState.Downloaded
                return
            }
            InstallStatus.DOWNLOADING -> {
                _state.value = UpdateState.Downloading(
                    bytesDownloaded = info.bytesDownloaded(),
                    totalBytes = info.totalBytesToDownload(),
                )
                return
            }
            else -> Unit
        }

        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            pendingFlow = null
            _state.value = UpdateState.UpToDate(userAsked)
            return
        }

        offer(info, userAsked)
    }

    /** Turns an available update into a plan, and the plan into state or a flow. */
    private fun offer(info: AppUpdateInfo, userAsked: Boolean) {
        val version = info.availableVersionCode()
        val snoozed = prefs.isSnoozed(version, now())
        val plan = UpdatePolicy.plan(
            priority = info.updatePriority(),
            // Play omits this for a release published in the last day or so.
            stalenessDays = info.clientVersionStalenessDays() ?: -1,
            immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            snoozed = snoozed,
            autoPrompt = prefs.autoPrompt,
        )
        pendingFlow = plan.flow
        if (plan.flow == null) {
            // Play has an update but permits neither flow for this device.
            _state.value = UpdateState.UpToDate(userAsked)
            return
        }

        _state.value = UpdateState.Available(
            versionCode = version,
            sizeBytes = info.totalBytesToDownload(),
            immediate = plan.flow == UpdateFlow.IMMEDIATE,
            // An urgent release keeps its card even after a "no": its flow
            // re-opens on the next visit anyway, so hiding the one control
            // that explains why would only be confusing.
            dismissed = snoozed && plan.flow != UpdateFlow.IMMEDIATE,
        )

        // A check the user asked for never opens a dialog on its own: they are
        // looking at the row that is about to answer them.
        if (plan.automatic && !userAsked && !promptedAutomatically) {
            promptedAutomatically = true
            startFlow(info, plan.flow)
        }
    }

    private fun startFlow(info: AppUpdateInfo, flow: UpdateFlow) {
        val target = launcher ?: return
        val type = when (flow) {
            UpdateFlow.FLEXIBLE -> AppUpdateType.FLEXIBLE
            UpdateFlow.IMMEDIATE -> AppUpdateType.IMMEDIATE
        }
        val started = runCatching {
            manager.startUpdateFlowForResult(
                info,
                target,
                AppUpdateOptions.newBuilder(type).build(),
            )
        }.getOrElse { error ->
            DebugLog.w(TAG, "could not start update flow: ${error.message.orEmpty()}")
            false
        }
        if (!started) {
            _state.value = UpdateState.Failed(cancelled = false, reason = UpdateFailure.INSTALL_FAILED)
        }
    }

    /** The result of Play's own dialog or full-screen page. */
    fun onFlowResult(resultCode: Int) {
        when (resultCode) {
            Activity.RESULT_OK -> Unit // The install listener takes it from here.
            // Backing out of Play's own dialog is the same answer as pressing
            // "Not now", and is remembered the same way: this version stops
            // asking for a week. A high-priority release still comes back, and
            // keeps its card in the meantime, because its plan ignores the
            // snooze entirely.
            Activity.RESULT_CANCELED -> dismiss()
            else -> {
                DebugLog.w(TAG, "update flow failed with result $resultCode")
                _state.value = UpdateState.Failed(
                    cancelled = false,
                    reason = UpdateFailure.INSTALL_FAILED,
                )
            }
        }
    }

}

private const val TAG = "AppUpdates"

package com.wasimaster.wmkeyboard.app.updates

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import kotlinx.coroutines.flow.StateFlow

/**
 * Binds [GithubUpdateManager] to the settings activity for as long as this
 * composition lives, and hands back the [AppUpdater] every screen reads.
 *
 * This is the GitHub half of a set of three; `src/play/java` drives Play and
 * `src/fdroid/java` links out to F-Droid. Exactly one of the three is on the
 * compile path.
 */
@Composable
internal fun rememberAppUpdater(): AppUpdater {
    val context = LocalContext.current
    val activity = context.findActivity()
    val updater = remember(context) { GithubAppUpdater(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(updater, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Everything the app can learn only by looking again: whether
                // the unknown-sources switch has been turned on since, whether
                // a staged file is still there, and whether there is a newer
                // release. Same reasoning as the Play driver's resume check.
                Lifecycle.Event.ON_RESUME -> {
                    GithubUpdateManager.hasResumedActivity = true
                    updater.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> GithubUpdateManager.hasResumedActivity = false
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                Lifecycle.Event.ON_ANY,
                -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            GithubUpdateManager.hasResumedActivity = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // The screen Android asks for before it installs. Started from here rather
    // than from the receiver that heard about it: a foreground activity may
    // always launch an activity, and a background receiver may not.
    val pending = GithubUpdateManager.pendingUserAction
    LaunchedEffect(activity, pending) {
        pending.collect { confirm ->
            if (confirm == null || activity == null) return@collect
            runCatching { activity.startActivity(confirm) }
                .onSuccess { GithubUpdateManager.userActionShown() }
                .onFailure { DebugLog.w("AppUpdates", "could not show the install screen") }
        }
    }
    return updater
}

/** The activity behind a Compose context, through however many wrappers. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * In-app updates from this project's GitHub releases.
 *
 * A thin adapter: the work is in [GithubUpdateManager], which outlives this
 * object, because a download has to survive a rotation and the round trip out
 * to a system settings screen.
 *
 * ## The one rule, restated for this channel
 *
 * On Android 12 and up an update this app installs can complete without a
 * screen of its own, so the press on Install is the only warning the user gets
 * that the keyboard is about to restart. That is why nothing here installs on
 * the app's own initiative, not even to finish something interrupted: the
 * resume path only continues an install the user pressed within the last two
 * minutes, and only after Android has been given permission it was refused at
 * the time of that press.
 */
internal class GithubAppUpdater(
    private val context: Context,
    private val prefs: UpdatePrefs = UpdatePrefs(context),
) : AppUpdater {

    override val state: StateFlow<UpdateState> = GithubUpdateManager.state
    override val releaseNotes: StateFlow<String?> = GithubUpdateManager.releaseNotes
    override val switchingToAllLanguages: StateFlow<Boolean> = GithubUpdateManager.switching

    // The standard build too: on Android 7 to 10 it cannot load a language
    // pack, and the release carries the all-languages build again. BOOX has no
    // all-languages build; it takes language packs like the English base.
    override val canSwitchToAllLanguages: Boolean =
        !BuildConfig.ENABLE_ONYX_PEN && BuildConfig.FLAVOR == ReleaseAssets.LANGUAGES_EN

    override val sourceNameRes: Int = R.string.update_source_github

    /** This app fetches the bytes, so it owns cancelling, data saving and the restart. */
    override val ownsDownload: Boolean = true

    override val supportsPrereleases: Boolean = true

    /** The release carries its own notes; [loadNotes] fetches them. */
    override val supportsNotes: Boolean = true

    override var includePrereleases: Boolean
        get() = prefs.includePrereleases
        set(value) {
            if (value == prefs.includePrereleases) return
            prefs.includePrereleases = value
            // The answer changes immediately, and the release list in hand is
            // usually enough to work it out, so this is not a request.
            GithubUpdateManager.check(context, userAsked = true, allowPrompt = false)
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

    override val installGrant: SpecialAccess?
        get() = if (needsInstallGrant()) SpecialAccess.INSTALL_UPDATES else null

    fun onResume() {
        GithubUpdateManager.init(context)
        GithubUpdateManager.refresh(context)
        GithubUpdateManager.continuePending(context, grantNeeded = needsInstallGrant())
        // Whether a dialog has already been shown is the manager's to know: a
        // check is asynchronous, so by the time this could see one it would be
        // a resume too late.
        GithubUpdateManager.check(context, userAsked = false, allowPrompt = true)
    }

    override fun check(userAsked: Boolean) {
        GithubUpdateManager.check(context, userAsked = userAsked, allowPrompt = true)
    }

    override fun start() = GithubUpdateManager.start(context)

    override fun switchToAllLanguages() {
        if (canSwitchToAllLanguages) GithubUpdateManager.switchToAllLanguages(context)
    }

    override fun abandonLanguageSwitch() = GithubUpdateManager.abandonLanguageSwitch(context)

    override fun cancel() = GithubUpdateManager.cancel()

    override fun loadNotes() = GithubUpdateManager.loadNotes()

    override fun install() =
        GithubUpdateManager.install(context, grantNeeded = needsInstallGrant())

    override fun dismiss() = GithubUpdateManager.dismiss(context)

    /**
     * Whether Android still has to be told this app may install packages.
     *
     * Below API 26 there is no per-app switch to ask about: the setting is one
     * global toggle, and the system installer raises it itself when it matters.
     */
    private fun needsInstallGrant(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
}

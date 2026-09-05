package com.wasimaster.wmkeyboard.app.lock

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.ChoiceSetting
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.SettingsRowIcons
import com.wasimaster.wmkeyboard.app.SettingsRouteIcons
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.core.settings.AppLockDefaults
import com.wasimaster.wmkeyboard.core.settings.AppLockRelock
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Which settings the fingerprint stands in front of.
 *
 * The screen guards itself: `SettingsScreen` resolves this route to
 * [AppLockTargets.SELF], so once the lock is on you cannot reach these
 * checkboxes without answering a prompt first. That is also why none of the
 * rows here carry a lock of their own. A second check per checkbox, on a
 * screen you already proved your way onto, would be theatre.
 */
@Composable
internal fun AppLockSettingsScreen(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lock = LocalAppLock.current
    val status by lock.status.collectAsStateWithLifecycle()
    val config by lock.config.collectAsStateWithLifecycle()
    val cfg = config ?: return

    Spacer(Modifier.height(12.dp))

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.privacy_lock_enabled_title,
                stringResource(R.string.privacy_lock_enabled_subtitle),
                cfg.enabled,
                info = stringResource(R.string.privacy_lock_enabled_info),
                enabled = status.canEnable,
                default = AppLockDefaults.enabled,
            ) { wanted ->
                // A controlled switch: the value is written only after the
                // check passes, so a failed or cancelled prompt lets the thumb
                // slide back on the next recomposition. That slide back is
                // good feedback and is deliberately not suppressed.
                //
                // Both directions ask. Turning it on matters, but turning it
                // off matters more: a lock somebody else can switch off while
                // holding your phone is not a lock.
                //
                // Raised against SELF rather than as a bare check so that
                // passing it counts as this visit's check. Switching the
                // feature on puts the gate in front of this very screen on the
                // next frame, and a bare check would leave that gate asking a
                // second time for a finger the user gave a moment ago.
                lock.unlock(AppLockTargets.SELF) { result ->
                    if (!result.succeeded) return@unlock
                    scope.launch {
                        repository.setAppLockEnabled(wanted)
                        // Seed the curated picks the first time only, so the
                        // feature does something the moment it is switched on.
                        // Switching it off leaves the picks alone: coming back
                        // must not silently re-tick rows the user cleared.
                        if (wanted && cfg.lockedTargets.isEmpty()) {
                            repository.setAppLockTargets(AppLockTargets.defaultIds)
                        }
                    }
                }
            }
        }
    }

    if (status != AppLockStatus.READY) {
        CaptionText(stringResource(status.reason))
        if (status == AppLockStatus.NOT_ENROLLED) {
            SettingsGroup {
                item {
                    NavRow(
                        R.string.privacy_lock_enroll_title,
                        stringResource(R.string.privacy_lock_enroll_subtitle),
                    ) { context.startActivity(enrolIntent()) }
                }
            }
        }
    }

    for ((group, targets) in AppLockTargets.grouped) {
        SettingsGroup(stringResource(group.title)) {
            for (target in targets) {
                item {
                    ToggleSetting(
                        title = stringResource(target.label),
                        subtitle = stringResource(
                            if (target.kind == LockKind.SCREEN) {
                                R.string.privacy_lock_kind_screen
                            } else {
                                R.string.privacy_lock_kind_action
                            },
                        ),
                        checked = target.id in cfg.lockedTargets,
                        // The glyph of the thing being guarded, not a padlock
                        // on every row: the list should read as the screens
                        // and buttons it names.
                        icon = target.route?.let { SettingsRouteIcons[it] }
                            ?: SettingsRowIcons[target.label],
                        highlightKey = target.label,
                        enabled = cfg.enabled && status.canEnable,
                        default = target.onByDefault,
                    ) { checked ->
                        scope.launch { repository.setAppLockTarget(target.id, checked) }
                    }
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.privacy_lock_behaviour_group_title),
        info = stringResource(R.string.privacy_lock_threat_info),
    ) {
        item {
            ChoiceSetting(
                R.string.privacy_lock_relock_title,
                info = stringResource(R.string.privacy_lock_relock_info),
                options = relockOptions(),
                selected = cfg.relock,
                default = AppLockDefaults.relock,
            ) { scope.launch { repository.setAppLockRelock(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_lock_credential_title,
                stringResource(R.string.privacy_lock_credential_subtitle),
                cfg.allowDeviceCredential,
                info = stringResource(R.string.privacy_lock_credential_info),
                default = AppLockDefaults.allowDeviceCredential,
            ) { scope.launch { repository.setAppLockAllowDeviceCredential(it) } }
        }
    }

    // The last thing on the screen is the limit, not the promise.
}

@Composable
private fun relockOptions(): List<Pair<AppLockRelock, String>> = listOf(
    AppLockRelock.IMMEDIATE to stringResource(R.string.privacy_lock_relock_immediate),
    AppLockRelock.AFTER_1_MIN to stringResource(R.string.privacy_lock_relock_1min),
    AppLockRelock.AFTER_5_MIN to stringResource(R.string.privacy_lock_relock_5min),
    AppLockRelock.ON_LEAVE to stringResource(R.string.privacy_lock_relock_on_leave),
    AppLockRelock.UNTIL_APP_CLOSES to stringResource(R.string.privacy_lock_relock_session),
)

/**
 * The system screen that adds a fingerprint.
 *
 * `ACTION_BIOMETRIC_ENROLL` lands on the right page and can say which strength
 * is wanted, but it only exists from API 30. Below that, the security settings
 * are the closest thing every phone has.
 */
private fun enrolIntent(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

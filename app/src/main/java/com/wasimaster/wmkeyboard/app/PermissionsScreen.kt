package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.accessibility.KeyboardPassthrough
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess

/**
 * Privacy → Permissions: every permission the app can hold, in one place.
 *
 * Each feature already asks for its own permission where the feature lives, so
 * this screen is not a request path anyone is funnelled through — it is the
 * inventory. It answers "what can this keyboard reach?" without a trip through
 * the manifest, and it is the one place a permission can be granted ahead of
 * the feature that wants it.
 *
 * Granting still runs through the same two sanctioned routes as everywhere
 * else — [rememberDisclosedPermissionRequest] for runtime permissions and
 * [rememberDisclosedSpecialAccess] for the special grants — never a bare
 * `launch()`: Play's prominent-disclosure rule applies to this screen exactly
 * as it does to the features' own rows.
 */
@Composable
internal fun PermissionsSettings() {
    // The screen opens on free-standing text, which brings no spacing of its
    // own the way a group's SectionHeader does.
    Spacer(Modifier.height(12.dp))

    SettingsGroup(
        stringResource(R.string.privacy_permissions_runtime_group_title),
        info = stringResource(R.string.privacy_permissions_intro_info),
    ) {
        item {
            RuntimePermissionRow(
                R.string.privacy_permissions_mic_title,
                R.string.privacy_permissions_mic_subtitle,
                PermissionDisclosures.MICROPHONE,
            )
        }
        item {
            RuntimePermissionRow(
                R.string.privacy_permissions_camera_title,
                R.string.privacy_permissions_camera_subtitle,
                PermissionDisclosures.CAMERA,
            )
        }
        item {
            RuntimePermissionRow(
                R.string.privacy_permissions_contacts_title,
                R.string.privacy_permissions_contacts_subtitle,
                PermissionDisclosures.CONTACT_NAMES,
            )
        }
        item {
            RuntimePermissionRow(
                R.string.privacy_permissions_calendar_title,
                R.string.privacy_permissions_calendar_subtitle,
                PermissionDisclosures.CALENDAR,
            )
        }
        item {
            RuntimePermissionRow(
                R.string.privacy_permissions_images_title,
                R.string.privacy_permissions_images_subtitle,
                PermissionDisclosures.IMAGES,
            )
        }
        // From API 29 scoped storage saves to the gallery without any
        // permission, so the row would be a switch wired to nothing.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            item {
                RuntimePermissionRow(
                    R.string.privacy_permissions_storage_title,
                    R.string.privacy_permissions_storage_subtitle,
                    PermissionDisclosures.STORAGE,
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.privacy_permissions_special_group_title)) {
        item {
            SpecialAccessRow(
                R.string.privacy_permissions_notifications_title,
                R.string.privacy_permissions_notifications_subtitle,
                SpecialAccess.NOTIFICATIONS,
                ::hasNotificationAccess,
            )
        }
        item {
            SpecialAccessRow(
                R.string.privacy_permissions_usage_title,
                R.string.privacy_permissions_usage_subtitle,
                SpecialAccess.USAGE,
                ::hasUsageAccess,
            )
        }
        item {
            SpecialAccessRow(
                R.string.privacy_permissions_accessibility_title,
                R.string.privacy_permissions_accessibility_subtitle,
                SpecialAccess.ACCESSIBILITY,
                KeyboardPassthrough::isServiceEnabled,
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.privacy_permissions_install_group_title),
        info = stringResource(R.string.privacy_permissions_footer_info),
    ) {
        item {
            InstallPermissionRow(
                R.string.privacy_permissions_internet_title,
                R.string.privacy_permissions_internet_subtitle,
            )
        }
        item {
            InstallPermissionRow(
                R.string.privacy_permissions_network_state_title,
                R.string.privacy_permissions_network_state_subtitle,
            )
        }
        item {
            InstallPermissionRow(
                R.string.privacy_permissions_vibrate_title,
                R.string.privacy_permissions_vibrate_subtitle,
            )
        }
        item {
            InstallPermissionRow(
                R.string.privacy_permissions_biometric_title,
                R.string.privacy_permissions_biometric_subtitle,
            )
        }
    }

}

/** The app's own page in system Settings, where a granted permission can be taken back. */
private fun appDetailsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData("package:${context.packageName}".toUri())

/**
 * One runtime permission: state on the right, the feature it unlocks
 * underneath. Not granted leads into the disclosure-then-request flow; granted
 * leads to the app's system settings page, the only place a grant can be
 * taken back.
 */
@Composable
private fun RuntimePermissionRow(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    disclosure: PermissionDisclosure,
) {
    val context = LocalContext.current
    // Re-read on every return to the foreground: both directions of a change
    // happen on system screens this row sends the user to.
    val granted = rememberGrantState {
        ContextCompat.checkSelfPermission(it, disclosure.permission) ==
            PackageManager.PERMISSION_GRANTED
    }
    val request = rememberDisclosedPermissionRequest(disclosure) {
        // Nothing to flip: the features watch the permission itself, and the
        // row re-reads it on resume.
    }
    NavRow(
        title,
        stringResource(subtitle),
        value = statusLabel(granted),
    ) {
        if (granted) {
            runCatching { context.startActivity(appDetailsIntent(context)) }
        } else {
            request()
        }
    }
}

/**
 * One special-access grant. These live on their own system screens, so the row
 * always goes through the disclosure and then out to the switch — including
 * when granted, since that same screen is where it is turned off.
 */
@Composable
private fun SpecialAccessRow(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    access: SpecialAccess,
    check: (Context) -> Boolean,
) {
    val granted = rememberGrantState(check)
    val open = rememberDisclosedSpecialAccess(access)
    NavRow(
        title,
        stringResource(subtitle),
        value = statusLabel(granted),
    ) { open() }
}

/**
 * A permission Android hands out with the install. Nothing to press: it cannot
 * be asked for, and it cannot be given back. The row exists so the inventory
 * is complete — a reader of this screen should not need the manifest to learn
 * the app can reach the network.
 */
@Composable
private fun InstallPermissionRow(@StringRes title: Int, @StringRes subtitle: Int) {
    WmRow(
        title = stringResource(title),
        subtitle = stringResource(subtitle),
        icon = SettingsRowIcons[title],
        highlightKey = title,
        trailing = {
            Text(
                stringResource(R.string.privacy_permissions_status_install),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun statusLabel(granted: Boolean): String = stringResource(
    if (granted) {
        R.string.privacy_permissions_status_granted
    } else {
        R.string.privacy_permissions_status_not_granted
    },
)

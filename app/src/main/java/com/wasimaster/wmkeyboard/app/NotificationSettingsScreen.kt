package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.NotificationSwitches
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.ime.KeyboardControls

/**
 * What the keyboard may put in the notification shade.
 *
 * One switch per kind, because they are worth different amounts to different
 * people: someone who never downloads a dictionary still wants to know a
 * backup failed, and someone who pins the keyboard wants the controls without
 * the rest. Turning them all off leaves an app that posts nothing, which is
 * where it started before this screen existed.
 *
 * The switches are the app's own, on top of Android's channels rather than
 * instead of them: the last group sends the user to the system's page, where
 * the same four kinds appear as channels and can be silenced or hidden with
 * the platform's own controls.
 *
 * ## Why these are not in the settings store
 *
 * They live in [NotificationSwitches], in device-protected preferences, for
 * two reasons that point the same way. The things that post — a dictionary
 * downloader in `:core:prediction`, the backup runner — sit below
 * `:core:settings` and cannot read that store at all. And `KeyboardSettings`
 * is at the JVM's limit on constructor arguments (see the update preferences,
 * which live in their own file for the same reason), so four more fields on it
 * do not compile. The rows below therefore keep their own copy of each value
 * and write through, exactly as the update rows on the About screen do.
 */
@Composable
internal fun NotificationSettingsScreen(settings: LiveSettings) {
    val context = LocalContext.current
    // Decides whether the KDE Connect row is a row of the group.
    val kdeOn = settings.watch { it.kdeConnect.enabled }

    // Re-read on every resume: the permission and the app-wide switch both
    // live in system settings, and this screen is the obvious place to come
    // back to after changing them there.
    var allowed by remember { mutableStateOf(WmNotifications.canPost(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            allowed = WmNotifications.canPost(context)
        }
    }
    val ask = rememberNotificationPermissionRequest { allowed = true }

    if (!allowed) {
        SettingsGroup(stringResource(R.string.notify_permission_group)) {
            item {
                NavRow(
                    R.string.notify_permission_title,
                    subtitle = stringResource(R.string.notify_permission_subtitle),
                    onClick = ask,
                )
            }
        }
    }

    val downloads = remember { mutableStateOf(NotificationSwitches.isOn(context, NotificationKind.DOWNLOADS)) }
    val updates = remember { mutableStateOf(NotificationSwitches.isOn(context, NotificationKind.UPDATES)) }
    val backup = remember { mutableStateOf(NotificationSwitches.isOn(context, NotificationKind.BACKUP)) }
    val controls = remember { mutableStateOf(NotificationSwitches.isOn(context, NotificationKind.KEYBOARD)) }
    val connect = remember { mutableStateOf(NotificationSwitches.isOn(context, NotificationKind.CONNECT)) }

    SettingsGroup(
        stringResource(R.string.notify_kinds_group),
        info = stringResource(R.string.notify_kinds_info),
    ) {
        // Nothing downloads and no update is ever looked for in the offline build.
        item(visible = !OfflineBuild.isThisBuild, waitsOnSetting = false) {
            ToggleSetting(
                R.string.notify_downloads_title,
                stringResource(R.string.notify_downloads_subtitle),
                checked = downloads.value,
                default = NotificationSwitches.DEFAULT_DOWNLOADS,
                info = stringResource(R.string.notify_downloads_info),
            ) {
                downloads.value = it
                NotificationSwitches.set(context, NotificationKind.DOWNLOADS, it)
            }
        }
        item(visible = !OfflineBuild.isThisBuild, waitsOnSetting = false) {
            ToggleSetting(
                R.string.notify_updates_title,
                stringResource(R.string.notify_updates_subtitle),
                checked = updates.value,
                default = NotificationSwitches.DEFAULT_UPDATES,
                info = stringResource(R.string.notify_updates_info),
            ) {
                updates.value = it
                NotificationSwitches.set(context, NotificationKind.UPDATES, it)
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.notify_backup_title,
                stringResource(R.string.notify_backup_subtitle),
                checked = backup.value,
                default = NotificationSwitches.DEFAULT_BACKUP,
                info = stringResource(R.string.notify_backup_info),
            ) {
                backup.value = it
                NotificationSwitches.set(context, NotificationKind.BACKUP, it)
            }
        }
        // Only while the KDE Connect tool is on: before that nothing can ever
        // post one, and a switch for an event that cannot happen is noise.
        item(visible = kdeOn) {
            ToggleSetting(
                R.string.kdeconnect_notify_title,
                stringResource(R.string.kdeconnect_notify_subtitle),
                checked = connect.value,
                default = NotificationSwitches.DEFAULT_CONNECT,
                info = stringResource(R.string.kdeconnect_notify_info),
            ) {
                connect.value = it
                NotificationSwitches.set(context, NotificationKind.CONNECT, it)
                if (it && !WmNotifications.canPost(context)) ask()
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.notify_controls_title,
                stringResource(R.string.notify_controls_subtitle),
                checked = controls.value,
                info = stringResource(R.string.notify_controls_info),
                default = NotificationSwitches.DEFAULT_KEYBOARD,
            ) { on ->
                controls.value = on
                NotificationSwitches.set(context, NotificationKind.KEYBOARD, on)
                // The one switch with something to show for itself immediately:
                // an ongoing notification the user just asked for should not
                // wait for the keyboard to next read its settings.
                if (on) {
                    if (!WmNotifications.canPost(context)) ask()
                    KeyboardControls.post(context, settings.value.persistentKeyboard)
                } else {
                    KeyboardControls.clear(context)
                }
            }
        }
    }

    // Android's own page, where the same four kinds are channels: this app can
    // decide whether to post, and only the platform can decide how loud.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        SettingsGroup(stringResource(R.string.notify_system_group)) {
            item {
                NavRow(
                    R.string.notify_system_title,
                    subtitle = stringResource(R.string.notify_system_subtitle),
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
        }
    }
}

package com.wasimaster.wmkeyboard.app

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.launcher.LauncherShizukuRow
import com.wasimaster.wmkeyboard.core.accessibility.ScreenContextService
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.core.shell.ShellFeatures
import com.wasimaster.wmkeyboard.core.shell.ShellGrants
import kotlinx.coroutines.launch

private const val IME_SERVICE = "com.wasimaster.wmkeyboard.ime.WMKeyboardService"
private const val LISTENER_SERVICE = "com.wasimaster.wmkeyboard.core.media.MediaNotificationListener"
private const val SPELL_SERVICE = "com.wasimaster.wmkeyboard.spell.HarperSpellCheckerService"

/**
 * Privacy › Permissions › Shizuku or root: what the keyboard can do with a
 * shell door open, behind one master switch, in the F-Droid and GitHub
 * builds only ([ShellFeatures.offered]). The grants land where Android shows
 * them, so each one is taken back the usual way in system Settings.
 */
@Composable
internal fun ShellFeaturesGroup(repository: SettingsRepository) {
    if (!ShellFeatures.offered) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(ShellFeatures.isEnabled(context)) }
    var readsScreen by remember { mutableStateOf(ShellFeatures.replyReadsScreenSetting(context)) }
    // Bumped after each grant, so the rows re-read what changed.
    var version by remember { mutableIntStateOf(0) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = null)
    val useRoot = settings?.launcher?.useRoot ?: false
    LaunchedEffect(useRoot) { ShellAccess.rootAllowed = useRoot }
    val shizuku by ShellAccess.shizukuGranted.collectAsState()
    val ready = shizuku || (useRoot && ShellAccess.suPresent)

    fun report(result: Boolean?) {
        version++
        Toast.makeText(
            context,
            when (result) {
                null -> R.string.shell_access_result_no_access
                true -> R.string.shell_access_result_done
                false -> R.string.shell_access_result_failed
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun run(block: suspend () -> Boolean?) {
        if (!ready) {
            report(null)
            return
        }
        scope.launch { report(block()) }
    }

    SettingsGroup(
        stringResource(R.string.shell_access_group_title),
        info = stringResource(R.string.shell_access_group_info),
    ) {
        item {
            ToggleSetting(
                R.string.shell_access_enabled_title,
                stringResource(R.string.shell_access_enabled_subtitle),
                enabled,
                default = false,
            ) {
                enabled = it
                ShellFeatures.setEnabled(context, it)
            }
        }
        item(visible = enabled) { LauncherShizukuRow() }
        item(visible = enabled && ShellAccess.suPresent) {
            ToggleSetting(
                R.string.tooldetail_launcher_root_title,
                stringResource(R.string.tooldetail_launcher_root_subtitle),
                useRoot,
                info = stringResource(R.string.tooldetail_launcher_root_info),
                default = false,
            ) {
                ShellAccess.rootAllowed = it
                scope.launch { repository.setLauncherUseRoot(it) }
            }
        }
        item(visible = enabled) {
            NavRow(R.string.shell_access_default_keyboard_title, stringResource(R.string.shell_access_default_keyboard_subtitle)) {
                run { ShellGrants.makeDefaultKeyboard(context, IME_SERVICE) }
            }
        }
        item(visible = enabled) {
            val missing = remember(version) { ShellGrants.missingRuntimePermissions(context).size }
            NavRow(
                R.string.shell_access_grant_permissions_title,
                stringResource(R.string.shell_access_grant_permissions_subtitle),
                value = if (missing == 0) stringResource(R.string.privacy_permissions_status_granted) else missing.toString(),
            ) {
                if (missing == 0) return@NavRow
                run { ShellGrants.grantRuntimePermissions(context)?.let { it > 0 } }
            }
        }
        item(visible = enabled) {
            val granted = remember(version) { hasNotificationAccess(context) }
            NavRow(
                R.string.shell_access_notification_access_title,
                stringResource(R.string.shell_access_notification_access_subtitle),
                value = statusText(granted),
            ) {
                if (!granted) run { ShellGrants.allowNotificationListener(context, LISTENER_SERVICE) }
            }
        }
        item(visible = enabled) {
            val granted = remember(version) { ScreenContextService.isEnabled(context) }
            NavRow(
                R.string.shell_access_screen_content_title,
                stringResource(R.string.shell_access_screen_content_subtitle),
                value = statusText(granted),
            ) {
                if (!granted) run { ShellGrants.enableAccessibilityService(context, ScreenContextService::class.java.name) }
            }
        }
        item(visible = enabled) {
            val granted = remember(version) { ShellGrants.canWriteSecureSettings(context) }
            NavRow(
                R.string.shell_access_secure_settings_title,
                stringResource(R.string.shell_access_secure_settings_subtitle),
                value = statusText(granted),
            ) {
                if (!granted) run { ShellGrants.grantWriteSecureSettings(context) }
            }
        }
        item(visible = enabled) {
            val chosen = remember(version) { ShellGrants.isSpellChecker(context, SPELL_SERVICE) }
            NavRow(
                R.string.shell_access_spell_checker_title,
                stringResource(R.string.shell_access_spell_checker_subtitle),
                value = statusText(chosen),
            ) {
                if (!chosen) run { ShellGrants.useSpellChecker(context, SPELL_SERVICE) }
            }
        }
        item(visible = enabled) {
            ToggleSetting(
                R.string.shell_access_reply_screen_title,
                stringResource(R.string.shell_access_reply_screen_subtitle),
                readsScreen,
                default = false,
            ) {
                readsScreen = it
                ShellFeatures.setReplyReadsScreen(context, it)
            }
        }
    }
}

@Composable
private fun statusText(on: Boolean): String = stringResource(
    if (on) R.string.privacy_permissions_status_granted else R.string.privacy_permissions_status_not_granted,
)

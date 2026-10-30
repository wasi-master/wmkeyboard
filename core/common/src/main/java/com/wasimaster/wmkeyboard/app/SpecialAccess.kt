package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.common.R

/**
 * The grants Android does not hand out through a permission dialog:
 * notification access, usage access, the accessibility service, installing
 * updates and drawing over other apps. Each is a toggle on a system Settings
 * screen the app can only *navigate to*.
 *
 * Play treats these as more sensitive than ordinary permissions, not less —
 * notification access and the Accessibility API each have their own Console
 * declaration, and both are routinely refused when the reviewer cannot see why
 * the app needs them. So the same prominent-disclosure rule from
 * [PermissionDisclosure] applies here: say what is read and why *before*
 * sending anyone to the switch, and never bounce them there straight from a tap.
 *
 * The wording matters more than usual for these three, because what the system
 * screen says is alarming and generic ("full control of your device", "read all
 * notifications") while what this app actually does with each is narrow. The
 * copy (in `strings_ui.xml`, under `common_special_access_*`) states the narrow
 * truth without arguing with the system warning — a reviewer reads both, and a
 * disclosure that contradicts the platform's own text reads as a dodge.
 */
enum class SpecialAccess {
    /** Notification listener, for the media-control tool's transport buttons. */
    NOTIFICATIONS,

    /**
     * The same notification-listener grant, asked for by the one-time-code
     * chip. A separate constant because the two features read very different
     * things — media reads nothing, the code chip reads notification text —
     * and each disclosure must state what *its* feature does, while also
     * naming the other so neither reads as the whole story.
     */
    NOTIFICATION_CODES,

    /** Usage access, for "show source app" on clipboard entries. */
    USAGE,

    /** The touch-passthrough accessibility service, for keyboard gestures under TalkBack. */
    ACCESSIBILITY,

    /**
     * The screen-reading accessibility service, for the floating AI button
     * (#697). Unlike [ACCESSIBILITY] this one does read window content, once
     * per press of that button, so the disclosure says exactly that.
     */
    SCREEN_CONTENT,

    /**
     * The split-screen accessibility service, for the app launcher's split
     * screen where the keyboard's own split launch opens full screen. It
     * performs one action, the system's split-screen switch, and reads only
     * where the app windows are, so the disclosure says exactly that.
     */
    SPLIT_SCREEN,

    /**
     * Permission to install packages, for the builds that update themselves
     * from this project's GitHub releases.
     *
     * The odd one out here in that it is not about reading anything. It is
     * still a switch on a system screen rather than a prompt, so it belongs to
     * the same flow, and its disclosure has more work to do than the others':
     * the system screen says this app may install apps, and what it actually
     * does with that is install one app, itself, from one place, after
     * checking the file against the release checksum and its own signing key.
     */
    INSTALL_UPDATES,

    /**
     * "Display over other apps", for the floating keyboard button (issue
     * #374). Nothing is read with it: the one window it draws is the button.
     */
    OVERLAY,

    /**
     * "Alarms and reminders", for the clock tool: an alarm set for 6:30
     * rings at 6:30. Without it the alarm still rings, but Android may hold
     * it back by some minutes while the phone sleeps. Nothing is read.
     */
    EXACT_ALARMS,

    /**
     * Full-screen notifications, for the clock tool's ring screen on a locked
     * phone (asked from Android 14). Without it a ringing alarm shows as a
     * notification instead.
     */
    FULL_SCREEN,

    /**
     * All files access, for the keyboard's file manager (from Android 11). The
     * grant really does cover everything on shared storage, which is why the
     * disclosure opens with it rather than with what the keyboard does with it.
     */
    ALL_FILES,
    ;

    /** Dialog heading: the grant and the feature that wants it. */
    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            NOTIFICATIONS -> R.string.common_special_access_notifications_title
            NOTIFICATION_CODES -> R.string.common_special_access_notification_codes_title
            USAGE -> R.string.common_special_access_usage_title
            ACCESSIBILITY -> R.string.common_special_access_accessibility_title
            SCREEN_CONTENT -> R.string.common_special_access_screen_content_title
            SPLIT_SCREEN -> R.string.common_special_access_split_screen_title
            INSTALL_UPDATES -> R.string.common_special_access_install_updates_title
            OVERLAY -> R.string.common_special_access_overlay_title
            EXACT_ALARMS -> R.string.common_special_access_exact_alarms_title
            FULL_SCREEN -> R.string.common_special_access_full_screen_title
            ALL_FILES -> R.string.common_special_access_all_files_title
        }

    /** What is read, why, where it goes, and that the feature is optional. */
    @get:StringRes
    val bodyRes: Int
        get() = when (this) {
            NOTIFICATIONS -> R.string.common_special_access_notifications_body
            NOTIFICATION_CODES -> R.string.common_special_access_notification_codes_body
            USAGE -> R.string.common_special_access_usage_body
            ACCESSIBILITY -> R.string.common_special_access_accessibility_body
            SCREEN_CONTENT -> R.string.common_special_access_screen_content_body
            SPLIT_SCREEN -> R.string.common_special_access_split_screen_body
            INSTALL_UPDATES -> R.string.common_special_access_install_updates_body
            OVERLAY -> R.string.common_special_access_overlay_body
            EXACT_ALARMS -> R.string.common_special_access_exact_alarms_body
            FULL_SCREEN -> R.string.common_special_access_full_screen_body
            ALL_FILES -> R.string.common_special_access_all_files_body
        }

    /**
     * Where to send the user. First entry preferred, later ones are fallbacks for
     * devices where the deep link is missing — the per-app notification-listener
     * page only exists from API 30, and some OEMs ship neither.
     */
    internal fun intents(component: ComponentName?, packageName: String): List<Intent> = when (this) {
        // The version check is spelled out rather than folded into a takeIf so
        // that Lint can see it: both constants are Strings, so they inline into
        // the APK and would be used unguarded on API 24 otherwise.
        NOTIFICATIONS, NOTIFICATION_CODES -> listOfNotNull(
            if (component != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component.flattenToString(),
                    )
            } else {
                null
            },
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        USAGE -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        ACCESSIBILITY, SCREEN_CONTENT, SPLIT_SCREEN -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        // The per-app "install unknown apps" page arrived with API 26. Below
        // that the setting is one global switch with no page to send anyone
        // to, so the list is empty and the system installer asks for itself.
        // The version check is spelled out for the same reason as above.
        INSTALL_UPDATES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri(),
                ),
                // Some early Oreo skins ship the action without the per-app
                // page, where the bare intent lands on the list of apps.
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
            )
        } else {
            emptyList()
        }
        // The app's own row first; some skins only have the list of apps.
        OVERLAY -> listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        // The app's own switch first; some skins only have the list of apps.
        ALL_FILES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        } else {
            emptyList()
        }
        // Both pages are newer than minSdk; the checks are spelled out for Lint.
        EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
            )
        } else {
            emptyList()
        }
        FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
            )
        } else {
            emptyList()
        }
    }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}

/** Opens the first Settings screen that this device actually has. */
internal fun SpecialAccess.openSettings(context: Context, component: ComponentName? = null) {
    intents(component, context.packageName).firstOrNull { intent ->
        runCatching { context.startActivity(intent) }.isSuccess
    }
}

/**
 * Settings-app entry point: returns a lambda that shows the disclosure and, if
 * the user continues, opens the system screen holding the switch.
 */
@Composable
fun rememberDisclosedSpecialAccess(access: SpecialAccess): () -> Unit {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    if (showing) {
        DisclosureDialog(
            titleRes = access.titleRes,
            bodyRes = access.bodyRes,
            continueLabelRes = R.string.common_open_system_settings,
            onContinue = {
                showing = false
                access.openSettings(context)
            },
            onDismiss = { showing = false },
        )
    }
    return { showing = true }
}

/**
 * The IME's route to the same thing. An input method cannot host a dialog
 * outside its own window and must not hand out a "grant" button that jumps
 * straight to a system screen with no explanation, so it starts this invisible
 * activity, which shows the disclosure and then opens Settings.
 *
 * Build the intent with [intent]; the component extra is only read for
 * [SpecialAccess.NOTIFICATIONS], where it selects the app's own row on the
 * per-listener settings page.
 */
class SpecialAccessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val access = intent?.getStringExtra(EXTRA_ACCESS)
            ?.let { name -> SpecialAccess.entries.firstOrNull { it.name == name } }
        if (access == null) {
            finish()
            return
        }
        val component = intent?.getStringExtra(EXTRA_COMPONENT)
            ?.let { ComponentName.unflattenFromString(it) }
        setContent {
            DisclosureTheme {
                DisclosureDialog(
                    titleRes = access.titleRes,
                    bodyRes = access.bodyRes,
                    continueLabelRes = R.string.common_open_system_settings,
                    onContinue = {
                        access.openSettings(this, component)
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ACCESS = "access"
        private const val EXTRA_COMPONENT = "component"

        /** Intent that shows [access]'s disclosure and then its Settings screen. */
        fun intent(
            context: Context,
            access: SpecialAccess,
            component: ComponentName? = null,
        ): Intent = Intent(context, SpecialAccessActivity::class.java)
            .putExtra(EXTRA_ACCESS, access.name)
            .putExtra(EXTRA_COMPONENT, component?.flattenToString())
    }
}

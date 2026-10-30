package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.wasimaster.wmkeyboard.common.R

/**
 * One prominent disclosure: what the keyboard is about to ask Android for, what
 * it does with the answer, and where that data goes.
 *
 * Google Play's User Data policy requires this for every permission that
 * reaches personal or sensitive data, and it is stricter than it first looks —
 * the disclosure has to be *inside the app*, has to appear *before* the system
 * permission dialog, has to name the data and the use in the app's own words,
 * and has to be refusable without granting anything. A description in the
 * settings row, the store listing or the privacy policy does not satisfy any of
 * those on its own.
 *
 * An input method draws that scrutiny harder than most apps do: a keyboard that
 * asks for the microphone, the camera, contacts and the photo library is
 * shaped exactly like a keylogger from the outside, so every one of those
 * requests goes through [PermissionRequestActivity] (from the IME, which cannot
 * show a permission dialog itself) or [rememberDisclosedPermissionRequest]
 * (from the settings app) rather than calling `launch()` directly. There is no
 * fourth path, and adding one is how the next review gets failed.
 *
 * The copy lives in [PermissionDisclosures] next to the permission it belongs
 * to so the two cannot drift; keep it truthful rather than reassuring, since
 * this text is also what the Play Data safety form has to agree with.
 */
data class PermissionDisclosure(
    /** The Android permission this disclosure precedes. */
    val permission: String,
    /** Dialog heading: the permission and the feature that wants it. */
    @StringRes val titleRes: Int,
    /** What is read, why, where it goes, and that the feature is optional. */
    @StringRes val bodyRes: Int,
    /**
     * Permissions Android must be asked for in the same request. Location is
     * the case: from Android 12 a request for precise location alone is
     * ignored, and the dialog that offers "Precise" or "Approximate" appears
     * only when both are asked for together. Any one of them granted counts as
     * a grant; the feature reads which it got.
     */
    val alsoRequests: List<String> = emptyList(),
) {
    /** Everything one request asks for, [permission] first. */
    val requested: Array<String> get() = (listOf(permission) + alsoRequests).toTypedArray()
}

/** The disclosure copy for every runtime permission this app asks for. */
object PermissionDisclosures {

    val MICROPHONE = PermissionDisclosure(
        permission = Manifest.permission.RECORD_AUDIO,
        titleRes = R.string.common_permission_microphone_title,
        bodyRes = R.string.common_permission_microphone_body,
    )

    val CAMERA = PermissionDisclosure(
        permission = Manifest.permission.CAMERA,
        titleRes = R.string.common_permission_camera_title,
        bodyRes = R.string.common_permission_camera_body,
    )

    /**
     * The Location tool (#350), and "Use my location" for the weather and
     * compass tools. Precise is the one asked for, with approximate alongside
     * so Android 12 draws its choice between the two; a user who picks
     * approximate still gets a working tool.
     */
    val LOCATION = PermissionDisclosure(
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
        titleRes = R.string.common_permission_location_title,
        bodyRes = R.string.common_permission_location_body,
        alsoRequests = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
    )

    val CALENDAR = PermissionDisclosure(
        permission = Manifest.permission.READ_CALENDAR,
        titleRes = R.string.common_permission_calendar_title,
        bodyRes = R.string.common_permission_calendar_body,
    )

    /**
     * Writing to the calendar: the calendar tool's Save, Edit and Delete, and
     * the AI's "add it directly" for events (#698). Read is asked for
     * alongside, since choosing which calendar to add to means listing them.
     */
    val CALENDAR_WRITE = PermissionDisclosure(
        permission = Manifest.permission.WRITE_CALENDAR,
        titleRes = R.string.common_permission_calendar_write_title,
        bodyRes = R.string.common_permission_calendar_write_body,
        alsoRequests = listOf(Manifest.permission.READ_CALENDAR),
    )

    /** The calendar tool's guest row: typing a name offers that contact's address. */
    val CONTACT_GUESTS = PermissionDisclosure(
        permission = Manifest.permission.READ_CONTACTS,
        titleRes = R.string.common_permission_contact_guests_title,
        bodyRes = R.string.common_permission_contact_guests_body,
    )

    val CONTACT_NAMES = PermissionDisclosure(
        permission = Manifest.permission.READ_CONTACTS,
        titleRes = R.string.common_permission_contact_names_title,
        bodyRes = R.string.common_permission_contact_names_body,
    )

    val CONTACT_EMAILS = PermissionDisclosure(
        permission = Manifest.permission.READ_CONTACTS,
        titleRes = R.string.common_permission_contact_emails_title,
        bodyRes = R.string.common_permission_contact_emails_body,
    )

    /** The call/message chip (#656): names and phone numbers. */
    val CONTACT_PHONES = PermissionDisclosure(
        permission = Manifest.permission.READ_CONTACTS,
        titleRes = R.string.common_permission_contact_phones_title,
        bodyRes = R.string.common_permission_contact_phones_body,
    )

    /**
     * Screenshots in the clipboard. Android has no screenshots-only permission —
     * granting this opens the whole photo library — so the disclosure says so
     * rather than implying a narrower grant than the user is actually giving.
     */
    val IMAGES = PermissionDisclosure(
        permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
        titleRes = R.string.common_permission_images_title,
        bodyRes = R.string.common_permission_images_body,
    )

    /**
     * The gallery tool's photos and videos (#708). From Android 13 they are
     * two permissions, asked for together; from 14 the system dialog also
     * offers "Select photos", which the keyboard honours as a partial grant.
     * Asking again with only part granted is how the user picks more.
     */
    val GALLERY = PermissionDisclosure(
        permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
        titleRes = R.string.common_permission_gallery_title,
        bodyRes = R.string.common_permission_gallery_body,
        alsoRequests = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> emptyList()
        },
    )

    /**
     * The Send files tool's direct link (#729): a Wi-Fi this phone opens for
     * another, or the other's joined. Android 13 has a permission for exactly
     * that; before it, Wi-Fi Direct and the hotspot sit behind location, which
     * is why the copy says so plainly. No position is read or kept.
     */
    val NEARBY_WIFI = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionDisclosure(
            permission = Manifest.permission.NEARBY_WIFI_DEVICES,
            titleRes = R.string.common_permission_nearby_title,
            bodyRes = R.string.common_permission_nearby_body,
        )
    } else {
        PermissionDisclosure(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            titleRes = R.string.common_permission_nearby_title,
            bodyRes = R.string.common_permission_nearby_body_location,
            alsoRequests = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    /**
     * The shade. Not a data permission at all — nothing is read, only posted —
     * but it goes through the same disclosure as the rest so there stays
     * exactly one way this app asks Android for anything, and so the user
     * learns what will arrive before they say yes to it.
     *
     * Null below API 33, where notifications need no grant and the dialog
     * would be a dialog about nothing.
     */
    val NOTIFICATIONS: PermissionDisclosure? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionDisclosure(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                titleRes = R.string.common_permission_notifications_title,
                bodyRes = R.string.common_permission_notifications_body,
            )
        } else {
            null
        }

    /**
     * Save-to-gallery below API 29. The old permission covers reading as well as
     * writing, which the copy admits rather than glossing over.
     */
    val STORAGE = PermissionDisclosure(
        permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
        titleRes = R.string.common_permission_storage_title,
        bodyRes = R.string.common_permission_storage_body,
    )

    /**
     * The file manager on Android 7 to 10: the old storage pair, which covers
     * all of shared storage. From Android 11 it is [SpecialAccess.ALL_FILES].
     */
    val FILES = PermissionDisclosure(
        permission = Manifest.permission.READ_EXTERNAL_STORAGE,
        titleRes = R.string.common_special_access_all_files_title,
        bodyRes = R.string.common_special_access_all_files_body,
        alsoRequests = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    )

    /**
     * AnkiDroid's own permission (#765): the Look up card adds a word to a
     * deck. AnkiDroid defines it; the grant covers its whole collection, which
     * the copy says rather than claiming the card only ever adds.
     */
    val ANKIDROID = PermissionDisclosure(
        permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE",
        titleRes = R.string.common_permission_ankidroid_title,
        bodyRes = R.string.common_permission_ankidroid_body,
    )

    /**
     * OpenTasks' own pair (#920): the To-do list syncs with the one task list
     * the user picks, which DAVx5 keeps in step with a CalDAV server. The
     * grant covers every list in the app, which the copy says.
     */
    val TASKS_OPENTASKS = PermissionDisclosure(
        permission = "org.dmfs.permission.READ_TASKS",
        titleRes = R.string.common_permission_tasks_opentasks_title,
        bodyRes = R.string.common_permission_tasks_body,
        alsoRequests = listOf("org.dmfs.permission.WRITE_TASKS"),
    )

    /** Tasks.org's copy of the same provider, under its own permissions. */
    val TASKS_TASKS_ORG = PermissionDisclosure(
        permission = "org.tasks.permission.READ_TASKS",
        titleRes = R.string.common_permission_tasks_tasks_org_title,
        bodyRes = R.string.common_permission_tasks_body,
        alsoRequests = listOf("org.tasks.permission.WRITE_TASKS"),
    )
}

/**
 * Tells a refusal the user made apart from one Android made on their behalf.
 *
 * After two refusals Android stops drawing the permission dialog, and
 * `requestPermissions` comes straight back "denied" with nothing on screen. A
 * button that silently does nothing is worse than no button, so that case has
 * to end at the app's page in Settings instead.
 *
 * Nothing an app can read *before* asking separates that case from one where
 * the dialog will still appear. `shouldShowRequestPermissionRationale` answers
 * false for "denied for good", but also for "never asked" and — the case that
 * matters most — for every permission set to **Ask every time** (issue #351):
 * both that Settings choice and the expiry of an "Only this time" grant clear
 * the platform's USER_SET flag, which is all the rationale call reads. A guess
 * made up front would send one-time users to a Settings page that has no
 * one-time option, forever.
 *
 * So the request always goes out first, and only its answer decides: a denial
 * that arrives faster than anyone could read and answer a dialog, for a
 * permission Android no longer wants a rationale for, was never shown.
 */
internal class PermissionProbe {

    private var launchedAt = 0L

    /** Call right before `launch()`. */
    fun launched() {
        launchedAt = SystemClock.elapsedRealtime()
    }

    /** Whether a denial for [permission] was Android's answer, not the user's. */
    fun silentlyRefused(activity: Activity, permission: String): Boolean =
        launchedAt != 0L &&
            SystemClock.elapsedRealtime() - launchedAt < SILENT_REFUSAL_MS &&
            !activity.shouldShowRequestPermissionRationale(permission)

    private companion object {
        /**
         * The auto-denial is a same-process activity round trip, well under
         * this; a person reading the dialog and pressing a button is well over.
         * Erring long only means a very fast "Don't allow" also gets the
         * Settings offer, which the user can wave off with "Not now".
         */
        const val SILENT_REFUSAL_MS = 500L
    }
}

/** The app's own page in system Settings, where a blocked permission can still be granted. */
private fun appSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData("package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * The disclosure dialog: heading, the explanation, and an explicit choice.
 *
 * Dismissing (the button, the scrim or Back) must leave without asking for
 * anything — Play requires the disclosure to be refusable, and the feature
 * behind it is expected to keep working in its permission-less state.
 *
 * When [blocked], the disclosure still has to be shown (the user is on their
 * way to granting the permission, so Play's requirement applies exactly as it
 * does to the prompt) but the button leads to Settings rather than to a dialog
 * Android will not draw.
 */
@Composable
internal fun DisclosureDialog(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    blocked: Boolean = false,
    @StringRes continueLabelRes: Int =
        if (blocked) R.string.common_open_system_settings else R.string.common_continue,
) {
    val body = stringResource(bodyRes)
    // Two resources rather than one: the note only applies when Android has
    // stopped drawing the system dialog, and every disclosure body shares it.
    val bodyText = if (blocked) {
        body + "\n\n" + stringResource(R.string.common_disclosure_blocked_body)
    } else {
        body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            // Long bodies on a short screen: scroll rather than clip, because a
            // clipped disclosure is not a disclosure.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(bodyText, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(continueLabelRes)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_disclosure_not_now))
            }
        },
    )
}

/** Convenience wrapper for the runtime-permission case. */
@Composable
fun PermissionDisclosureDialog(
    disclosure: PermissionDisclosure,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    blocked: Boolean = false,
) = DisclosureDialog(
    titleRes = disclosure.titleRes,
    bodyRes = disclosure.bodyRes,
    onContinue = onContinue,
    onDismiss = onDismiss,
    blocked = blocked,
)

/**
 * Settings-app entry point: returns a lambda that shows the disclosure and, if
 * the user continues, asks Android for the permission. [onGranted] runs only on
 * a grant, so the caller can flip its setting on there and nowhere else.
 *
 * [disclosure] is nullable for the permissions that do not exist on every
 * Android version — notifications, below API 33. A null one has nothing to ask
 * for, so the returned lambda reports the grant it already has. Nullable
 * rather than left to the caller because the caller would have to branch
 * around a composable call, which is the one thing a composable may not do.
 */
@Composable
fun rememberDisclosedPermissionRequest(
    disclosure: PermissionDisclosure?,
    onGranted: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val probe = remember { PermissionProbe() }
    var showing by remember { mutableStateOf(false) }
    // Only ever set by the answer to a request: see [PermissionProbe].
    var blocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.any { it }
        val activity = context.findActivity()
        when {
            granted -> onGranted()
            disclosure != null && activity != null &&
                probe.silentlyRefused(activity, disclosure.permission) -> {
                // Android refused without drawing anything; bring the
                // disclosure back, now pointing at Settings.
                blocked = true
                showing = true
            }
        }
    }
    if (showing && disclosure != null) {
        PermissionDisclosureDialog(
            disclosure = disclosure,
            blocked = blocked,
            onContinue = {
                showing = false
                if (blocked) {
                    runCatching { context.startActivity(appSettingsIntent(context)) }
                } else {
                    probe.launched()
                    launcher.launch(disclosure.requested)
                }
            },
            onDismiss = { showing = false },
        )
    }
    return {
        if (disclosure == null) {
            onGranted()
        } else {
            blocked = false
            showing = true
        }
    }
}

/** The Activity behind a Compose context, for the calls that only Activity has. */
internal fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Base class for the invisible trampolines the IME starts when a tool needs a
 * permission: an input method service cannot show a permission dialog itself,
 * so it starts one of these instead.
 *
 * The trampoline shows the disclosure, then the system dialog, then finishes.
 * Subclasses supply the disclosure and nothing else; a `null` disclosure (a
 * permission that does not apply on this Android version) finishes immediately.
 * The tools re-check the permission when the keyboard regains focus, which is
 * what actually drives the panel — these activities never report back.
 */
abstract class PermissionRequestActivity : ComponentActivity() {

    /** The permission to ask for, or null when this build/API needs none. */
    protected abstract val disclosure: PermissionDisclosure?

    /**
     * Whether there is nothing left to ask for, which closes the trampoline
     * at once. By default the main permission alone; a feature whose grant is
     * several permissions says when it has all it needs.
     */
    protected open fun alreadyGranted(disclosure: PermissionDisclosure): Boolean =
        checkSelfPermission(disclosure.permission) == PackageManager.PERMISSION_GRANTED

    private val probe = PermissionProbe()

    // Only ever set by the answer to a request: see [PermissionProbe].
    private var blocked by mutableStateOf(false)

    private val request =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.any { it }
            val permission = disclosure?.permission
            if (!granted && permission != null && probe.silentlyRefused(this, permission)) {
                // Android refused without drawing anything. The disclosure is
                // still on screen behind where the dialog would have been;
                // it now says so and offers Settings instead.
                blocked = true
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val disclosure = disclosure
        if (disclosure == null || alreadyGranted(disclosure)) {
            finish()
            return
        }
        setContent {
            DisclosureTheme {
                PermissionDisclosureDialog(
                    disclosure = disclosure,
                    blocked = blocked,
                    onContinue = {
                        if (blocked) {
                            // The system dialog will not appear; the app's
                            // settings page is the only place left to grant it.
                            runCatching { startActivity(appSettingsIntent(this)) }
                            finish()
                        } else {
                            probe.launched()
                            request.launch(disclosure.requested)
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

/**
 * Material You colours for a dialog that is hosted by a bare translucent
 * activity theme. The app's own theme lives in :core:theme, which depends on
 * this module — so this is a deliberately tiny stand-in rather than that.
 */
@Composable
internal fun DisclosureTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

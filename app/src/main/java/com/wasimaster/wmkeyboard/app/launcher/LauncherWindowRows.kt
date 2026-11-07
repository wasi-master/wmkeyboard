package com.wasimaster.wmkeyboard.app.launcher

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
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
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.rememberGrantState
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.ime.launch.LaunchExecutor
import com.wasimaster.wmkeyboard.ime.launch.LaunchRoute
import com.wasimaster.wmkeyboard.ime.launch.RouteStore
import kotlinx.coroutines.launch

/**
 * **Check what works on this phone**: runs the launcher's calibration from
 * settings (a test window floats, then splits beside this screen) and shows
 * the routes it found. The full result line goes to the clipboard, for a bug
 * report from a phone the keyboard has not met yet.
 */
@Composable
internal fun LauncherRouteCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val executor = remember { LaunchExecutor(context) }
    var running by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val floating = remember(version) { executor.known(LaunchRoute.Kind.FLOATING) }
    val split = remember(version) { executor.known(LaunchRoute.Kind.SPLIT) }
    val subtitle = when {
        running -> stringResource(R.string.tooldetail_launcher_check_running)
        floating == null && split == null -> stringResource(R.string.tooldetail_launcher_check_never)
        else -> stringResource(
            R.string.tooldetail_launcher_check_result,
            stringResource(knownLabel(floating)),
            stringResource(knownLabel(split)),
        )
    }
    NavRow(R.string.tooldetail_launcher_check_title, subtitle = subtitle) {
        if (running) return@NavRow
        running = true
        scope.launch {
            ShellAccess.init()
            executor.recalibrate(LaunchRoute.Kind.FLOATING, null)
            executor.recalibrate(LaunchRoute.Kind.SPLIT, context.packageName)
            running = false
            version++
            copy(context, executor.describe())
            Toast.makeText(context, R.string.tooldetail_launcher_check_copied, Toast.LENGTH_SHORT).show()
        }
    }
}

@StringRes
private fun knownLabel(known: RouteStore.Known?): Int {
    // RouteStore lives in another module, so known.route cannot be smart-cast.
    val route = known?.route
    return when {
        known == null -> R.string.tooldetail_launcher_route_untested
        route == null -> R.string.tooldetail_launcher_route_none
        else -> routeLabel(route)
    }
}

/** A route's name as the settings show it. */
@StringRes
internal fun routeLabel(route: LaunchRoute): Int = when (route) {
    LaunchRoute.FLOAT_COLOROS -> R.string.tooldetail_launcher_route_coloros
    LaunchRoute.FLOAT_FREEFORM -> R.string.tooldetail_launcher_route_freeform
    LaunchRoute.FLOAT_HUAWEI -> R.string.tooldetail_launcher_route_huawei_float
    LaunchRoute.FLOAT_MEIZU -> R.string.tooldetail_launcher_route_flyme
    LaunchRoute.FLOAT_VIVO -> R.string.tooldetail_launcher_route_vivo
    LaunchRoute.FLOAT_MOTOROLA -> R.string.tooldetail_launcher_route_motorola
    LaunchRoute.FLOAT_MIUI_FACTORY, LaunchRoute.FLOAT_MIUI_SCALED -> R.string.tooldetail_launcher_route_xiaomi
    LaunchRoute.FLOAT_NUBIA -> R.string.tooldetail_launcher_route_nubia
    LaunchRoute.FLOAT_BOUNDS -> R.string.tooldetail_launcher_route_desktop
    LaunchRoute.FLOAT_SHELL, LaunchRoute.SPLIT_SHELL -> R.string.tooldetail_launcher_route_shell
    LaunchRoute.FLOAT_EMULATED -> R.string.tooldetail_launcher_route_emulated
    LaunchRoute.SPLIT_SERVICE,
    LaunchRoute.SPLIT_TRAMPOLINE_ADJ_FIRST,
    LaunchRoute.SPLIT_TRAMPOLINE_PLAIN_FIRST,
    -> R.string.tooldetail_launcher_route_android_split
    LaunchRoute.SPLIT_HUAWEI -> R.string.tooldetail_launcher_route_huawei_split
    LaunchRoute.SPLIT_HELPER -> R.string.tooldetail_launcher_route_helper
}

/**
 * Shizuku's state, and the one tap that moves it along: get it, start it,
 * allow the keyboard. Shizuku runs commands as the shell user, which is what
 * floats apps on phones without a floating window and splits on the ones that
 * ignore the keyboard's own request.
 */
@Composable
internal fun LauncherShizukuRow() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ShellAccess.init()
        ShellAccess.refresh()
    }
    val granted by ShellAccess.shizukuGranted.collectAsState()
    val running = rememberGrantState { ShellAccess.shizukuRunning() }
    val installed = rememberGrantState { ShellAccess.shizukuInstalled(it) }
    NavRow(
        R.string.tooldetail_launcher_shizuku_title,
        subtitle = stringResource(
            when {
                granted -> R.string.tooldetail_launcher_shizuku_on
                running -> R.string.tooldetail_launcher_shizuku_allow
                installed -> R.string.tooldetail_launcher_shizuku_start
                else -> R.string.tooldetail_launcher_shizuku_get
            },
        ),
    ) {
        when {
            granted -> Unit
            running -> ShellAccess.requestShizuku()
            installed -> context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?.let { runCatching { context.startActivity(it) } }
            else -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
            }
        }
    }
}

/**
 * Android's own freeform windows, the developer option most phones hide:
 * with it on (and a restart), the plain floating route works on any skin.
 * Writing it takes WRITE_SECURE_SETTINGS, which only adb or the shell can
 * grant; the row copies the one command when neither is at hand.
 */
@Composable
internal fun LauncherFreeformRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val builtIn = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
    }
    var version by remember { mutableIntStateOf(0) }
    // Re-read on resume (adb may have run meanwhile) and after each write.
    val resumed = rememberGrantState { freeformSetting(it) }
    val on = remember(version, resumed) { freeformSetting(context) }
    val canWrite = rememberGrantState { canWriteSecure(it) }
    val shell by ShellAccess.shizukuGranted.collectAsState()
    val command = "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
    NavRow(
        R.string.tooldetail_launcher_freeform_title,
        subtitle = when {
            builtIn -> stringResource(R.string.tooldetail_launcher_freeform_builtin)
            on -> stringResource(R.string.tooldetail_launcher_freeform_on)
            canWrite || shell || ShellAccess.available -> stringResource(R.string.tooldetail_launcher_freeform_off)
            else -> stringResource(R.string.tooldetail_launcher_freeform_needs_adb, command)
        },
    ) {
        if (builtIn) return@NavRow
        val turnOn = !on
        when {
            canWrite -> {
                writeFreeform(context, turnOn)
                version++
            }
            ShellAccess.available -> scope.launch {
                val value = if (turnOn) 1 else 0
                ShellAccess.exec(
                    context,
                    "settings put global $FREEFORM_KEY $value; settings put global $RESIZABLE_KEY $value",
                )
                version++
            }
            else -> {
                copy(context, command)
                Toast.makeText(context, R.string.tooldetail_launcher_freeform_copied, Toast.LENGTH_LONG).show()
            }
        }
        if (turnOn && (canWrite || ShellAccess.available)) {
            Toast.makeText(context, R.string.tooldetail_launcher_freeform_restart, Toast.LENGTH_LONG).show()
        }
    }
}

private fun freeformSetting(context: Context): Boolean =
    runCatching { Settings.Global.getInt(context.contentResolver, FREEFORM_KEY, 0) != 0 }.getOrDefault(false)

private fun canWriteSecure(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

private fun writeFreeform(context: Context, on: Boolean) {
    val value = if (on) 1 else 0
    runCatching {
        Settings.Global.putInt(context.contentResolver, FREEFORM_KEY, value)
        Settings.Global.putInt(context.contentResolver, RESIZABLE_KEY, value)
    }
}

private fun copy(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("WM Keyboard", text))
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"
private const val FREEFORM_KEY = "enable_freeform_support"
private const val RESIZABLE_KEY = "force_resizable_activities"

package com.wasimaster.wmkeyboard.ime.floating

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Vibrator
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.SpecialAccessActivity
import com.wasimaster.wmkeyboard.core.settings.DeviceControl
import com.wasimaster.wmkeyboard.core.settings.RingerMode
import com.wasimaster.wmkeyboard.core.settings.nextRingerMode
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.core.shell.ShellFeatures
import com.wasimaster.wmkeyboard.ime.R

/**
 * The floating button's phone controls (#1326): the ringer, the volume panel,
 * and the Wi-Fi, mobile data, Bluetooth and hotspot switches.
 *
 * Each switch takes the first route that works on this phone:
 *
 *  1. the app's own API, where Android still lets an app flip it: Wi-Fi up to
 *     Android 9, Bluetooth up to Android 11;
 *  2. a shell command through Shizuku or root ([ShellAccess]): once the
 *     keyboard is allowed in Shizuku, or **Use root** is on, wherever that
 *     switch was turned on ([shell]);
 *  3. Android's own panel or page for the switch, for the user to flip.
 *
 * No route starts the hotspot: Android has no public call for it, and its
 * shell side has no command that starts the user's own saved hotspot, so the
 * hotspot always opens its page.
 */
object DeviceControls {

    /** Whether [control] is flipped by the keyboard itself on this phone now, rather than opening a page. */
    fun switchesItself(context: Context, control: DeviceControl): Boolean = when (control) {
        DeviceControl.RINGER -> true
        DeviceControl.VOLUME_PANEL, DeviceControl.HOTSPOT -> false
        DeviceControl.WIFI -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || shell(context)
        DeviceControl.BLUETOOTH -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S || shell(context)
        DeviceControl.MOBILE_DATA -> shell(context)
    }

    /** What [control] does on this phone, for its row in the settings pickers. */
    fun description(context: Context, control: DeviceControl): String {
        val itself = switchesItself(context, control)
        val text = context.getString(
            when (control) {
                DeviceControl.RINGER -> R.string.ime_floating_device_ringer_desc
                DeviceControl.VOLUME_PANEL -> R.string.ime_floating_device_volume_desc
                DeviceControl.WIFI ->
                    if (itself) R.string.ime_floating_device_wifi_desc else R.string.ime_floating_device_wifi_panel_desc
                DeviceControl.MOBILE_DATA ->
                    if (itself) R.string.ime_floating_device_data_desc else R.string.ime_floating_device_data_panel_desc
                DeviceControl.BLUETOOTH ->
                    if (itself) R.string.ime_floating_device_bluetooth_desc else R.string.ime_floating_device_bluetooth_page_desc
                DeviceControl.HOTSPOT -> R.string.ime_floating_device_hotspot_desc
            },
        )
        // A switch that opens a page here, in a build that can reach Shizuku or root.
        val shellWouldSwitch = !itself && ShellFeatures.offered &&
            control in setOf(DeviceControl.WIFI, DeviceControl.MOBILE_DATA, DeviceControl.BLUETOOTH)
        return if (shellWouldSwitch) context.getString(R.string.ime_floating_device_shell_hint, text) else text
    }

    /** The name of [control], on the button, in its menu and on the settings page. */
    fun labelRes(control: DeviceControl): Int = when (control) {
        DeviceControl.RINGER -> R.string.ime_floating_device_ringer
        DeviceControl.VOLUME_PANEL -> R.string.ime_floating_device_volume
        DeviceControl.WIFI -> R.string.ime_floating_device_wifi
        DeviceControl.MOBILE_DATA -> R.string.ime_floating_device_data
        DeviceControl.BLUETOOTH -> R.string.ime_floating_device_bluetooth
        DeviceControl.HOTSPOT -> R.string.ime_floating_device_hotspot
    }

    /**
     * Works [control], saying what it did in a toast where nothing on screen
     * does. Call on the main thread; a shell command suspends off it.
     */
    suspend fun run(context: Context, control: DeviceControl) {
        when (control) {
            DeviceControl.RINGER -> cycleRinger(context)
            DeviceControl.VOLUME_PANEL -> openVolumePanel(context)
            DeviceControl.WIFI -> toggleWifi(context)
            DeviceControl.MOBILE_DATA -> toggleMobileData(context)
            DeviceControl.BLUETOOTH -> toggleBluetooth(context)
            DeviceControl.HOTSPOT -> openHotspotPage(context)
        }
    }

    /**
     * Whether a switch can go through Shizuku or root now (#1364): in a build
     * that offers them at all, with the keyboard allowed in Shizuku or with
     * **Use root** on. Either is the user's own yes to the shell, given where
     * the switch is, so neither waits on the master switch of Privacy ›
     * Shizuku or root as well. Before, a phone with root allowed and **Use
     * root** on under the app launcher still opened the panels, because that
     * other switch was off, and nothing on the floating button's page said so.
     */
    private fun shell(context: Context): Boolean {
        if (!ShellFeatures.offered) return false
        ShellAccess.init(context)
        return ShellAccess.available
    }

    /**
     * Whether [control] is on now, for its slot in the floating button's rings
     * to show (#1364); null for the controls with no on and off (the ringer,
     * the volume panel) and for a state this phone will not tell. Cheap: each
     * is one read of a system service or a setting.
     */
    fun isOn(context: Context, control: DeviceControl): Boolean? = when (control) {
        DeviceControl.RINGER, DeviceControl.VOLUME_PANEL -> null
        DeviceControl.WIFI -> runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
        }.getOrNull()
        DeviceControl.MOBILE_DATA -> mobileDataOn(context)
        DeviceControl.BLUETOOTH -> bluetoothOn(context)
        DeviceControl.HOTSPOT -> hotspotOn(context)
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothOn(context: Context): Boolean? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
            ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON) == 1 }
                .getOrNull()

    /**
     * The hotspot's state, read through the call Android keeps for itself
     * and lets apps with the Wi-Fi state permission make; null where a phone
     * refuses it.
     */
    private fun hotspotOn(context: Context): Boolean? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
    }.getOrNull()

    // ---- the ringer ----

    /**
     * Ring, vibrate, silent, ring. Android lets an app set silent, or leave
     * it, only with Do Not Disturb access; without it the cycle is ring and
     * vibrate, and a phone found already on silent asks for the access, since
     * nothing else would take it off.
     */
    private fun cycleRinger(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return failed(context)
        val canSilence = context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true
        val canVibrate = context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
        val current = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            else -> RingerMode.RING
        }
        if (current == RingerMode.SILENT && !canSilence) return askDoNotDisturb(context)
        val next = nextRingerMode(current, canVibrate, canSilence)
        if (next == current) return askDoNotDisturb(context)
        val mode = when (next) {
            RingerMode.RING -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
        val set = runCatching { audio.ringerMode = mode }
        if (set.exceptionOrNull() is SecurityException) return askDoNotDisturb(context)
        if (set.isFailure) return failed(context)
        toast(
            context,
            when (next) {
                RingerMode.RING -> R.string.ime_floating_ringer_ring
                RingerMode.VIBRATE -> R.string.ime_floating_ringer_vibrate
                RingerMode.SILENT -> R.string.ime_floating_ringer_silent
            },
        )
    }

    private fun askDoNotDisturb(context: Context) {
        start(context, SpecialAccessActivity.intent(context, SpecialAccess.DO_NOT_DISTURB))
    }

    // ---- volume ----

    /**
     * Android's volume panel, the sheet with every stream's slider (Android
     * 10 and later), or the volume slider a volume key shows where the
     * phone has no panel.
     */
    private fun openVolumePanel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && start(context, Intent(Settings.Panel.ACTION_VOLUME))) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return failed(context)
        runCatching {
            audio.adjustSuggestedStreamVolume(
                AudioManager.ADJUST_SAME,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI,
            )
        }.onFailure { failed(context) }
    }

    // ---- the switches ----

    @Suppress("DEPRECATION")
    private suspend fun toggleWifi(context: Context) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val on = runCatching { wifi?.isWifiEnabled }.getOrNull()
        if (wifi != null && on != null) {
            // Android 9 and older let an app flip Wi-Fi itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                runCatching { wifi.setWifiEnabled(!on) }.getOrDefault(false)
            ) {
                return switched(context, DeviceControl.WIFI, !on)
            }
            // `cmd wifi` from Android 11; `svc wifi` before it.
            val verb = if (on) "disable" else "enable"
            val command = "cmd wifi set-wifi-enabled ${verb}d 2>/dev/null || svc wifi $verb"
            if (shellRan(context, command)) return switched(context, DeviceControl.WIFI, !on)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && start(context, Intent(Settings.Panel.ACTION_WIFI))) return
        if (!start(context, Intent(Settings.ACTION_WIFI_SETTINGS))) failed(context)
    }

    private suspend fun toggleMobileData(context: Context) {
        val on = mobileDataOn(context)
        if (on != null && shellRan(context, "svc data ${if (on) "disable" else "enable"}")) {
            return switched(context, DeviceControl.MOBILE_DATA, !on)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            start(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        ) {
            return
        }
        if (!start(context, Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) &&
            !start(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))
        ) {
            failed(context)
        }
    }

    /**
     * Mobile data's switch for the default data SIM. [TelephonyManager.isDataEnabled]
     * needs only the network-state permission from Android 12; before that it
     * may refuse, and the global setting it mirrors is read instead.
     */
    @SuppressLint("MissingPermission")
    private fun mobileDataOn(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.getSystemService(TelephonyManager::class.java)?.isDataEnabled }
                .getOrNull()?.let { return it }
        }
        return runCatching { Settings.Global.getInt(context.contentResolver, "mobile_data") == 1 }.getOrNull()
    }

    /**
     * Up to Android 11 the adapter's own call, under the two install-time
     * Bluetooth permissions (declared only that far). From 12 that call wants
     * the Nearby devices prompt, so Shizuku or root, or Android's page.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun toggleBluetooth(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null) {
            val on = runCatching { adapter.isEnabled }.getOrNull()
                ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON) == 1 }
                    .getOrNull()
            if (on != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                    runCatching { if (on) adapter.disable() else adapter.enable() }.getOrDefault(false)
                ) {
                    return switched(context, DeviceControl.BLUETOOTH, !on)
                }
                // `cmd bluetooth_manager` from Android 13; `svc bluetooth` before it.
                val verb = if (on) "disable" else "enable"
                if (shellRan(context, "cmd bluetooth_manager $verb 2>/dev/null || svc bluetooth $verb")) {
                    return switched(context, DeviceControl.BLUETOOTH, !on)
                }
            }
        }
        if (!start(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) failed(context)
    }

    /**
     * The hotspot's page: Settings' own action where it answers it, then the
     * page by name as AOSP has called it, then the network page it sits on.
     */
    private fun openHotspotPage(context: Context) {
        val pages = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            ),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        if (pages.none { start(context, it) }) failed(context)
    }

    // ---- helpers ----

    /** Runs [command] through Shizuku or root, when the user allowed them; true when it succeeded. */
    private suspend fun shellRan(context: Context, command: String): Boolean =
        shell(context) && ShellAccess.exec(context, command)?.ok == true

    private fun switched(context: Context, control: DeviceControl, on: Boolean) {
        val name = context.getString(labelRes(control))
        toast(context, context.getString(if (on) R.string.ime_floating_device_on else R.string.ime_floating_device_off, name))
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun failed(context: Context) = toast(context, R.string.ime_floating_device_failed)

    private fun toast(context: Context, message: Int) = toast(context, context.getString(message))

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

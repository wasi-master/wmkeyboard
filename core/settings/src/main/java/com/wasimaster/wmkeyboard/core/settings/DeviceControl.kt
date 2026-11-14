package com.wasimaster.wmkeyboard.core.settings

/**
 * One of the phone's own controls the floating button can work (#1326): the
 * ringer, the volume panel, and the Wi-Fi, mobile data, Bluetooth and hotspot
 * switches. Stored by [name], so a control a later build drops reads back as
 * nothing.
 *
 * An app may flip only some of these itself, and only on some Android
 * versions: Wi-Fi before Android 10, Bluetooth before Android 12. Past that,
 * the keyboard flips them through Shizuku or root where the user allowed it,
 * and otherwise opens Android's own panel or page for the switch.
 */
enum class DeviceControl {
    /** Ring, vibrate and silent, in turn. */
    RINGER,

    /** Android's volume panel. */
    VOLUME_PANEL,
    WIFI,
    MOBILE_DATA,
    BLUETOOTH,

    /** No app can start the hotspot on current Android, so this one always opens its page. */
    HOTSPOT,
}

/** The phone's ringer, as [DeviceControl.RINGER] cycles it. */
enum class RingerMode { RING, VIBRATE, SILENT }

/**
 * The mode after [current]: ring, then vibrate, then silent, then ring again.
 * Vibrate is skipped on a phone with no vibrator, and silent while the
 * keyboard may not set it ([canSilence]: Android ties silent to Do Not
 * Disturb access). With neither, ring stays ring.
 */
fun nextRingerMode(current: RingerMode, canVibrate: Boolean, canSilence: Boolean): RingerMode = when (current) {
    RingerMode.RING -> when {
        canVibrate -> RingerMode.VIBRATE
        canSilence -> RingerMode.SILENT
        else -> RingerMode.RING
    }
    RingerMode.VIBRATE -> if (canSilence) RingerMode.SILENT else RingerMode.RING
    RingerMode.SILENT -> RingerMode.RING
}

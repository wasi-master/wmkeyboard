package com.wasimaster.wmkeyboard.core.plugins

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.plugins.R

/**
 * What a plugin may do beyond drawing inside its own panel.
 *
 * Every permission has a [Tier]:
 *
 * * [Tier.FREE] and [Tier.LISTED] apply as soon as the plugin is installed.
 *   They are shown before install and on the plugin's page, and none of them
 *   can carry what the user types anywhere: storage stays on the device, a
 *   reminder is a line the app shows, writing into the field is something the
 *   user sees happen.
 * * [Tier.RISKY] permissions (API 5) can read what the user wrote or reach
 *   beyond the phone. Declaring one is not enough: each is **off until the
 *   user grants it** on the plugin's page, behind a warning, and only while
 *   the global "Allow risky permissions" switch in [PluginStore] is on. The
 *   keyboard checks the declaration, the grant and the field's state again
 *   on every call ([PluginHost]).
 *
 * What no grant allows, in any build: seeing keystrokes or text typed outside
 * the plugin's own boxes, reading the field or the clipboard except while the
 * user acts in the plugin's panel, touching a password field or incognito
 * text, running while none of the plugin's panels is open, loading code from
 * the network, or reaching Android beyond the named host calls.
 *
 * A permission string this build does not recognise is a **refused install**,
 * not an ignored line — see [PluginManifestCodec]. A capability that cannot be
 * explained to the user before they install is one that cannot be granted, and
 * refusing is also the forward-compatibility guard.
 */
sealed class PluginPermission(
    /** The exact string a manifest declares. */
    val wire: String,
    /**
     * One line for the "This plugin can" list, shown before install and in
     * settings. A resource id: this module has no Context.
     */
    @get:StringRes val labelRes: Int,
    val tier: Tier,
    /** The API level that introduced it; a manifest below it may not declare it. */
    val sinceApi: Int = 1,
    /** The warning shown before a grant, for a [Tier.RISKY] permission. */
    @get:StringRes val warningRes: Int? = null,
) {

    enum class Tier {
        /** Nothing to tell the user beyond the list. */
        FREE,

        /** Applies on install, shown in the list. */
        LISTED,

        /** Off until the user grants it, behind a warning. */
        RISKY,
    }

    /** Per-plugin storage under the plugin's own directory: `wm.storage`, `wm.secrets`. */
    data object Storage : PluginPermission("storage", R.string.core_plugins_permission_storage_label, Tier.LISTED)

    /** Reminders the app shows at a time the plugin chose: `wm.reminders`. */
    data object Reminders : PluginPermission("reminders", R.string.core_plugins_permission_reminders_label, Tier.LISTED)

    /**
     * Selection actions: chips on the bar over selected text, declared in the
     * manifest's `actions`. The selection reaches the plugin only when the user
     * taps one of its chips.
     */
    data object SelectedText : PluginPermission("selection", R.string.core_plugins_permission_selection_label, Tier.LISTED)

    // ---- API 5 ----------------------------------------------------------

    /** `wm.http`: https requests to the hosts the manifest lists. */
    data object Network : PluginPermission(
        "network",
        R.string.core_plugins_permission_network_label,
        Tier.RISKY,
        sinceApi = 5,
        warningRes = R.string.core_plugins_permission_network_warning,
    )

    /** `wm.ai`: the AI model the user set up in the keyboard. */
    data object Ai : PluginPermission(
        "ai",
        R.string.core_plugins_permission_ai_label,
        Tier.RISKY,
        sinceApi = 5,
        warningRes = R.string.core_plugins_permission_ai_warning,
    )

    /** `wm.field` reads: the selection and the text around the cursor, while the user acts in the panel. */
    data object FieldRead : PluginPermission(
        "field.read",
        R.string.core_plugins_permission_field_read_label,
        Tier.RISKY,
        sinceApi = 5,
        warningRes = R.string.core_plugins_permission_field_read_warning,
    )

    /** `wm.field` writes: typing, replacing the selection, moving the cursor. */
    data object FieldWrite : PluginPermission("field.write", R.string.core_plugins_permission_field_write_label, Tier.LISTED, sinceApi = 5)

    /** `wm.clipboard.get`, while the user acts in the panel; never a private clip. */
    data object ClipboardRead : PluginPermission(
        "clipboard.read",
        R.string.core_plugins_permission_clipboard_read_label,
        Tier.RISKY,
        sinceApi = 5,
        warningRes = R.string.core_plugins_permission_clipboard_read_warning,
    )

    data object ClipboardWrite : PluginPermission("clipboard.write", R.string.core_plugins_permission_clipboard_write_label, Tier.LISTED, sinceApi = 5)

    data object Location : PluginPermission(
        "location",
        R.string.core_plugins_permission_location_label,
        Tier.RISKY,
        sinceApi = 5,
        warningRes = R.string.core_plugins_permission_location_warning,
    )

    /** Files the user picks in the system picker, and saving files where the user chooses. */
    data object Files : PluginPermission(
        "files",
        R.string.core_plugins_permission_files_label,
        Tier.RISKY,
        sinceApi = 5,
        warningRes = R.string.core_plugins_permission_files_warning,
    )

    data object Sensors : PluginPermission("sensors", R.string.core_plugins_permission_sensors_label, Tier.LISTED, sinceApi = 5)

    /** The keyboard's own QR and text scanners; the plugin gets the result only. */
    data object Scan : PluginPermission("scan", R.string.core_plugins_permission_scan_label, Tier.LISTED, sinceApi = 5)

    /** Vibration, the flashlight, reading aloud, the battery level. */
    data object Device : PluginPermission("device", R.string.core_plugins_permission_device_label, Tier.LISTED, sinceApi = 5)

    /** Share sheet and opening links, always behind a sheet that names the target. */
    data object Share : PluginPermission("share", R.string.core_plugins_permission_share_label, Tier.LISTED, sinceApi = 5)

    data object Notifications : PluginPermission("notifications", R.string.core_plugins_permission_notifications_label, Tier.LISTED, sinceApi = 5)

    val isRisky: Boolean get() = tier == Tier.RISKY

    companion object {
        /** Every permission this build understands, in the order lists show them. */
        val all: List<PluginPermission> by lazy {
            listOf(
                Storage, Reminders, SelectedText, FieldWrite, ClipboardWrite, Sensors, Scan, Device, Share,
                Notifications, Network, Ai, FieldRead, ClipboardRead, Location, Files,
            )
        }

        /** The permission [wire] names, or null if this build does not know it. */
        fun parse(wire: String): PluginPermission? {
            val trimmed = wire.trim()
            return all.firstOrNull { it.wire == trimmed }
        }

        /** Permissions that can send data off the phone. */
        val EGRESS: Set<PluginPermission> by lazy { setOf(Network, Ai) }

        /** Permissions that read something the user has. */
        val READS: Set<PluginPermission> by lazy { setOf(SelectedText, FieldRead, ClipboardRead, Files, Location) }

        /**
         * Whether [permissions] together need the stronger warning: something
         * that reads what the user has, beside something that can send it away.
         */
        fun combinationRisk(permissions: Collection<PluginPermission>): Boolean =
            permissions.any { it in EGRESS } && permissions.any { it in READS }

        /** The host-call permission for an API 5 op, the same table as `native/wm-python/src/host/v5.rs`. */
        fun forOp(op: String): PluginPermission? = when (op.substringBefore('.')) {
            "http" -> Network
            "ai" -> Ai
            "field" -> if (op.substringAfter('.') in FIELD_READS) FieldRead else FieldWrite
            "clipboard" -> if (op == "clipboard.get") ClipboardRead else ClipboardWrite
            "location" -> Location
            "files" -> Files
            "sensors" -> Sensors
            "scan" -> Scan
            "device" -> Device
            "share" -> Share
            "notify" -> Notifications
            "secrets" -> Storage
            else -> null
        }

        private val FIELD_READS = setOf("selection", "before", "after", "info")
    }
}

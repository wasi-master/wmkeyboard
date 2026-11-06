package com.wasimaster.wmkeyboard.core.plugins

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.plugins.R

/**
 * What a plugin may do beyond drawing inside its own panel.
 *
 * The list is deliberately short, and the things that are *not* on it matter
 * more than the things that are. A plugin can never read what the user types,
 * read the text field, read the clipboard, or reach the network — not behind a
 * prompt, not behind a grant, not at all. There is no API for any of them, so
 * there is no permission for any of them either.
 *
 * That is a design constraint rather than a starting point. A keyboard sees
 * everything its user types, and third-party code that could read that text and
 * also send it somewhere is the definitional keyboard-malware shape; Google
 * Play's Device and Network Abuse policy names Lua specifically and asks that
 * runtime-loaded interpreted code "must not allow potential violations" — a
 * question about capability, which a consent prompt does not answer. So text
 * reaches a plugin only when the user hands it over on purpose: by typing or
 * pasting into a visible input inside the plugin's own panel, or, with
 * [SelectedText], by selecting text and tapping one of the plugin's own action
 * chips. Either way the user can see exactly what they shared, and the plugin
 * still has nowhere to send it.
 *
 * What remains is local scratch space, which cannot leak anywhere because
 * nothing in the sandbox has anywhere to leak to, and reminders: a line of the
 * plugin's own text that the app shows in the shade at a time the plugin set.
 *
 * A permission string this build does not recognise is a **refused install**,
 * not an ignored line — see [PluginManifestCodec]. A capability that cannot be
 * explained to the user before they install is one that cannot be granted, and
 * refusing is also the forward-compatibility guard: if a later version adds a
 * capability, older builds decline those plugins outright rather than running
 * them with a declaration they silently dropped.
 */
sealed interface PluginPermission {

    /** The exact string a manifest declares. */
    val wire: String

    /**
     * One line for the "This plugin can" list, shown before install and in
     * settings. A resource id rather than the words themselves: this module has
     * no Context, so the screen that draws the list resolves it.
     */
    @get:StringRes
    val labelRes: Int

    /**
     * Per-plugin key/value storage under the plugin's own directory, exposed to
     * the script as `wm.storage`. Local only, quota'd, and deleted when the
     * plugin is uninstalled.
     *
     * Not gated at runtime. With no egress of any kind this is app-local
     * scratch space, and a prompt for it would be noise that teaches users to
     * dismiss prompts without reading them.
     */
    data object Storage : PluginPermission {
        override val wire: String = "storage"

        @StringRes
        override val labelRes: Int = R.string.core_plugins_permission_storage_label
    }

    /**
     * Reminders the app shows at a time the plugin chose, exposed to the script
     * as `wm.reminders`.
     *
     * A reminder is data, not code: the plugin hands over an id, a time and a
     * line of text, and when the time comes **the app** posts the notification.
     * No plugin code runs in the background to do it, and none runs when the
     * reminder fires. The notification always carries the plugin's name as its
     * title, so a reminder cannot pass itself off as the system or another app,
     * and tapping it opens that plugin's settings page, where every pending
     * reminder is listed and can be cancelled.
     *
     * Not gated at runtime, for the same reason as [Storage]: the user sees it
     * before install, and the notification itself names who asked for it.
     */
    data object Reminders : PluginPermission {
        override val wire: String = "reminders"

        @StringRes
        override val labelRes: Int = R.string.core_plugins_permission_reminders_label
    }

    /**
     * Selection actions: chips on the bar over selected text, declared in the
     * manifest's `actions`. A tap calls the script's `transform` with the
     * selection, and the string it returns replaces the selection.
     *
     * This is the one way text from the field reaches a plugin, and only text
     * the user chose: they selected it and tapped this plugin's own chip. Never
     * a password field, never more than the selection, and never without the
     * tap. Each tap runs in a fresh sandbox, so nothing outlives the call except
     * what the plugin writes to its own [Storage], which has no way out of the
     * device either.
     */
    data object SelectedText : PluginPermission {
        override val wire: String = "selection"

        @StringRes
        override val labelRes: Int = R.string.core_plugins_permission_selection_label
    }

    companion object {
        /** Every permission this build understands. */
        val all: List<PluginPermission> = listOf(Storage, Reminders, SelectedText)

        /** The permission [wire] names, or null if this build does not know it. */
        fun parse(wire: String): PluginPermission? {
            val trimmed = wire.trim()
            return all.firstOrNull { it.wire == trimmed }
        }
    }
}

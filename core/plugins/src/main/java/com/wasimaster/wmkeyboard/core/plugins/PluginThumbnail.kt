package com.wasimaster.wmkeyboard.core.plugins

/**
 * What a plugin draws before anyone touches it, for a picture of it in a list.
 *
 * The script runs in the same interpreter, with the same load and render
 * budgets, as it would in the keyboard, but on the calling thread and once:
 * loaded, then drawn. Storage and reminders, when the plugin declares them,
 * live in memory and are gone afterwards. Nothing is logged and nothing is
 * kept. Null as well while the library has not been downloaded.
 */
object PluginThumbnail {

    /** The widgets [script] draws first, or null when it fails to load, has no render, or runs out of budget. */
    fun render(plugin: InstalledPlugin, script: String): RenderedUi? {
        val python = PythonSession.create(
            PythonSession.Config(plugin, PythonSession.Config.PANEL, storagePath = null, remindersPath = null),
        ) ?: return null
        return try {
            python.load(script).takeIf { it.ok }?.ui
        } finally {
            python.close()
        }
    }
}

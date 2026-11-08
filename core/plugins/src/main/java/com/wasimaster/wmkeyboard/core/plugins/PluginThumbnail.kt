package com.wasimaster.wmkeyboard.core.plugins

/**
 * What a plugin draws before anyone touches it, for a picture of it in a list.
 *
 * The script runs in the same interpreter, with the same load and render
 * budgets, as it would in the keyboard, once: loaded, then drawn. Storage and
 * reminders, when the plugin declares them, live in memory and are gone
 * afterwards. Nothing is logged and nothing is kept. Null as well while the
 * library has not been downloaded.
 *
 * Blocks the caller until the script is done (at most its two budgets), but
 * runs it on a thread of its own with the stack the interpreter asks for
 * ([PluginRuntime.PLUGIN_STACK_BYTES]), which a pool thread may not have.
 */
object PluginThumbnail {

    /** The widgets [script] draws first, or null when it fails to load, has no render, or runs out of budget. */
    fun render(plugin: InstalledPlugin, script: String): RenderedUi? {
        var drawn: RenderedUi? = null
        val worker = Thread(null, { drawn = draw(plugin, script) }, "wm-plugin-thumbnail", PluginRuntime.PLUGIN_STACK_BYTES)
        worker.isDaemon = true
        worker.start()
        worker.join()
        return drawn
    }

    private fun draw(plugin: InstalledPlugin, script: String): RenderedUi? {
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

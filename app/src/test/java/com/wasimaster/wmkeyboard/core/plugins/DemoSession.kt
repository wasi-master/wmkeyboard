package com.wasimaster.wmkeyboard.core.plugins

import java.io.Closeable
import java.io.File

/**
 * One plugin script in a real `libwm_python` session, driven the way
 * [PluginRuntime] drives it but on the calling thread: the constructor loads
 * the script and draws it, and every event is handled and drawn again in the
 * same call. What `wm.ui.set_input` wrote is collected in [writes], what the
 * script printed in [log].
 *
 * The library is the host build Gradle makes before the tests run
 * (`:core:plugins:buildHostPython`). A missing library fails here, loudly, the
 * way `NativeEngine`'s tests fail without theirs.
 */
internal class DemoSession(
    val source: String,
    val plugin: InstalledPlugin,
    storage: File? = null,
    reminders: File? = null,
) : Closeable {

    private val python: PythonSession = requireNotNull(
        PythonSession.create(PythonSession.Config(plugin, PythonSession.Config.PANEL, storage?.path, reminders?.path)),
    ) { "libwm_python did not open a session (library loaded: ${PythonNative.available})" }

    val writes = mutableListOf<Pair<String, String>>()
    val log = mutableListOf<String>()

    /** What the last call drew. */
    var ui: RenderedUi = settle(python.load(source), "load")
        private set

    fun event(event: PluginEvent): RenderedUi = settle(python.event(event), event.toString()).also { ui = it }

    fun click(id: String) = event(PluginEvent.Click(id))

    fun input(id: String, text: String) = event(PluginEvent.InputChanged(id, text))

    fun toggle(id: String, on: Boolean) = event(PluginEvent.ToggleChanged(id, on))

    fun date(id: String, seconds: Long?) = event(PluginEvent.DateChanged(id, seconds))

    fun tab(id: String, index: Int) = event(PluginEvent.TabSelected(id, index))

    override fun close() = python.close()

    private fun settle(result: PythonSession.Result, call: String): RenderedUi {
        writes += result.inputs
        log += result.log
        check(result.ok) { "${plugin.name}: $call failed: ${result.error}" }
        return checkNotNull(result.ui) { "${plugin.name}: $call drew nothing" }
    }

    companion object {
        fun resource(name: String): String =
            requireNotNull(DemoSession::class.java.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
                .bufferedReader()
                .use { it.readText() }

        fun plugin(name: String, permissions: List<String>, id: String = "com.example.demo", apiVersion: Int = 1) =
            InstalledPlugin(id = id, name = name, version = "test", permissions = permissions, apiVersion = apiVersion)

        /** The demo `plugins/<name>.py`, loaded and drawn. */
        fun demo(
            name: String,
            permissions: List<String> = emptyList(),
            storage: File? = null,
            reminders: File? = null,
            apiVersion: Int = 1,
        ) = DemoSession(resource("plugins/$name.py"), plugin(name, permissions, apiVersion = apiVersion), storage, reminders)

        /**
         * [actionId] of [source] on [text], in a fresh `action` session, the
         * way the selection bar runs every tap: load, `transform`, free.
         */
        fun transform(source: String, plugin: InstalledPlugin, storage: File?, text: String, actionId: String): PythonSession.Result {
            val python = requireNotNull(
                PythonSession.create(PythonSession.Config(plugin, PythonSession.Config.ACTION, storage?.path, null)),
            ) { "libwm_python did not open a session" }
            try {
                val loaded = python.load(source)
                check(loaded.ok) { "${plugin.name}: load failed: ${loaded.error}" }
                return python.transform(text, actionId)
            } finally {
                python.close()
            }
        }
    }
}

/** Every widget in the tree, depth first, every tab page included. */
internal fun RenderedUi.widgets(): List<PluginWidget> {
    val out = ArrayList<PluginWidget>()
    fun walk(widgets: List<PluginWidget>) {
        for (widget in widgets) {
            out += widget
            when (widget) {
                is PluginWidget.Column -> walk(widget.children)
                is PluginWidget.Row -> walk(widget.children)
                is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                else -> Unit
            }
        }
    }
    walk(root)
    return out
}

/** Every piece of text a person could read anywhere in the tree, one per line, for loose assertions. */
internal fun RenderedUi.allText(): String = widgets().mapNotNull { widget ->
    when (widget) {
        is PluginWidget.Label -> widget.text
        is PluginWidget.Output -> widget.text
        is PluginWidget.Button -> widget.text
        is PluginWidget.Toggle -> widget.label
        else -> null
    }
}.joinToString("\n")

/** Every output block's text, in order. */
internal fun RenderedUi.outputs(): List<String> = widgets().filterIsInstance<PluginWidget.Output>().map { it.text }

package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `wm` members that must never exist: every way a plugin could read typed
 * text, the clipboard, files or the network. A live session with every
 * permission granted has none of them, and the editor's API model names none
 * of them either, so completion can never suggest one.
 *
 * The interpreter's own sandbox (no `open`, no `os`, no imports beyond the six
 * modules) is pinned by `cargo test` in `native/wm-python`.
 */
class PluginForbiddenNamesTest {

    @Test
    fun `a session with every permission reaches none of them`() {
        val probes = FORBIDDEN.joinToString("\n") { name ->
            "try:\n    $name\n    found.append(\"$name\")\nexcept AttributeError:\n    pass"
        }
        val script = "found = []\n$probes\n\ndef render():\n    return ui.label(\",\".join(found))"
        val session = DemoSession(
            script,
            DemoSession.plugin("Probe", PluginPermission.all.map { it.wire }),
        )
        session.use {
            assertEquals("", (it.ui.root.single() as PluginWidget.Label).text)
        }
    }

    @Test
    fun `the editor's API model names none of them`() {
        val paths = PythonTooling.api.entries.map { it.path }
        assertTrue("the API model is empty: is libwm_python loaded?", paths.any { it.startsWith("wm.storage") })
        val leaked = paths.filter { path -> FORBIDDEN.any { path == it || path.startsWith("$it.") } }
        assertEquals(emptyList<String>(), leaked)
    }

    private companion object {
        val FORBIDDEN: List<String> = listOf(
            "wm.text", "wm.clipboard", "wm.http", "wm.net", "wm.field", "wm.insert",
            "wm.keys", "wm.input", "wm.contacts", "wm.files", "wm.fs", "wm.exec",
        )
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginThumbnailTest {

    private fun plugin(permissions: List<String> = emptyList()) = InstalledPlugin(
        id = "com.example.thumbnail",
        name = "Thumbnail",
        version = "1",
        permissions = permissions,
    )

    @Test
    fun `every demo draws a panel with nothing repaired`() {
        val demos = mapOf(
            "text-tools" to emptyList<String>(),
            "cipher-tool" to emptyList<String>(),
            "todo-list" to listOf("storage", "reminders"),
            "ui-kitchen-sink" to emptyList<String>(),
            "math-mode" to listOf("storage"),
            "text-actions" to listOf("storage", "selection"),
        )
        for ((name, permissions) in demos) {
            val drawn = PluginThumbnail.render(plugin(permissions), DemoSession.resource("plugins/$name.py"))
            assertNotNull(name, drawn)
            val ui = drawn!!
            assertTrue(name, ui.root.isNotEmpty())
            assertTrue("$name: ${ui.repairs}", ui.repairs.isEmpty())
        }
    }

    @Test
    fun `storage a thumbnail writes is gone afterwards`() {
        val script = """
            n = int(wm.storage.get("n", "0")) + 1
            wm.storage.set("n", str(n))

            def render():
                return ui.label(str(n))
        """.trimIndent()
        repeat(2) {
            val label = PluginThumbnail.render(plugin(listOf("storage")), script)!!.root.single() as PluginWidget.Label
            assertEquals("1", label.text)
        }
    }

    @Test
    fun `a script that fails, never stops, or cannot draw gives no picture`() {
        assertNull(PluginThumbnail.render(plugin(), "raise ValueError(\"no\")"))
        assertNull(PluginThumbnail.render(plugin(), "while True:\n    pass"))
        assertNull(PluginThumbnail.render(plugin(), "x = 1"))
        assertNull(PluginThumbnail.render(plugin(), "def render():\n    while True:\n        pass"))
        assertNull(PluginThumbnail.render(plugin(), "def render(:"))
    }
}

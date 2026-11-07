package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RenderedUi.repairCodes] must stay index-aligned with [RenderedUi.repairs]. */
class PluginRepairCodesTest {

    private val open = ArrayList<DemoSession>()

    @After
    fun tearDown() {
        open.forEach { it.close() }
    }

    private fun decode(json: String): RenderedUi = PluginUiCodec.fromJson(Json.parseToJsonElement(json) as JsonObject)

    /** What a real script's `render()` comes to, through the library's codec and then this one. */
    private fun render(body: String): RenderedUi =
        DemoSession("def render():\n    return $body", DemoSession.plugin("Repairs", emptyList())).also { open += it }.ui

    @Test
    fun `each repair message carries its code at the same index`() {
        // This side's own repairs come first, in tree order, then the library's.
        val ui = decode(
            """
            {"root": [{"type": "sparkle"}, {}, {"type": "tabs", "id": "t"}],
             "repairs": [{"code": "NOT_A_WIDGET"}]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(PluginRepair.UNKNOWN_TYPE, PluginRepair.NO_TYPE, PluginRepair.TABS_NO_PAGES, PluginRepair.NOT_A_WIDGET),
            ui.repairCodes,
        )
        assertEquals(ui.repairs.size, ui.repairCodes.size)
        assertEquals(PluginText.of(R.string.core_plugins_ui_repair_unknown_type, "sparkle"), ui.repairs[0])
        assertEquals(PluginText.of(R.string.core_plugins_ui_repair_not_a_widget), ui.repairs[3])
    }

    @Test
    fun `a repeated message is dropped together with its code`() {
        val ui = decode("""{"root": [{"type": "a"}, {"type": "a"}, {"type": "b"}]}""")
        assertEquals(listOf(PluginRepair.UNKNOWN_TYPE, PluginRepair.UNKNOWN_TYPE), ui.repairCodes)
        assertEquals(2, ui.repairs.size)
    }

    @Test
    fun `a repair both sides found is said once`() {
        val ui = decode("""{"root": [{"type": "a"}], "repairs": [{"code": "UNKNOWN_TYPE", "arg": "a"}]}""")
        assertEquals(listOf(PluginRepair.UNKNOWN_TYPE), ui.repairCodes)
    }

    @Test
    fun `a clean tree has no codes`() {
        assertTrue(decode("""{"root": [{"type": "label", "text": "hi"}]}""").repairCodes.isEmpty())
        assertTrue(render("ui.label(\"hi\")").repairCodes.isEmpty())
    }

    @Test
    fun `what a script got wrong arrives as codes`() {
        val ui = render("[42, {\"type\": \"sparkle\"}, {}, ui.label(\"kept\")]")
        assertEquals(setOf(PluginRepair.NOT_A_WIDGET, PluginRepair.UNKNOWN_TYPE, PluginRepair.NO_TYPE), ui.repairCodes.toSet())
        assertEquals(ui.repairs.size, ui.repairCodes.size)
        assertTrue(PluginText.of(R.string.core_plugins_ui_repair_unknown_type, "sparkle") in ui.repairs)
        assertEquals(listOf<PluginWidget>(PluginWidget.Label("kept", PluginLabelStyle.BODY)), ui.root)
    }
}

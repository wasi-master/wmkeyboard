package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginUiCodec.fromJson]: the finished tree `libwm_python` hands back, read
 * into widgets. The library validates and repairs the tree first; this side
 * reads it defensively and applies the same caps again, so a library from
 * another build can at worst draw less. Fed hand-written JSON, in the shape
 * the library writes: `{root: [...], repairs: [{code, arg}]}`.
 */
class PluginUiCodecTest {

    private fun decode(json: String): RenderedUi = PluginUiCodec.fromJson(Json.parseToJsonElement(json) as JsonObject)

    /** A tree of [nodes] at the top level and no repairs. */
    private fun tree(vararg nodes: String): RenderedUi = decode("""{"root": [${nodes.joinToString(",")}]}""")

    /**
     * True when one of the repairs is [res]. The assertions name the resource
     * rather than the English, so rewording a message does not fail a test that
     * is about what the codec dropped.
     */
    private fun RenderedUi.reports(res: Int): Boolean = repairs.any { it is PluginText.Resource && it.textRes == res }

    private fun label(text: String) = """{"type": "label", "text": "$text"}"""

    // ---- the vocabulary ---------------------------------------------------

    @Test
    fun `every widget survives the trip`() {
        val ui = tree(
            """
            {"type": "column", "children": [
              {"type": "label", "text": "Title", "style": "title"},
              {"type": "input", "id": "msg", "label": "Message", "placeholder": "type here"},
              {"type": "row", "children": [
                {"type": "button", "id": "go", "text": "Encode", "primary": true},
                {"type": "button", "id": "stop", "text": "Off", "enabled": false}
              ]},
              {"type": "toggle", "id": "caps", "label": "Uppercase", "checked": true},
              {"type": "output", "id": "out", "text": "result", "mono": true},
              {"type": "divider"},
              {"type": "spacer", "height": 12},
              {"type": "progress"},
              {"type": "label", "text": "small", "style": "caption"}
            ]}
            """.trimIndent(),
        )

        val column = ui.root.single() as PluginWidget.Column
        assertEquals(PluginWidget.Label("Title", PluginLabelStyle.TITLE), column.children[0])
        assertEquals(PluginWidget.Input("msg", "Message", "type here"), column.children[1])

        val row = column.children[2] as PluginWidget.Row
        assertEquals(PluginWidget.Button("go", "Encode", primary = true, enabled = true), row.children[0])
        assertEquals(PluginWidget.Button("stop", "Off", primary = false, enabled = false), row.children[1])

        assertEquals(PluginWidget.Toggle("caps", "Uppercase", checked = true), column.children[3])

        // Insert and Copy are on unless the plugin opts out: the host's Insert
        // button is the only route from a plugin to the user's text.
        assertEquals(PluginWidget.Output("out", "result", mono = true, insertable = true, copyable = true), column.children[4])

        assertEquals(PluginWidget.Divider, column.children[5])
        assertEquals(PluginWidget.Spacer(12), column.children[6])
        assertEquals(PluginWidget.Progress, column.children[7])
        assertEquals(PluginLabelStyle.CAPTION, (column.children[8] as PluginWidget.Label).style)
        assertTrue(ui.repairs.isEmpty())
        assertTrue(ui.repairCodes.isEmpty())
    }

    @Test
    fun `missing fields take their defaults`() {
        val ui = tree(
            """{"type": "label"}""",
            """{"type": "label", "text": "x", "style": "shouting"}""",
            """{"type": "output", "text": "o", "insertable": false, "copyable": false}""",
            """{"type": "button", "id": "b"}""",
            """{"type": "toggle", "id": "t"}""",
            """{"type": "spacer"}""",
        )
        assertEquals(
            listOf(
                PluginWidget.Label("", PluginLabelStyle.BODY),
                PluginWidget.Label("x", PluginLabelStyle.BODY),
                PluginWidget.Output("", "o", mono = false, insertable = false, copyable = false),
                PluginWidget.Button("b", "", primary = false, enabled = true),
                PluginWidget.Toggle("t", "", checked = false),
                PluginWidget.Spacer(8),
            ),
            ui.root,
        )
    }

    @Test
    fun `a date keeps whole seconds in range, its time switch and a step in range`() {
        val date = tree("""{"type": "date", "id": "due", "label": "Due", "value": 1790000000, "time": true, "step": 500}""")
            .root.single() as PluginWidget.DatePicker
        assertEquals(PluginWidget.DatePicker("due", "Due", 1_790_000_000L, time = true, step = 60), date)

        for (value in listOf("\"tomorrow\"", "1e300", "1790000000.5", "253402300800", "-62135596801", "null")) {
            val other = tree("""{"type": "date", "id": "d", "value": $value}""").root.single() as PluginWidget.DatePicker
            assertEquals(value, null, other.value)
            assertEquals(15, other.step)
            assertFalse(other.time)
        }
        assertEquals(1, (tree("""{"type": "date", "id": "d", "step": 0}""").root.single() as PluginWidget.DatePicker).step)
    }

    @Test
    fun `tabs carry their pages`() {
        val ui = tree(
            """
            {"type": "tabs", "id": "modes", "pages": [
              {"title": "Caesar", "children": [${label("one")}]},
              {"title": "Vigenere", "children": [${label("two")}]}
            ]}
            """.trimIndent(),
        )
        val tabs = ui.root.single() as PluginWidget.Tabs
        assertEquals("modes", tabs.id)
        assertEquals(listOf("Caesar", "Vigenere"), tabs.pages.map { it.title })
        assertEquals("two", (tabs.pages[1].children.single() as PluginWidget.Label).text)
    }

    @Test
    fun `input ids are collected for the host to keep buffers for`() {
        val ui = tree(
            """
            {"type": "column", "children": [
              {"type": "input", "id": "a"},
              {"type": "tabs", "id": "t", "pages": [{"title": "p", "children": [{"type": "input", "id": "b"}]}]}
            ]}
            """.trimIndent(),
        )
        assertEquals(setOf("a", "b"), ui.inputIds())
    }

    // ---- what the library repaired -------------------------------------------

    @Test
    fun `the library's repairs become this build's words`() {
        val ui = decode(
            """
            {"root": [${label("kept")}], "repairs": [
              {"code": "NOT_A_WIDGET"},
              {"code": "UNKNOWN_TYPE", "arg": "webview"},
              {"code": "SOMETHING_NEWER", "arg": "x"},
              {"code": "TOO_MANY_TABS"}
            ]}
            """.trimIndent(),
        )
        assertEquals("kept", (ui.root.single() as PluginWidget.Label).text)
        // A code this build does not know is skipped rather than shown as nothing.
        assertEquals(listOf(PluginRepair.NOT_A_WIDGET, PluginRepair.UNKNOWN_TYPE, PluginRepair.TOO_MANY_TABS), ui.repairCodes)
        assertEquals(PluginText.of(R.string.core_plugins_ui_repair_unknown_type, "webview"), ui.repairs[1])
        assertEquals(PluginText.of(R.string.core_plugins_ui_repair_too_many_tabs, PluginUiCodec.MAX_TABS), ui.repairs[2])
    }

    @Test
    fun `a repair's argument is cut short`() {
        val ui = decode("""{"root": [], "repairs": [{"code": "UNKNOWN_TYPE", "arg": "${"w".repeat(500)}"}]}""")
        assertEquals(PluginText.of(R.string.core_plugins_ui_repair_unknown_type, "w".repeat(24)), ui.repairs.single())
    }

    @Test
    fun `repairs are capped`() {
        val repairs = (1..20).joinToString(",") { """{"code": "UNKNOWN_TYPE", "arg": "t$it"}""" }
        val ui = decode("""{"root": [], "repairs": [$repairs]}""")
        assertEquals(8, ui.repairs.size)
        assertEquals(ui.repairs.size, ui.repairCodes.size)
    }

    // ---- hostile and broken trees -----------------------------------------

    @Test
    fun `an unknown widget type is dropped and reported`() {
        val ui = tree("""{"type": "column", "children": [{"type": "webview", "url": "http://evil"}]}""")
        val column = ui.root.single() as PluginWidget.Column
        assertTrue(column.children.isEmpty())
        val repair = ui.repairs.single() as PluginText.Resource
        assertEquals(R.string.core_plugins_ui_repair_unknown_type, repair.textRes)
        assertEquals("webview", repair.arg1)
    }

    @Test
    fun `a node with no type is dropped and reported`() {
        val ui = tree("""{"text": "orphan"}""", """{"type": ""}""", label("kept"))
        assertEquals(listOf<PluginWidget>(PluginWidget.Label("kept", PluginLabelStyle.BODY)), ui.root)
        assertEquals(listOf(PluginRepair.NO_TYPE), ui.repairCodes)
    }

    @Test
    fun `nothing at all renders as nothing at all`() {
        assertEquals(RenderedUi.EMPTY.root, decode("{}").root)
        assertEquals(RenderedUi.EMPTY.root, decode("""{"root": null}""").root)
        assertEquals(RenderedUi.EMPTY.root, decode("""{"root": 42}""").root)
        // Values that are not objects are skipped quietly: reporting them is the library's job.
        assertEquals(listOf<PluginWidget>(PluginWidget.Divider), decode("""{"root": [42, "x", [], {"type": "divider"}]}""").root)
        assertTrue(decode("""{"root": [42]}""").repairs.isEmpty())
    }

    @Test
    fun `a tree nested too deep is cut off`() {
        var node = label("bottom")
        repeat(20) { node = """{"type": "column", "children": [$node]}""" }
        val ui = tree(node)
        assertTrue(ui.reports(R.string.core_plugins_ui_repair_too_deep))
        assertFalse(ui.allText().contains("bottom"))
    }

    @Test
    fun `a huge tree is truncated rather than drawn`() {
        val ui = tree("""{"type": "column", "children": [${(1..5000).joinToString(",") { label("row $it") }}]}""")
        val column = ui.root.single() as PluginWidget.Column
        assertTrue(column.children.size < PluginUiCodec.MAX_NODES)
        assertTrue(ui.reports(R.string.core_plugins_ui_repair_too_many_widgets))
        assertEquals("the message is said once", 1, ui.repairCodes.count { it == PluginRepair.TOO_MANY_WIDGETS })
    }

    @Test
    fun `one enormous string is shortened`() {
        val ui = tree(label("x".repeat(100_000)))
        val label = ui.root.single() as PluginWidget.Label
        assertEquals(PluginUiCodec.MAX_TEXT, label.text.length)
        assertTrue(ui.reports(R.string.core_plugins_ui_repair_text_shortened))
    }

    @Test
    fun `many large strings are capped across the whole tree`() {
        val ui = tree("""{"type": "column", "children": [${(1..200).joinToString(",") { label("y".repeat(2000)) }}]}""")
        val column = ui.root.single() as PluginWidget.Column
        val total = column.children.filterIsInstance<PluginWidget.Label>().sumOf { it.text.length }
        assertTrue("total text was $total", total <= PluginUiCodec.MAX_TOTAL_TEXT)
        assertTrue(ui.reports(R.string.core_plugins_ui_repair_text_budget))
    }

    @Test
    fun `only the first few tabs are shown`() {
        val pages = (1..30).joinToString(",") { """{"title": "t$it", "children": []}""" }
        val tabs = tree("""{"type": "tabs", "id": "many", "pages": [$pages]}""").root.single() as PluginWidget.Tabs
        assertEquals(PluginUiCodec.MAX_TABS, tabs.pages.size)
        assertEquals("t8", tabs.pages.last().title)
    }

    @Test
    fun `a tab strip with no pages is dropped`() {
        val ui = tree("""{"type": "tabs", "id": "empty"}""", """{"type": "tabs", "id": "junk", "pages": [1, 2]}""")
        assertTrue(ui.root.isEmpty())
        assertTrue(ui.reports(R.string.core_plugins_ui_repair_tabs_no_pages))
    }

    @Test
    fun `a spacer cannot push the panel apart and an id cannot run on`() {
        assertEquals(PluginWidget.Spacer(64), tree("""{"type": "spacer", "height": 100000}""").root.single())
        assertEquals(PluginWidget.Spacer(0), tree("""{"type": "spacer", "height": -5}""").root.single())
        val button = tree("""{"type": "button", "id": "${"i".repeat(500)}", "text": "b"}""").root.single() as PluginWidget.Button
        assertEquals(64, button.id.length)
    }
}

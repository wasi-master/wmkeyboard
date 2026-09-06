package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.io.File

/**
 * Math Mode, the demo plugin that is a real text engine, run against the real
 * sandbox.
 *
 * The plugin turns `x^2 + sqrt(2)` into `x² + √2` after every keystroke, so
 * almost all of its behaviour is a table of input → output rows. That table
 * lives in `plugins/math-mode.cases.txt`; every row goes in through an
 * `input_changed` event and must come back out of the output block exactly.
 * The script and the corpus are mirrored from `plugins-src/math-mode/` in the
 * addon repository, where `test.lua` runs the same rows on the luaj command
 * line — edit them there and copy across.
 *
 * Two things only this side can prove: that the host's own codec and caps are
 * happy with what the script produces (astral glyphs cross the boundary as
 * CESU-8, output blocks stay under 2048 UTF-16 units, no repairs), and that an
 * 8 KB paste converts inside the per-event budget.
 */
class MathModePluginTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Demo(val globals: Globals, val budget: PluginBudget, val writes: MutableList<Pair<String, String>>)

    private fun load(storageFile: File = File(temp.newFolder("storage-${counter++}"), "storage.json")): Demo {
        val writes = mutableListOf<Pair<String, String>>()
        val budget = PluginBudget()
        val globals = PluginSandbox.create(budget) { }
        val api = PluginHostApi(
            plugin = InstalledPlugin(id = "com.wasimaster.mathmode", name = "Math Mode", permissions = listOf("storage")),
            log = PluginLog(null),
            storage = PluginStorage(storageFile),
            setInput = { id, text -> writes += id to text },
            revoked = { false },
        )
        globals.set("wm", api.table())
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        PluginSandbox.compile(globals, resource("plugins/math-mode.lua"), "@math-mode.lua").call()
        return Demo(globals, budget, writes)
    }

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun Demo.render(): RenderedUi {
        budget.begin(PluginLimit.RENDER)
        return PluginUiCodec.fromLua(globals.get("render").call())
    }

    private fun Demo.event(type: String, id: String, value: Any? = null) {
        budget.begin(PluginLimit.EVENT)
        val table = LuaTable()
        table.set("type", type)
        table.set("id", id)
        when (value) {
            is String -> table.set("value", value)
            is Boolean -> table.set("value", LuaValue.valueOf(value))
            else -> Unit
        }
        globals.get("on_event").call(table)
    }

    private fun Demo.type(text: String) = event("input_changed", "expr", text)

    /** Sets every option: the defaults, then whatever the corpus row flips. */
    private fun Demo.options(flags: List<String> = emptyList()) {
        val values = DEFAULTS.toMutableMap()
        for (flag in flags) {
            val (key, on) = FLAGS[flag] ?: error("unknown corpus flag $flag")
            values[key] = on
        }
        for ((key, on) in values) event("toggle", key, on)
    }

    /** Every output block's text, in order. */
    private fun RenderedUi.outputs(): List<String> {
        val found = mutableListOf<String>()
        fun walk(widgets: List<PluginWidget>) {
            for (widget in widgets) {
                when (widget) {
                    is PluginWidget.Output -> found += widget.text
                    is PluginWidget.Column -> walk(widget.children)
                    is PluginWidget.Row -> walk(widget.children)
                    is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                    else -> Unit
                }
            }
        }
        walk(root)
        return found
    }

    private fun RenderedUi.toggles(): Map<String, Boolean> {
        val found = mutableMapOf<String, Boolean>()
        fun walk(widgets: List<PluginWidget>) {
            for (widget in widgets) {
                when (widget) {
                    is PluginWidget.Toggle -> found[widget.id] = widget.checked
                    is PluginWidget.Column -> walk(widget.children)
                    is PluginWidget.Row -> walk(widget.children)
                    is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                    else -> Unit
                }
            }
        }
        walk(root)
        return found
    }

    private fun RenderedUi.result(): String = outputs().joinToString("")

    private data class Case(val line: Int, val flags: List<String>, val input: String, val expected: String)

    private fun corpus(): List<Case> =
        resource("plugins/math-mode.cases.txt").lines().mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val fields = line.split('\t')
            val (flags, input, expected) = if (fields[0].startsWith("@")) {
                Triple(fields[0].drop(1).split(','), fields[1], fields[2])
            } else {
                Triple(emptyList(), fields[0], fields[1])
            }
            Case(index + 1, flags, input.unescaped(), expected.unescaped())
        }

    /** The corpus writes control characters as control pictures so LaTeX backslashes stay literal. */
    private fun String.unescaped() = replace('␊', '\n').replace('␍', '\r').replace('␉', '\t')

    // ---- the corpus --------------------------------------------------------

    @Test
    fun `every corpus row converts through the real sandbox`() {
        val demo = load()
        val cases = corpus()
        assertTrue("corpus is empty", cases.size > 500)
        val failures = mutableListOf<String>()
        for (case in cases) {
            demo.options(case.flags)
            demo.type(case.input)
            val ui = demo.render()
            val got = ui.result()
            if (got != case.expected) {
                failures += "line ${case.line}: ${case.input.show()}\n    expected  ${case.expected.show()}\n    got       ${got.show()}"
            }
            if (ui.repairs.isNotEmpty()) failures += "line ${case.line}: repairs ${ui.repairs}"
        }
        assertTrue("${failures.size} of ${cases.size} rows failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun String.show() = replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ---- the panel ---------------------------------------------------------

    @Test
    fun `loads and renders without repairs`() {
        val ui = load().render()
        assertTrue(ui.root.isNotEmpty())
        assertEquals(emptyList<PluginText>(), ui.repairs)
        assertEquals("an empty box shows a caption, not an output block", emptyList<String>(), ui.outputs())
    }

    @Test
    fun `a symbol tap appends to the box and writes it back once`() {
        val demo = load()
        demo.type("x^2")
        demo.writes.clear()
        demo.event("click", "sym:π")
        assertEquals(listOf("expr" to "x^2π"), demo.writes)
        assertEquals("x²π", demo.render().result())
    }

    @Test
    fun `options persist through storage across a reload`() {
        val storage = File(temp.newFolder("shared"), "storage.json")
        val first = load(storage)
        first.event("toggle", "chem", true)
        first.type("H2O")
        assertEquals("H₂O", first.render().result())

        val second = load(storage)
        val ui = second.render()
        assertEquals(true, ui.toggles()["chem"])
        second.type("H2O")
        assertEquals("H₂O", second.render().result())

        second.event("click", "reset_opts")
        assertEquals(false, second.render().toggles()["chem"])
        assertEquals("H2O", second.render().result())
    }

    @Test
    fun `clear empties the box`() {
        val demo = load()
        demo.type("x^2")
        demo.writes.clear()
        demo.event("click", "clear")
        assertEquals(listOf("expr" to ""), demo.writes)
        assertEquals(emptyList<String>(), demo.render().outputs())
    }

    // ---- caps and budgets --------------------------------------------------

    @Test
    fun `adversarial input stays inside the budget and the widget caps`() {
        val demo = load()
        val unchanged = listOf(
            "^".repeat(8192),
            "(".repeat(8192),
            "(".repeat(4096) + "x" + ")".repeat(4096) + "^2",
            "1" + "/2".repeat(2000),
            "z".repeat(8192),
            "\\frac{".repeat(1000),
            "😀".repeat(2048),
        )
        for (input in unchanged) {
            demo.type(input)
            val ui = demo.render()
            assertEquals(emptyList<PluginText>(), ui.repairs)
            assertEquals(input, ui.result())
            for (block in ui.outputs()) assertTrue("block of ${block.length} chars", block.length <= 2000)
        }
        demo.type("a" + "^b".repeat(2000))
        assertEquals(emptyList<PluginText>(), demo.render().repairs)
        demo.type("\"".repeat(4000))
        assertEquals(emptyList<PluginText>(), demo.render().repairs)
    }

    @Test
    fun `a long result is split into blocks that join back to the whole`() {
        val demo = load()
        demo.type("x^2\n".repeat(2000))
        val ui = demo.render()
        assertEquals(emptyList<PluginText>(), ui.repairs)
        val blocks = ui.outputs()
        assertTrue("expected several blocks, got ${blocks.size}", blocks.size >= 2)
        for (block in blocks) assertTrue(block.length <= 2000)
        assertEquals("x²\n".repeat(2000), blocks.joinToString(""))
    }

    @Test
    fun `astral characters survive the trip through the sandbox`() {
        val demo = load()
        demo.type("𝔸^2 😀 bb(A) FF")
        assertEquals("𝔸² 😀 𝐀 𝔽", demo.render().result())
    }

    private companion object {
        var counter = 0

        val DEFAULTS = mapOf(
            "minus" to true, "times" to true, "frac" to true, "small" to true, "words" to false,
            "prime" to true, "chem" to false, "italic" to false, "rootline" to false,
        )

        /** Corpus flag → (option key, value). */
        val FLAGS = mapOf(
            "chem" to ("chem" to true),
            "nominus" to ("minus" to false),
            "dot" to ("times" to false),
            "words" to ("words" to true),
            "rootline" to ("rootline" to true),
            "italic" to ("italic" to true),
            "noprime" to ("prime" to false),
            "nofrac" to ("frac" to false),
            "nosmall" to ("small" to false),
        )
    }
}

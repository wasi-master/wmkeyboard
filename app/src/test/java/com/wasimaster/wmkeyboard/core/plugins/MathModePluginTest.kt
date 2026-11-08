package com.wasimaster.wmkeyboard.core.plugins

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Math Mode, the demo plugin that is a real text engine, run in the real
 * interpreter.
 *
 * The plugin turns `x^2 + sqrt(2)` into `x² + √2` after every keystroke, so
 * almost all of its behaviour is a table of input → output rows. That table
 * lives in `plugins/math-mode.cases.txt`; every row goes in through an
 * `input_changed` event and must come back out of the output blocks exactly.
 * The script and the corpus are mirrored from `plugins-src/math-mode/` in the
 * addon repository, where `test.py` runs the same rows on `wm-python-cli`:
 * edit them there and copy across.
 *
 * Two things only this side can prove: that the app's codec and caps are
 * happy with what the script produces (astral glyphs cross the JSON boundary
 * whole, output blocks stay under 2048 UTF-16 units, no repairs), and that an
 * 8 KB paste converts inside the per-event budget the keyboard gives it. A
 * call that ran out of budget fails its row, since the session reports it as
 * an error rather than a panel.
 */
class MathModePluginTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val open = ArrayList<DemoSession>()

    @After
    fun tearDown() {
        open.forEach { it.close() }
    }

    private fun load(storage: File = File(temp.newFolder(), PluginStore.STORAGE_FILE)): DemoSession =
        DemoSession(
            DemoSession.resource("plugins/math-mode.py"),
            DemoSession.plugin("Math Mode", listOf("storage"), id = "com.wasimaster.mathmode"),
            storage,
        ).also { open += it }

    private fun DemoSession.type(text: String): RenderedUi = input("expr", text)

    /** The options each toggle was last sent, so a row only sends what changes. */
    private val sent = HashMap<DemoSession, MutableMap<String, Boolean>>()

    /** Sets every option: the defaults, then whatever the corpus row flips. */
    private fun DemoSession.options(flags: List<String> = emptyList()) {
        val values = DEFAULTS.toMutableMap()
        for (flag in flags) {
            val (key, on) = FLAGS[flag] ?: error("unknown corpus flag $flag")
            values[key] = on
        }
        val current = sent.getOrPut(this) { DEFAULTS.toMutableMap() }
        for ((key, on) in values) {
            if (current[key] != on) {
                toggle(key, on)
                current[key] = on
            }
        }
    }

    private fun RenderedUi.toggles(): Map<String, Boolean> =
        widgets().filterIsInstance<PluginWidget.Toggle>().associate { it.id to it.checked }

    private fun RenderedUi.result(): String = outputs().joinToString("")

    private data class Case(val line: Int, val flags: List<String>, val input: String, val expected: String)

    private fun corpus(): List<Case> =
        DemoSession.resource("plugins/math-mode.cases.txt").lines().mapIndexedNotNull { index, raw ->
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

    private fun String.show() = replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ---- the corpus --------------------------------------------------------

    @Test
    fun `every corpus row converts through the real interpreter`() {
        val demo = load()
        val cases = corpus()
        assertTrue("corpus is empty", cases.size > 500)
        val failures = mutableListOf<String>()
        for (case in cases) {
            demo.options(case.flags)
            // A call that failed (out of budget, say) is that row's failure, not the run's.
            val ui = try {
                demo.type(case.input)
            } catch (failure: IllegalStateException) {
                failures += "line ${case.line}: ${case.input.show()}: ${failure.message}"
                null
            } ?: continue
            val got = ui.result()
            if (got != case.expected) {
                failures += "line ${case.line}: ${case.input.show()}\n    expected  ${case.expected.show()}\n    got       ${got.show()}"
            }
            if (ui.repairs.isNotEmpty()) failures += "line ${case.line}: repairs ${ui.repairCodes}"
        }
        assertTrue("${failures.size} of ${cases.size} rows failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ---- the panel ---------------------------------------------------------

    @Test
    fun `loads and renders without repairs`() {
        val ui = load().ui
        assertTrue(ui.root.isNotEmpty())
        assertEquals(emptyList<PluginText>(), ui.repairs)
        assertEquals("an empty box shows a caption, not an output block", emptyList<String>(), ui.outputs())
    }

    @Test
    fun `a symbol tap appends to the box and writes it back once`() {
        val demo = load()
        demo.type("x^2")
        demo.writes.clear()
        val ui = demo.click("sym:π")
        assertEquals(listOf("expr" to "x^2π"), demo.writes)
        assertEquals("x²π", ui.result())
    }

    @Test
    fun `options persist through storage across a reload`() {
        val storage = File(temp.newFolder("shared"), PluginStore.STORAGE_FILE)
        val first = load(storage)
        first.toggle("chem", true)
        assertEquals("H₂O", first.type("H2O").result())
        first.close()

        val second = load(storage)
        assertEquals(true, second.ui.toggles()["chem"])
        assertEquals("H₂O", second.type("H2O").result())

        val reset = second.click("reset_opts")
        assertEquals(false, reset.toggles()["chem"])
        assertEquals("H2O", reset.result())
    }

    @Test
    fun `clear empties the box`() {
        val demo = load()
        demo.type("x^2")
        demo.writes.clear()
        val ui = demo.click("clear")
        assertEquals(listOf("expr" to ""), demo.writes)
        assertEquals(emptyList<String>(), ui.outputs())
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
            val ui = demo.type(input)
            assertEquals(emptyList<PluginText>(), ui.repairs)
            assertEquals(input, ui.result())
            for (block in ui.outputs()) assertTrue("block of ${block.length} chars", block.length <= 2000)
        }
        assertEquals(emptyList<PluginText>(), demo.type("a" + "^b".repeat(2000)).repairs)
        assertEquals(emptyList<PluginText>(), demo.type("\"".repeat(4000)).repairs)
    }

    @Test
    fun `a long result is split into blocks that join back to the whole`() {
        val ui = load().type("x^2\n".repeat(2000))
        assertEquals(emptyList<PluginText>(), ui.repairs)
        val blocks = ui.outputs()
        assertTrue("expected several blocks, got ${blocks.size}", blocks.size >= 2)
        for (block in blocks) assertTrue(block.length <= 2000)
        assertEquals("x²\n".repeat(2000), blocks.joinToString(""))
    }

    @Test
    fun `astral characters survive the trip through the interpreter`() {
        assertEquals("𝔸² 😀 𝐀 𝔽", load().type("𝔸^2 😀 bb(A) FF").result())
    }

    private companion object {
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

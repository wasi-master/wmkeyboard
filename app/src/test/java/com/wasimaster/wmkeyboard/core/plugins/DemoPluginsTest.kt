package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.luaj.vm2.Globals
import java.io.File

/**
 * The published demo plugins, run against the real sandbox.
 *
 * These five are what the documentation teaches from and what most people will
 * copy, so "it loads, it renders, its buttons do the thing" is worth asserting
 * rather than assuming. Every one of them also has to survive the sandbox with
 * no special treatment — if a demo needed something the sandbox does not offer,
 * that would be a documentation bug shipped to every reader.
 *
 * The sources are mirrored from `plugins-src/` in the addon repository. Edit
 * them there and copy across; `tools/build_plugins.py --check` guards the other
 * half of that pairing.
 */
class DemoPluginsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Demo(val globals: Globals, val budget: PluginBudget, val writes: MutableList<Pair<String, String>>)

    private fun load(name: String, permissions: List<String> = emptyList()): Demo {
        val source = javaClass.classLoader!!.getResourceAsStream("plugins/$name.lua")!!
            .bufferedReader().readText()
        val writes = mutableListOf<Pair<String, String>>()
        val budget = PluginBudget()
        val globals = PluginSandbox.create(budget) { }
        val storageFile = File(temp.newFolder("storage-${counter++}"), "storage.json")
        val api = PluginHostApi(
            plugin = InstalledPlugin(id = "com.example.demo", name = name, permissions = permissions),
            log = PluginLog(null),
            storage = if ("storage" in permissions) PluginStorage(storageFile) else null,
            setInput = { id, text -> writes += id to text },
            revoked = { false },
        )
        globals.set("wm", api.table())
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        PluginSandbox.compile(globals, source, "@$name.lua").call()
        return Demo(globals, budget, writes)
    }

    private fun Demo.render(): RenderedUi {
        budget.begin(PluginLimit.RENDER)
        return PluginUiCodec.fromLua(globals.get("render").call())
    }

    private fun Demo.event(type: String, id: String, value: Any? = null) {
        budget.begin(PluginLimit.EVENT)
        val table = org.luaj.vm2.LuaTable()
        table.set("type", type)
        table.set("id", id)
        when (value) {
            is String -> table.set("value", value)
            is Boolean -> table.set("value", org.luaj.vm2.LuaValue.valueOf(value))
            is Int -> table.set("index", value)
            else -> Unit
        }
        globals.get("on_event").call(table)
    }

    /** Every piece of text anywhere in a rendered tree, for loose assertions. */
    private fun RenderedUi.allText(): String {
        val out = StringBuilder()
        fun walk(widgets: List<PluginWidget>) {
            for (widget in widgets) {
                when (widget) {
                    is PluginWidget.Label -> out.append(widget.text).append('\n')
                    is PluginWidget.Output -> out.append(widget.text).append('\n')
                    is PluginWidget.Button -> out.append(widget.text).append('\n')
                    is PluginWidget.Toggle -> out.append(widget.label).append('\n')
                    is PluginWidget.Column -> walk(widget.children)
                    is PluginWidget.Row -> walk(widget.children)
                    is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                    is PluginWidget.Input,
                    is PluginWidget.Spacer,
                    PluginWidget.Divider,
                    PluginWidget.Progress,
                    -> Unit
                }
            }
        }
        walk(root)
        return out.toString()
    }

    // ---- every demo at least works ----------------------------------------

    @Test
    fun `every demo loads and renders without repairs`() {
        for (name in listOf("cipher-tool", "ui-kitchen-sink", "todo-list", "text-tools", "math-mode")) {
            val permissions = if (name == "todo-list" || name == "math-mode") listOf("storage") else emptyList()
            val ui = load(name, permissions).render()
            assertTrue("$name rendered nothing", ui.root.isNotEmpty())
            assertEquals("$name needed repairs: ${ui.repairs}", emptyList<PluginText>(), ui.repairs)
        }
    }

    // ---- cipher ------------------------------------------------------------

    @Test
    fun `caesar encodes and decodes`() {
        val demo = load("cipher-tool")
        demo.event("input_changed", "message", "Hello, World!")
        demo.event("input_changed", "shift", "3")
        demo.event("click", "caesar_encode")
        assertTrue(demo.render().allText().contains("Khoor, Zruog!"))

        demo.event("input_changed", "message", "Khoor, Zruog!")
        demo.event("click", "caesar_decode")
        assertTrue(demo.render().allText().contains("Hello, World!"))
    }

    @Test
    fun `vigenere matches the textbook vector`() {
        // ATTACKATDAWN with key LEMON is LXFOPVEFRNHR in every reference.
        val demo = load("cipher-tool")
        demo.event("input_changed", "message", "ATTACKATDAWN")
        demo.event("input_changed", "keyword", "LEMON")
        demo.event("click", "vigenere_encode")
        assertTrue(demo.render().allText().contains("LXFOPVEFRNHR"))

        demo.event("input_changed", "message", "LXFOPVEFRNHR")
        demo.event("click", "vigenere_decode")
        assertTrue(demo.render().allText().contains("ATTACKATDAWN"))
    }

    // ---- todo --------------------------------------------------------------

    @Test
    fun `todo adds ticks and clears`() {
        val demo = load("todo-list", listOf("storage"))
        demo.event("input_changed", "draft", "buy milk")
        demo.event("click", "add")
        assertTrue(demo.render().allText().contains("buy milk"))
        // Adding clears the box through the host, not by writing to it directly.
        assertEquals(listOf("draft" to ""), demo.writes)

        demo.event("toggle", "done:1", true)
        assertTrue(demo.render().allText().contains("0 of 1 left"))

        demo.event("click", "clear_done")
        assertTrue(demo.render().allText().contains("Nothing on the list"))
    }

    @Test
    fun `todo remembers its list through storage`() {
        val folder = temp.newFolder("shared")
        val storageFile = File(folder, "storage.json")

        fun session(): Demo {
            val source = javaClass.classLoader!!.getResourceAsStream("plugins/todo-list.lua")!!
                .bufferedReader().readText()
            val writes = mutableListOf<Pair<String, String>>()
            val budget = PluginBudget()
            val globals = PluginSandbox.create(budget) { }
            val api = PluginHostApi(
                plugin = InstalledPlugin(id = "com.example.demo", permissions = listOf("storage")),
                log = PluginLog(null),
                storage = PluginStorage(storageFile),
                setInput = { id, text -> writes += id to text },
                revoked = { false },
            )
            globals.set("wm", api.table())
            budget.begin(PluginLimit.LOAD)
            PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
            PluginSandbox.compile(globals, source, "@todo.lua").call()
            return Demo(globals, budget, writes)
        }

        val first = session()
        first.event("input_changed", "draft", "water the plants")
        first.event("click", "add")

        // A second session, as if the keyboard had been closed and reopened.
        assertTrue(session().render().allText().contains("water the plants"))
    }

    // ---- text tools --------------------------------------------------------

    @Test
    fun `text tools change case`() {
        val demo = load("text-tools")
        demo.event("input_changed", "text", "hello there")
        demo.event("click", "upper")
        assertTrue(demo.render().allText().contains("HELLO THERE"))
        demo.event("click", "title")
        assertTrue(demo.render().allText().contains("Hello There"))
    }

    @Test
    fun `text tools round trip every encoding`() {
        val demo = load("text-tools")
        val original = "Hello, World! 123"
        for ((encode, decode) in listOf(
            "b64_encode" to "b64_decode",
            "hex_encode" to "hex_decode",
            "url_encode" to "url_decode",
        )) {
            demo.event("input_changed", "text", original)
            demo.event("click", encode)
            val encoded = demo.render().outputText()
            assertTrue("$encode produced nothing", encoded.isNotEmpty())

            demo.event("input_changed", "text", encoded)
            demo.event("click", decode)
            assertEquals("$encode/$decode did not round trip", original, demo.render().outputText())
        }
    }

    @Test
    fun `base64 matches a known value`() {
        val demo = load("text-tools")
        demo.event("input_changed", "text", "Hello")
        demo.event("click", "b64_encode")
        assertEquals("SGVsbG8=", demo.render().outputText())
    }

    @Test
    fun `text tools count what is there`() {
        val demo = load("text-tools")
        demo.event("input_changed", "text", "one two three")
        demo.event("click", "stats")
        val stats = demo.render().outputText()
        assertTrue(stats, stats.contains("13 characters"))
        assertTrue(stats, stats.contains("3 words"))
    }

    // ---- kitchen sink ------------------------------------------------------

    @Test
    fun `the kitchen sink shows every widget kind`() {
        val demo = load("ui-kitchen-sink")
        val ui = demo.render()
        val tabs = ui.root.single() as PluginWidget.Tabs
        val flat = ArrayList<PluginWidget>()
        fun walk(widgets: List<PluginWidget>) {
            for (widget in widgets) {
                flat += widget
                when (widget) {
                    is PluginWidget.Column -> walk(widget.children)
                    is PluginWidget.Row -> walk(widget.children)
                    is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                    is PluginWidget.Label,
                    is PluginWidget.Output,
                    is PluginWidget.Button,
                    is PluginWidget.Toggle,
                    is PluginWidget.Input,
                    is PluginWidget.Spacer,
                    PluginWidget.Divider,
                    PluginWidget.Progress,
                    -> Unit
                }
            }
        }
        walk(ui.root)

        // Every widget the prelude offers should appear somewhere, or the
        // "kitchen sink" name is a lie the docs repeat.
        assertTrue(flat.any { it is PluginWidget.Label })
        assertTrue(flat.any { it is PluginWidget.Output })
        assertTrue(flat.any { it is PluginWidget.Button })
        assertTrue(flat.any { it is PluginWidget.Toggle })
        assertTrue(flat.any { it is PluginWidget.Input })
        assertTrue(flat.any { it is PluginWidget.Spacer })
        assertTrue(flat.any { it == PluginWidget.Divider })
        assertTrue(flat.any { it is PluginWidget.Row })
        assertTrue(tabs.pages.size >= 5)
    }

    @Test
    fun `the kitchen sink logs the events it is given`() {
        val demo = load("ui-kitchen-sink")
        demo.event("click", "primary")
        demo.event("toggle", "demo_toggle", true)
        demo.event("input_changed", "box", "typed text")
        val text = demo.render().allText()
        assertTrue(text, text.contains("click primary"))
        assertTrue(text, text.contains("demo_toggle = true"))
        assertTrue(text, text.contains("box = typed text"))
    }

    /** The first non-empty output block, which is where each demo puts its result. */
    private fun RenderedUi.outputText(): String {
        var found = ""
        fun walk(widgets: List<PluginWidget>) {
            for (widget in widgets) {
                when (widget) {
                    is PluginWidget.Output -> if (found.isEmpty()) found = widget.text
                    is PluginWidget.Column -> walk(widget.children)
                    is PluginWidget.Row -> walk(widget.children)
                    is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                    is PluginWidget.Label,
                    is PluginWidget.Button,
                    is PluginWidget.Toggle,
                    is PluginWidget.Input,
                    is PluginWidget.Spacer,
                    PluginWidget.Divider,
                    PluginWidget.Progress,
                    -> Unit
                }
            }
        }
        walk(root)
        return found
    }

    private companion object {
        var counter = 0
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

/**
 * The published demo plugins, run in the real interpreter.
 *
 * These are what the documentation teaches from and what most people will
 * copy, so "it loads, it renders, its buttons do the thing" is worth asserting
 * rather than assuming. Every one of them has to work with no special
 * treatment: a demo that needed something plugins do not get would be a
 * documentation bug shipped to every reader.
 *
 * The scripts are mirrored from `plugins-src/` in the addon repository, whose
 * `test.py` files test the same plugins on `wm-python-cli`. What only this
 * side proves is the trip through the app: the session's JSON, the widget
 * codec, events as the keyboard sends them, and the storage and reminders
 * files the app reads afterwards.
 */
class DemoPluginsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val open = ArrayList<DemoSession>()

    @After
    fun tearDown() {
        open.forEach { it.close() }
    }

    private fun load(
        name: String,
        permissions: List<String> = emptyList(),
        storage: File? = File(temp.newFolder(), PluginStore.STORAGE_FILE),
        reminders: File? = null,
    ): DemoSession = DemoSession.demo(name, permissions, storage, reminders).also { open += it }

    private fun remindersFile(): File = File(temp.newFolder(), PluginStore.REMINDERS_FILE)

    // ---- every demo at least works ----------------------------------------

    @Test
    fun `every demo loads and renders without repairs`() {
        for (name in listOf("cipher-tool", "ui-kitchen-sink", "todo-list", "text-tools", "math-mode", "text-actions")) {
            val permissions = when (name) {
                "todo-list" -> TODO_PERMISSIONS
                "math-mode" -> listOf("storage")
                "text-actions" -> listOf("storage", "selection")
                else -> emptyList()
            }
            val ui = load(name, permissions).ui
            assertTrue("$name rendered nothing", ui.root.isNotEmpty())
            assertEquals("$name needed repairs: ${ui.repairs}", emptyList<PluginText>(), ui.repairs)
        }
    }

    @Test
    fun `the API 5 text generator writes as many words as asked`() {
        val demo = DemoSession.demo("text-generator", apiVersion = PluginManifestCodec.PACKAGE_API).also { open += it }
        demo.event(PythonSession.hostEvent("change", mapOf("id" to JsonPrimitive("select"), "value" to JsonPrimitive("Words"))))
        val ui = demo.event(PythonSession.hostEvent("change", mapOf("id" to JsonPrimitive("stepper:How many"), "value" to JsonPrimitive(5))))
        val output = ui.root.flatMap { listOf(it) + it.childWidgets() }.filterIsInstance<PluginWidget.Output>().single()
        assertEquals(5, output.text.split(" ").size)
    }

    // ---- selection actions --------------------------------------------------

    @Test
    fun `selection actions rot13 and quote with the style the panel chose`() {
        val storage = File(temp.newFolder(), PluginStore.STORAGE_FILE)
        val plugin = DemoSession.plugin("text-actions", listOf("storage", "selection"))
        val source = DemoSession.resource("plugins/text-actions.py")
        fun transform(text: String, action: String): String? {
            val result = DemoSession.transform(source, plugin, storage, text, action)
            assertTrue("$action failed: ${result.error}", result.ok)
            return result.value
        }

        assertEquals("Uryyb, Jbeyq!", transform("Hello, World!", "rot13"))
        assertEquals("Hello, World!", transform(transform("Hello, World!", "rot13")!!, "rot13"))
        assertEquals("“hi”", transform("hi", "quote"))

        // The panel and the action are two sessions; the choice crosses in storage.
        load("text-actions", plugin.permissions, storage).click("guillemets")
        assertEquals("«hi»", transform("hi", "quote"))
        assertNull(transform("hi", "no-such-action"))
    }

    // ---- cipher ------------------------------------------------------------

    @Test
    fun `caesar encodes and decodes`() {
        val demo = load("cipher-tool")
        demo.input("message", "Hello, World!")
        demo.input("shift", "3")
        assertTrue(demo.click("caesar_encode").allText().contains("Khoor, Zruog!"))

        demo.input("message", "Khoor, Zruog!")
        assertTrue(demo.click("caesar_decode").allText().contains("Hello, World!"))
    }

    @Test
    fun `vigenere matches the textbook vector`() {
        // ATTACKATDAWN with key LEMON is LXFOPVEFRNHR in every reference.
        val demo = load("cipher-tool")
        demo.input("message", "ATTACKATDAWN")
        demo.input("keyword", "LEMON")
        assertTrue(demo.click("vigenere_encode").allText().contains("LXFOPVEFRNHR"))

        demo.input("message", "LXFOPVEFRNHR")
        assertTrue(demo.click("vigenere_decode").allText().contains("ATTACKATDAWN"))
    }

    @Test
    fun `switching tabs clears the other cipher's result`() {
        val demo = load("cipher-tool")
        demo.input("message", "abc")
        assertEquals(listOf("def", "def"), demo.click("caesar_encode").outputs())
        assertTrue(demo.tab("cipher", 1).outputs().isEmpty())
    }

    // ---- todo --------------------------------------------------------------

    @Test
    fun `todo adds ticks and clears`() {
        val demo = load("todo-list", TODO_PERMISSIONS, reminders = remindersFile())
        demo.input("draft", "buy milk")
        assertTrue(demo.click("add").allText().contains("buy milk"))
        // Adding clears the box through the host, not by writing to it directly.
        assertEquals(listOf("draft" to ""), demo.writes)

        assertTrue(demo.toggle("done:1", true).allText().contains("0 of 1 left"))
        assertTrue(demo.click("clear_done").allText().contains("Nothing on the list"))
    }

    @Test
    fun `todo remembers its list through storage`() {
        val storage = File(temp.newFolder("shared"), PluginStore.STORAGE_FILE)
        val first = load("todo-list", listOf("storage"), storage)
        first.input("draft", "water the plants")
        first.click("add")

        // A second session, as if the keyboard had been closed and reopened.
        assertTrue(load("todo-list", listOf("storage"), storage).ui.allText().contains("water the plants"))
    }

    @Test
    fun `todo reads the list its Lua version saved`() {
        // The shape the retired Lua plugin wrote, which a parked storage.json
        // brings back when the Python version is installed with the same id.
        val storage = File(temp.newFolder(), PluginStore.STORAGE_FILE)
        PluginStorage(storage).set(
            "items",
            """[{"id":1,"text":"Buy milk","done":false,"remind":false},""" +
                """{"text":"From version one","done":true,"remind":false}]""",
        )
        val text = load("todo-list", listOf("storage"), storage).ui.allText()
        assertTrue(text, text.contains("Buy milk"))
        assertTrue(text, text.contains("From version one"))
        assertTrue(text, text.contains("1 of 2 left"))
    }

    @Test
    fun `todo sets a reminder for a due item and cancels it when done`() {
        val file = remindersFile()
        val reminders = PluginReminders(file)
        val demo = load("todo-list", TODO_PERMISSIONS, reminders = file)
        val due = System.currentTimeMillis() / 1000 + 3600
        demo.input("draft", "file taxes")
        demo.date("draft_due", due)
        val ui = demo.click("add")

        // Written by the interpreter, read back the way the app's alarm reads it.
        val reminder = reminders.list().single()
        assertEquals("file taxes", reminder.text)
        assertEquals(due * 1000, reminder.at)
        assertTrue(ui.allText().contains("reminder"))

        demo.toggle("done:1", true)
        assertTrue(reminders.list().isEmpty())
    }

    @Test
    fun `todo moves a due date from the details page and flags what is overdue`() {
        val file = remindersFile()
        val reminders = PluginReminders(file)
        val demo = load("todo-list", TODO_PERMISSIONS, reminders = file)
        demo.input("draft", "late thing")
        demo.date("draft_due", System.currentTimeMillis() / 1000 - 7200)
        // A time already past gets no reminder, and the list says it is late.
        assertTrue(demo.click("add").allText().contains("1 overdue"))
        assertTrue(reminders.list().isEmpty())

        val later = System.currentTimeMillis() / 1000 + 86_400
        assertFalse(demo.date("due", later).allText().contains("overdue"))
        assertEquals(later * 1000, reminders.list().single().at)

        assertTrue(demo.click("delete").allText().contains("Nothing on the list"))
        assertTrue(reminders.list().isEmpty())
    }

    @Test
    fun `a cleared date reaches the script as None`() {
        val demo = load("todo-list", TODO_PERMISSIONS, reminders = remindersFile())
        demo.input("draft", "someday")
        demo.date("draft_due", System.currentTimeMillis() / 1000 + 3600)
        demo.click("add")
        val due = demo.ui.widgets().filterIsInstance<PluginWidget.DatePicker>().single { it.id == "due" }
        assertTrue(due.value != null)

        val cleared = demo.date("due", null).widgets().filterIsInstance<PluginWidget.DatePicker>().single { it.id == "due" }
        assertNull(cleared.value)
    }

    @Test
    fun `todo works without the reminders permission`() {
        val demo = load("todo-list", listOf("storage"))
        demo.input("draft", "buy milk")
        demo.date("draft_due", System.currentTimeMillis() / 1000 + 3600)
        val text = demo.click("add").allText()
        assertTrue(text.contains("buy milk"))
        assertFalse(text.contains("Remind me"))
    }

    // ---- text tools --------------------------------------------------------

    @Test
    fun `text tools change case`() {
        val demo = load("text-tools")
        demo.input("text", "hello there")
        assertTrue(demo.click("upper").allText().contains("HELLO THERE"))
        assertTrue(demo.click("title").allText().contains("Hello There"))
    }

    @Test
    fun `text tools round trip every encoding`() {
        val demo = load("text-tools")
        val original = "Hello, World! 123 কি 😀"
        for ((encode, decode) in listOf(
            "b64_encode" to "b64_decode",
            "hex_encode" to "hex_decode",
            "url_encode" to "url_decode",
        )) {
            demo.input("text", original)
            val encoded = demo.click(encode).outputs().single()
            assertTrue("$encode produced nothing", encoded.isNotEmpty())

            demo.input("text", encoded)
            assertEquals("$encode/$decode did not round trip", original, demo.click(decode).outputs().single())
        }
    }

    @Test
    fun `base64 matches a known value`() {
        val demo = load("text-tools")
        demo.input("text", "Hello")
        assertEquals("SGVsbG8=", demo.click("b64_encode").outputs().single())
    }

    @Test
    fun `text tools count what is there`() {
        val demo = load("text-tools")
        demo.input("text", "one two three")
        val stats = demo.click("stats").outputs().single()
        assertTrue(stats, stats.contains("13 characters"))
        assertTrue(stats, stats.contains("3 words"))
    }

    @Test
    fun `clearing text tools empties the box through the host`() {
        val demo = load("text-tools")
        demo.input("text", "abc")
        demo.click("upper")
        assertTrue(demo.click("clear").outputs().isEmpty())
        assertEquals(listOf("text" to ""), demo.writes)
    }

    // ---- kitchen sink ------------------------------------------------------

    @Test
    fun `the kitchen sink shows every widget kind`() {
        val demo = load("ui-kitchen-sink")
        val tabs = demo.ui.root.single() as PluginWidget.Tabs
        val flat = demo.ui.widgets()

        // Every widget the ui module offers should appear somewhere, or the
        // "kitchen sink" name is a lie the docs repeat.
        assertTrue(flat.any { it is PluginWidget.Label && it.style == PluginLabelStyle.TITLE })
        assertTrue(flat.any { it is PluginWidget.Label && it.style == PluginLabelStyle.CAPTION })
        assertTrue(flat.any { it is PluginWidget.Output && it.mono })
        assertTrue(flat.any { it is PluginWidget.Button && it.primary })
        assertTrue(flat.any { it is PluginWidget.Toggle })
        assertTrue(flat.any { it is PluginWidget.Input })
        assertTrue(flat.any { it is PluginWidget.Spacer && it.height == 12 })
        assertTrue(flat.any { it is PluginWidget.DatePicker && it.time })
        assertTrue(flat.any { it == PluginWidget.Divider })
        assertTrue(flat.any { it is PluginWidget.Row })
        assertTrue(tabs.pages.size >= 5)

        assertTrue(demo.click("work").widgets().any { it == PluginWidget.Progress })
        assertTrue(demo.click("toggle_enabled").widgets().any { it is PluginWidget.Button && it.id == "maybe" && !it.enabled })
    }

    @Test
    fun `the kitchen sink logs the events it is given`() {
        val demo = load("ui-kitchen-sink")
        demo.click("primary")
        demo.toggle("demo_toggle", true)
        demo.input("box", "typed text")
        demo.tab("sink", 2)
        val text = demo.date("when", 1_790_000_000L).allText()
        assertTrue(text, text.contains("date when = "))
        assertTrue(text, text.contains("Picked: "))
        assertTrue(text, text.contains("click primary"))
        assertTrue(text, text.contains("toggle demo_toggle = True"))
        assertTrue(text, text.contains("input box = typed text"))
        assertTrue(text, text.contains("tab sink #2"))
    }

    private companion object {
        /** What the todo demo's manifest declares. */
        val TODO_PERMISSIONS = listOf("storage", "reminders")
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The runtime, driven with real threads and the real interpreter.
 *
 * Not fast tests, but the things worth proving here (that a runaway script is
 * stopped and reported, that a second offence disables the plugin, that a
 * closed session goes quiet) are all about real concurrency, and a fake
 * executor would prove none of them. What the interpreter itself does with a
 * script is `cargo test`'s business in `native/wm-python`; these check what
 * the runtime makes of its answers.
 */
class PluginRuntimeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: PluginStore
    private var runtime: PluginRuntime? = null

    private val lock = Any()
    private val events = ArrayList<String>()
    private val uis = ArrayList<RenderedUi>()
    private val remindersMoved = AtomicInteger()

    /** The message behind every `error` and `stopped:` event, in arrival order. */
    private val messages = ArrayList<PluginText>()

    private val listener = object : PluginRuntime.Listener {
        override fun onUi(pluginId: String, ui: RenderedUi) = synchronized(lock) {
            uis += ui
            events += "ui"
        }

        override fun onBusy(pluginId: String, busy: Boolean) = Unit

        override fun onError(pluginId: String, message: PluginText) {
            synchronized(lock) {
                messages += message
                events += "error"
            }
        }

        override fun onInputWrite(pluginId: String, inputId: String, text: String) {
            synchronized(lock) { events += "input:$inputId=$text" }
        }

        override fun onStopped(pluginId: String, message: PluginText, disabled: Boolean) {
            synchronized(lock) {
                messages += message
                events += "stopped:disabled=$disabled"
            }
        }
    }

    @After
    fun tearDown() {
        runtime?.shutdown()
    }

    // ---- helpers ---------------------------------------------------------

    private fun runtime(limits: PluginRuntime.Limits = PluginRuntime.Limits()): PluginRuntime {
        store = PluginStore(temp.newFolder())
        return PluginRuntime(store, listener, limits, onRemindersChanged = { remindersMoved.incrementAndGet() })
            .also { runtime = it }
    }

    private fun install(
        script: String,
        id: String = ID,
        permissions: List<String> = emptyList(),
    ): String {
        store.adopt(
            PluginManifest(
                format = PluginManifestCodec.FORMAT,
                id = id,
                name = "Demo",
                pluginVersion = "1.0.0",
                permissions = permissions,
            ),
            script,
        )
        return id
    }

    /** Polls rather than latching: callbacks arrive on threads we do not control. */
    private fun waitUntil(timeoutMillis: Long = 10_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) { if (predicate()) return }
            Thread.sleep(20)
        }
        synchronized(lock) { throw AssertionError("timed out; events so far: $events") }
    }

    private fun awaitUis(count: Int) = waitUntil { uis.size >= count }

    private fun awaitTag(prefix: String) = waitUntil { events.any { it.startsWith(prefix) } }

    private fun reset() = synchronized(lock) {
        events.clear()
        uis.clear()
        messages.clear()
    }

    private fun lastLabel(): String =
        synchronized(lock) { (uis.last().root.single() as PluginWidget.Label).text }

    /**
     * The message behind the last error or stop.
     *
     * These arrive as [PluginText] rather than as words, and this is a host JVM
     * test with no Context, so what gets checked is *which* line the runtime
     * chose. That is the stronger check anyway: it outlives a rewording.
     */
    private fun lastMessage(): PluginText = synchronized(lock) { messages.last() }

    // ---- the happy path ---------------------------------------------------

    @Test
    fun `opening a plugin renders it`() {
        val engine = runtime()
        install("def render():\n    return ui.column(ui.label(\"hello\"))")
        engine.open(ID)
        awaitUis(1)

        val column = synchronized(lock) { uis.last().root.single() as PluginWidget.Column }
        assertEquals("hello", (column.children.single() as PluginWidget.Label).text)
        assertEquals(ID, engine.activePluginId)
    }

    @Test
    fun `an event reaches the script and the result is re-rendered`() {
        val engine = runtime()
        install(
            """
            count = 0

            def on_event(e):
                global count
                if e.type == "click":
                    count += 1

            def render():
                return ui.label("clicks: " + str(count))
            """.trimIndent(),
        )
        engine.open(ID)
        awaitUis(1)

        engine.dispatch(PluginEvent.Click("go"))
        awaitUis(2)
        engine.dispatch(PluginEvent.Click("go"))
        awaitUis(3)

        assertEquals("clicks: 2", lastLabel())
    }

    @Test
    fun `an input event carries its text`() {
        val engine = runtime()
        install(
            """
            typed = ""

            def on_event(e):
                global typed
                if e.type == "input_changed":
                    typed = e.id + ": " + e.value

            def render():
                return ui.label(typed)
            """.trimIndent(),
        )
        engine.open(ID)
        awaitUis(1)

        engine.dispatch(PluginEvent.InputChanged("msg", "typed by the user"))
        awaitUis(2)
        assertEquals("msg: typed by the user", lastLabel())
    }

    @Test
    fun `a toggle event carries its value`() {
        val engine = runtime()
        install(
            """
            on = False

            def on_event(e):
                global on
                if e["type"] == "toggle":
                    on = e["value"]

            def render():
                return ui.label(str(on))
            """.trimIndent(),
        )
        engine.open(ID)
        awaitUis(1)

        engine.dispatch(PluginEvent.ToggleChanged("caps", true))
        awaitUis(2)
        assertEquals("True", lastLabel())
    }

    @Test
    fun `a tab carries its index and a cleared date carries None`() {
        val engine = runtime()
        install(
            """
            seen = "nothing"

            def on_event(e):
                global seen
                if e.type == "tab_selected":
                    seen = "tab " + str(e.index)
                if e.type == "date_changed":
                    seen = "date " + str(e.value)

            def render():
                return ui.label(seen)
            """.trimIndent(),
        )
        engine.open(ID)
        awaitUis(1)

        engine.dispatch(PluginEvent.TabSelected("pages", 2))
        awaitUis(2)
        assertEquals("tab 2", lastLabel())
        engine.dispatch(PluginEvent.DateChanged("due", 1_790_000_000L))
        awaitUis(3)
        assertEquals("date 1790000000", lastLabel())
        engine.dispatch(PluginEvent.DateChanged("due", null))
        awaitUis(4)
        assertEquals("date None", lastLabel())
    }

    @Test
    fun `set_input reaches the host`() {
        val engine = runtime()
        install(
            """
            def on_event(e):
                wm.ui.set_input("box", "cleared")

            def render():
                return ui.input("box")
            """.trimIndent(),
        )
        engine.open(ID)
        awaitUis(1)

        engine.dispatch(PluginEvent.Click("go"))
        awaitTag("input:box=cleared")
    }

    @Test
    fun `storage survives a session ending`() {
        val engine = runtime()
        install(
            """
            def on_event(e):
                wm.storage.set("note", "kept")

            def render():
                return ui.label(wm.storage.get("note", "empty"))
            """.trimIndent(),
            permissions = listOf("storage"),
        )
        engine.open(ID)
        awaitUis(1)
        assertEquals("empty", lastLabel())
        engine.dispatch(PluginEvent.Click("save"))
        awaitUis(2)
        assertEquals("kept", lastLabel())
        engine.close()

        reset()
        engine.open(ID)
        awaitUis(1)
        assertEquals("kept", lastLabel())
        // Written where the app reads it, in the format it reads.
        assertEquals("kept", PluginStorage(store.storageFile(ID)).get("note"))
    }

    @Test
    fun `a reminder set by an installed plugin moves the app's wake-up`() {
        val engine = runtime()
        install(
            """
            import time

            def on_event(e):
                wm.reminders.set("r1", int(time.time()) + 3600, "stretch")

            def render():
                return ui.label(str(len(wm.reminders.list())))
            """.trimIndent(),
            permissions = listOf("reminders"),
        )
        engine.open(ID)
        awaitUis(1)
        assertEquals(0, remindersMoved.get())

        engine.dispatch(PluginEvent.Click("set"))
        awaitUis(2)
        assertEquals("1", lastLabel())
        waitUntil { remindersMoved.get() == 1 }
        assertEquals("stretch", PluginReminders(store.remindersFile(ID)).list().single().text)
    }

    // ---- broken plugins ---------------------------------------------------

    @Test
    fun `a script that fails to load reports an error`() {
        val engine = runtime()
        install("this is not python at all !!!")
        engine.open(ID)
        awaitTag("error")
        // The interpreter wrote the complaint, so it reaches the panel as the
        // script's own words rather than as one of this build's strings.
        assertTrue(lastMessage() is PluginText.Script)
        assertTrue(store.plugin(ID)!!.enabled)
    }

    @Test
    fun `a script with no render function says so`() {
        val engine = runtime()
        install("x = 1")
        engine.open(ID)
        awaitTag("error")
        // "says so" is the point of the test, so check which line it said.
        assertEquals(PluginText.Resource(R.string.core_plugins_error_no_render), lastMessage())
    }

    @Test
    fun `an error inside an event handler does not end the session`() {
        val engine = runtime()
        install(
            """
            def on_event(e):
                raise ValueError("handler exploded")

            def render():
                return ui.label("still here")
            """.trimIndent(),
        )
        engine.open(ID)
        awaitUis(1)

        engine.dispatch(PluginEvent.Click("boom"))
        awaitTag("error")
        // The interpreter's words, with the line the panel shows them at.
        val failure = lastMessage() as PluginText.Script
        assertTrue(failure.text, failure.text.contains("handler exploded"))
        assertTrue(failure.text, failure.text.startsWith("${PluginRuntime.SCRIPT_CHUNK}:2: "))

        // Still loaded, so the next event still arrives.
        reset()
        engine.dispatch(PluginEvent.Click("again"))
        awaitTag("error")
        assertTrue(store.plugin(ID)!!.enabled)
        assertEquals(0, store.plugin(ID)!!.abandonedCount)
    }

    @Test
    fun `an infinite loop is stopped, reported and counted`() {
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = 200_000, wallMillis = 5_000)))
        install("while True:\n    pass\n\ndef render():\n    return ui.label(\"x\")")
        engine.open(ID)
        awaitTag("stopped")
        // Reported as the reason it was stopped for: the step allowance ran
        // out, which is a different line from a deadline or a cancellation.
        assertEquals(PluginText.Resource(PluginAbortReason.INSTRUCTIONS.messageRes), lastMessage())
        assertEquals(1, store.plugin(ID)!!.abandonedCount)
    }

    @Test
    fun `a plugin past its deadline is stopped and a repeat offence disables it`() {
        // Steps enough for minutes; the clock is what stops it.
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = HUGE_STEPS, wallMillis = 200)))
        install("while True:\n    pass\n\ndef render():\n    return ui.label(\"never reached\")")

        engine.open(ID)
        awaitTag("stopped")
        assertEquals(PluginText.Resource(PluginAbortReason.DEADLINE.messageRes), lastMessage())
        assertEquals(1, store.plugin(ID)!!.abandonedCount)
        assertTrue(store.plugin(ID)!!.enabled)

        reset()
        engine.open(ID)
        awaitTag("stopped:disabled=true")
        assertFalse(store.plugin(ID)!!.enabled)

        // Once disabled it will not open at all.
        reset()
        engine.open(ID)
        awaitTag("error")
        assertEquals(PluginText.Resource(R.string.core_plugins_error_turned_off), lastMessage())
    }

    @Test
    fun `with auto-disable off the strikes are counted and the plugin stays on`() {
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = 200_000, wallMillis = 5_000)))
        store.setAutoDisableOnAbandon(false)
        install("while True:\n    pass")
        repeat(2) { round ->
            reset()
            engine.open(ID)
            awaitTag("stopped")
            assertEquals(round + 1, store.plugin(ID)!!.abandonedCount)
        }
        assertTrue(synchronized(lock) { "stopped:disabled=false" in events })
        assertTrue(store.plugin(ID)!!.enabled)
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test
    fun `closing stops callbacks arriving`() {
        val engine = runtime()
        install("def on_event(e):\n    pass\n\ndef render():\n    return ui.label(\"hi\")")
        engine.open(ID)
        awaitUis(1)

        engine.close()
        reset()
        engine.dispatch(PluginEvent.Click("go"))
        Thread.sleep(300)
        synchronized(lock) {
            assertTrue("a closed session still spoke: $events", events.isEmpty())
        }
        assertNull(engine.activePluginId)
    }

    @Test
    fun `closing a plugin mid-loop is silent and earns no strike`() {
        // Allowances it cannot reach before the close, so only the close can stop it.
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = HUGE_STEPS, wallMillis = 5_000)))
        install("while True:\n    pass")
        engine.open(ID)
        Thread.sleep(150)

        engine.close()
        Thread.sleep(500)
        // The user closed the panel on purpose: nothing to tell them, nothing to hold against the plugin.
        synchronized(lock) { assertTrue("$events", events.none { it.startsWith("stopped") || it == "error" }) }
        assertEquals(0, store.plugin(ID)!!.abandonedCount)
    }

    @Test
    fun `opening a second plugin replaces the first`() {
        val engine = runtime()
        install("def render():\n    return ui.label(\"one\")")
        engine.open(ID)
        awaitUis(1)

        install("def render():\n    return ui.label(\"two\")", id = "com.example.other")

        reset()
        engine.open("com.example.other")
        awaitUis(1)
        assertEquals("com.example.other", engine.activePluginId)
        assertEquals("two", lastLabel())
    }

    @Test
    fun `a missing plugin is an error rather than a crash`() {
        val engine = runtime()
        engine.open("com.example.absent")
        awaitTag("error")
        assertEquals(PluginText.Resource(R.string.core_plugins_error_not_installed), lastMessage())
    }

    @Test
    fun `a disabled plugin refuses to open`() {
        val engine = runtime()
        install("def render():\n    return ui.label(\"x\")")
        store.setEnabled(ID, false)
        engine.open(ID)
        awaitTag("error")
        // Turned off rather than missing: two different lines for two different
        // fixes, and only the resource id can tell them apart here.
        assertEquals(PluginText.Resource(R.string.core_plugins_error_turned_off), lastMessage())
    }

    @Test
    fun `a plugin whose script has gone says so`() {
        val engine = runtime()
        install("def render():\n    return ui.label(\"x\")")
        store.scriptFile(ID)!!.delete()
        engine.open(ID)
        awaitTag("error")
        assertEquals(PluginText.Resource(R.string.core_plugins_error_script_missing), lastMessage())
    }

    private companion object {
        const val ID = "com.example.demo"

        /** A step allowance no test reaches, so a deadline is the only way out. */
        const val HUGE_STEPS = 1_000_000_000_000L
    }
}

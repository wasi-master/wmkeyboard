package com.wasimaster.wmkeyboard.core.plugins

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The runtime as the plugin editor drives it: a [PluginRuntime.Draft] nobody
 * installed, watched through a [PluginRuntime.Observer]. Real threads and the
 * real interpreter, for the same reasons [PluginRuntimeTest] uses them.
 */
class PluginRuntimeDraftTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: PluginStore
    private var runtime: PluginRuntime? = null
    private val remindersMoved = AtomicInteger()

    private val lock = Any()
    private val events = ArrayList<String>()
    private val uis = ArrayList<RenderedUi>()
    private val prints = ArrayList<String>()
    private val phases = ArrayList<Pair<PluginRuntime.Phase, PluginBudget.Usage>>()
    private val failures = ArrayList<Pair<String, PluginRuntime.Failure>>()
    private val observedEvents = ArrayList<PluginEvent>()
    private val messages = ArrayList<PluginText>()

    private val listener = object : PluginRuntime.Listener {
        override fun onUi(pluginId: String, ui: RenderedUi) = synchronized(lock) {
            uis += ui
            events += "ui"
        }

        override fun onBusy(pluginId: String, busy: Boolean) = Unit

        override fun onError(pluginId: String, message: PluginText) = synchronized(lock) {
            messages += message
            events += "error"
        }

        override fun onInputWrite(pluginId: String, inputId: String, text: String) = Unit

        override fun onStopped(pluginId: String, message: PluginText, disabled: Boolean) = synchronized(lock) {
            messages += message
            events += "stopped:disabled=$disabled"
        }
    }

    private val observer = object : PluginRuntime.Observer {
        override fun onPrint(pluginId: String, line: String) {
            synchronized(lock) { prints += line }
        }

        override fun onPhase(pluginId: String, phase: PluginRuntime.Phase, usage: PluginBudget.Usage) {
            synchronized(lock) { phases += phase to usage }
        }

        override fun onFailure(pluginId: String, failure: PluginRuntime.Failure) {
            synchronized(lock) {
                failures += pluginId to failure
                events += "failure:${failure.kind}"
            }
        }

        override fun onEvent(pluginId: String, event: PluginEvent) {
            synchronized(lock) { observedEvents += event }
        }
    }

    @After
    fun tearDown() {
        runtime?.shutdown()
    }

    private fun runtime(limits: PluginRuntime.Limits = PluginRuntime.Limits()): PluginRuntime {
        store = PluginStore(temp.newFolder())
        return PluginRuntime(
            store,
            listener,
            limits,
            observer = observer,
            onRemindersChanged = { remindersMoved.incrementAndGet() },
        ).also { runtime = it }
    }

    private fun plugin(id: String = ID, permissions: List<String> = emptyList()) =
        InstalledPlugin(id = id, name = "Draft", version = "0.1", permissions = permissions)

    private fun draft(
        script: String,
        id: String = ID,
        storage: File? = null,
        permissions: List<String> = emptyList(),
        reminders: File? = null,
    ) = PluginRuntime.Draft(plugin(id, permissions), script, PluginLog(null), storage, reminders = reminders)

    private fun install(script: String) {
        store.adopt(
            PluginManifest(format = PluginManifestCodec.FORMAT, id = ID, name = "Installed", pluginVersion = "1.0.0"),
            script,
        )
    }

    private fun waitUntil(timeoutMillis: Long = 10_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) { if (predicate()) return }
            Thread.sleep(20)
        }
        synchronized(lock) { throw AssertionError("timed out; events so far: $events") }
    }

    private fun lastFailure(): PluginRuntime.Failure = synchronized(lock) { failures.last().second }

    @Test
    fun `a draft renders without going near the store`() {
        val engine = runtime()
        engine.open(draft("def render():\n    return ui.label(\"drafted\")"))
        waitUntil { uis.isNotEmpty() }
        assertEquals("drafted", synchronized(lock) { (uis.last().root.single() as PluginWidget.Label).text })
        assertTrue(store.plugins().isEmpty())
        assertEquals(ID, engine.activePluginId)
    }

    @Test
    fun `a runaway draft earns no strike against the installed plugin with its id`() {
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = 200_000, wallMillis = 5_000)))
        install("def render():\n    return ui.label(\"installed\")")

        engine.open(draft("while True:\n    pass"))
        waitUntil { events.any { it.startsWith("stopped") } }

        assertTrue(synchronized(lock) { "stopped:disabled=false" in events })
        assertEquals(PluginRuntime.Failure.Kind.INSTRUCTIONS, lastFailure().kind)
        assertEquals(0, store.plugin(ID)!!.abandonedCount)
        assertTrue(store.plugin(ID)!!.enabled)
    }

    @Test
    fun `a draft past its deadline earns no strike either`() {
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = HUGE_STEPS, wallMillis = 200)))
        install("def render():\n    return ui.label(\"installed\")")

        engine.open(draft("while True:\n    pass"))
        waitUntil { events.any { it.startsWith("stopped") } }
        engine.open(draft("while True:\n    pass"))
        waitUntil { events.count { it.startsWith("stopped") } >= 2 }

        assertEquals(2, synchronized(lock) { failures.count { it.second.kind == PluginRuntime.Failure.Kind.DEADLINE } })
        assertEquals(0, store.plugin(ID)!!.abandonedCount)
        assertTrue(store.plugin(ID)!!.enabled)
        assertFalse(synchronized(lock) { "stopped:disabled=true" in events })
    }

    @Test
    fun `a draft writes to the storage it was given and no other`() {
        val engine = runtime()
        install("def render():\n    return ui.label(\"installed\")")
        val file = File(temp.newFolder(), "draft-storage.json")

        engine.open(
            draft(
                "wm.storage.set(\"k\", \"from the draft\")\n\ndef render():\n    return ui.label(\"x\")",
                storage = file,
                permissions = listOf("storage"),
            ),
        )
        waitUntil { uis.isNotEmpty() }

        assertEquals("from the draft", PluginStorage(file).get("k"))
        assertFalse(store.storageFile(ID)!!.exists())
    }

    @Test
    fun `a draft's reminders stay in its own file and never move the app's wake-up`() {
        val engine = runtime()
        val file = File(temp.newFolder(), PluginStore.REMINDERS_FILE)
        engine.open(
            draft(
                "import time\nwm.reminders.set(\"r\", int(time.time()) + 3600, \"draft\")\n\n" +
                    "def render():\n    return ui.label(\"x\")",
                permissions = listOf("reminders"),
                reminders = file,
            ),
        )
        waitUntil { uis.isNotEmpty() }

        assertEquals("draft", PluginReminders(file).list().single().text)
        assertEquals(0, remindersMoved.get())
    }

    @Test
    fun `print and wm log both reach the observer in order`() {
        val engine = runtime()
        engine.open(
            draft("print(\"a\", 1)\nwm.log(\"b\")\n\ndef render():\n    print(\"c\")\n    return ui.label(\"x\")"),
        )
        waitUntil { uis.isNotEmpty() }
        assertEquals(listOf("a 1", "b", "c"), synchronized(lock) { prints.toList() })
    }

    @Test
    fun `a syntax error is observed as a compile failure at its line`() {
        val engine = runtime()
        engine.open(draft("a = 1\nb = = 2\n\ndef render():\n    return ui.label(\"x\")"))
        waitUntil { events.any { it.startsWith("failure") } }

        val failure = lastFailure()
        assertEquals(PluginRuntime.Failure.Kind.COMPILE, failure.kind)
        assertEquals(PluginRuntime.Phase.LOAD, failure.phase)
        assertEquals("main.py", failure.chunk)
        assertEquals(2, failure.line)
        // The keyboard still hears it the way it always did.
        waitUntil { events.contains("error") }
        assertTrue(synchronized(lock) { messages.last() } is PluginText.Script)
    }

    @Test
    fun `a runtime error in a handler is observed at its line in the event phase`() {
        val engine = runtime()
        engine.open(
            draft("def on_event(e):\n    t = None\n    return t.x\n\ndef render():\n    return ui.label(\"x\")"),
        )
        waitUntil { uis.isNotEmpty() }
        engine.dispatch(PluginEvent.Click("go"))
        waitUntil { events.any { it.startsWith("failure") } }

        val failure = lastFailure()
        assertEquals(PluginRuntime.Failure.Kind.RUNTIME, failure.kind)
        assertEquals(PluginRuntime.Phase.EVENT, failure.phase)
        assertEquals("main.py", failure.chunk)
        assertEquals(3, failure.line)
        waitUntil { events.contains("error") }
        // The panel gets the same words, placed in main.py and cut at the runtime's 300 characters.
        val shown = synchronized(lock) { messages.last() } as PluginText.Script
        assertEquals(PluginRuntime.locatedText(failure.text.take(300), 3), shown.text)
    }

    @Test
    fun `a long error reaches the observer whole and the panel cut short`() {
        val engine = runtime()
        engine.open(draft("raise ValueError(\"x\" * 1000)"))
        waitUntil { events.contains("error") && failures.isNotEmpty() }

        assertTrue(lastFailure().text.count { it == 'x' } >= 1000)
        val shown = synchronized(lock) { messages.last() } as PluginText.Script
        assertTrue(shown.text, shown.text.count { it == 'x' } < 1000)
    }

    @Test
    fun `each phase reports what it spent`() {
        val engine = runtime()
        engine.open(draft("def on_event(e):\n    pass\n\ndef render():\n    return ui.label(\"x\")"))
        waitUntil { phases.map { it.first } == listOf(PluginRuntime.Phase.LOAD, PluginRuntime.Phase.RENDER) }

        engine.dispatch(PluginEvent.Click("go"))
        waitUntil { phases.size >= 4 }

        val seen = synchronized(lock) { phases.toList() }
        assertEquals(
            listOf(PluginRuntime.Phase.LOAD, PluginRuntime.Phase.RENDER, PluginRuntime.Phase.EVENT, PluginRuntime.Phase.RENDER),
            seen.map { it.first },
        )
        assertEquals(PluginLimit.LOAD.instructions, seen[0].second.instructionLimit)
        assertEquals(PluginLimit.LOAD.wallMillis, seen[0].second.wallLimitMillis)
        assertEquals(PluginLimit.RENDER.instructions, seen[1].second.instructionLimit)
        assertEquals(PluginLimit.EVENT.instructions, seen[2].second.instructionLimit)
        assertTrue(seen.all { !it.second.running && it.second.instructions > 0 && it.second.elapsedNanos >= 0 })
    }

    @Test
    fun `an event is observed as it arrives`() {
        val engine = runtime()
        engine.open(draft("def on_event(e):\n    pass\n\ndef render():\n    return ui.label(\"x\")"))
        waitUntil { uis.isNotEmpty() }
        engine.dispatch(PluginEvent.ToggleChanged("wrap", true))
        waitUntil { observedEvents.isNotEmpty() }
        assertEquals(PluginEvent.ToggleChanged("wrap", true), synchronized(lock) { observedEvents.single() })
    }

    @Test
    fun `a run replaced mid-loop is observed as cancelled`() {
        // Allowances it cannot reach first, so only the replacement stops it.
        val engine = runtime(PluginRuntime.Limits(load = PluginLimit(instructions = HUGE_STEPS, wallMillis = 5_000)))
        engine.open(draft("while True:\n    pass", id = "com.example.first"))
        Thread.sleep(150)
        engine.open(draft("def render():\n    return ui.label(\"second\")", id = "com.example.second"))
        waitUntil { failures.any { it.first == "com.example.first" } && uis.isNotEmpty() }

        val first = synchronized(lock) { failures.first { it.first == "com.example.first" }.second }
        assertEquals(PluginRuntime.Failure.Kind.CANCELLED, first.kind)
        assertFalse(synchronized(lock) { events.any { it.startsWith("stopped") } })
    }

    @Test
    fun `an error is placed in main py the way the panel shows it`() {
        assertEquals("main.py:12: boom", PluginRuntime.locatedText("boom", 12))
        assertEquals("boom", PluginRuntime.locatedText("boom", null))
        assertEquals(PluginStore.SCRIPT_FILE, PluginRuntime.SCRIPT_CHUNK)
    }

    private companion object {
        const val ID = "com.example.draft"
        const val HUGE_STEPS = 1_000_000_000_000L
    }
}

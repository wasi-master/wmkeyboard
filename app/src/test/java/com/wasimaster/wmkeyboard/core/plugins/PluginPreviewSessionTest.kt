package com.wasimaster.wmkeyboard.core.plugins

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The preview the plugin editor draws, driven with real threads, the real
 * interpreter and a direct [PluginPreviewSession.post], so every assertion
 * reads settled state.
 */
class PluginPreviewSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspace: PluginWorkspace
    private lateinit var store: PluginStore
    private var session: PluginPreviewSession? = null
    private lateinit var draftId: String

    @After
    fun tearDown() {
        session?.shutdown()
    }

    private fun manifest(id: String = ID, permissions: List<String> = emptyList()) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = id,
        name = "Preview",
        pluginVersion = "0.1",
        permissions = permissions,
    )

    private fun session(limits: PluginRuntime.Limits = PluginRuntime.Limits()): PluginPreviewSession {
        workspace = PluginWorkspace(temp.newFolder("workspace"))
        store = PluginStore(temp.newFolder("plugins"))
        draftId = workspace.create(manifest(), "", "blank")!!.draftId
        return PluginPreviewSession(workspace, store, limits = limits).also { session = it }
    }

    private fun waitUntil(timeoutMillis: Long = 10_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out; state ${session?.state?.value}; console ${session?.console()}")
    }

    private fun PluginPreviewSession.label(): String? =
        (state.value.ui.root.singleOrNull() as? PluginWidget.Label)?.text

    private fun PluginPreviewSession.kinds() = console().map { it.kind }

    @Test
    fun `a good draft renders and says it started`() {
        val preview = session()
        preview.run(draftId, "def render():\n    return ui.label(\"hello\")", manifest())
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }
        assertEquals("hello", preview.label())
        assertEquals(PluginPreviewSession.ConsoleEntry.Kind.STARTED, preview.console().first().kind)
        assertEquals(1, preview.state.value.runs)
        assertTrue(PluginRuntime.Phase.LOAD in preview.state.value.usage)
        assertTrue(PluginRuntime.Phase.RENDER in preview.state.value.usage)
    }

    @Test
    fun `printed and logged lines reach the console in order`() {
        val preview = session()
        preview.run(
            draftId,
            "print(\"one\")\nwm.log(\"two\")\n\ndef render():\n    print(\"three\")\n    return ui.label(\"x\")",
            manifest(),
        )
        waitUntil { preview.console().count { it.kind == PluginPreviewSession.ConsoleEntry.Kind.PRINT } == 3 }
        assertEquals(
            listOf("one", "two", "three"),
            preview.console().filter { it.kind == PluginPreviewSession.ConsoleEntry.Kind.PRINT }.map { it.text },
        )
        assertTrue(preview.state.value.consoleRevision > 0)
    }

    @Test
    fun `a syntax error is a console error at its line`() {
        val preview = session()
        preview.run(draftId, "a = 1\nb = = 2\n\ndef render():\n    return ui.label(\"x\")", manifest())
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.FAILED }

        val error = preview.console().single { it.kind == PluginPreviewSession.ConsoleEntry.Kind.ERROR }
        assertEquals("main.py", error.chunk)
        assertEquals(2, error.line)
        assertEquals(PluginRuntime.Failure.Kind.COMPILE, preview.state.value.failure?.kind)
    }

    @Test
    fun `an error in a handler keeps the panel and names the line`() {
        val preview = session()
        preview.run(
            draftId,
            "def on_event(e):\n    raise ValueError(\"boom\")\n\ndef render():\n    return ui.label(\"still here\")",
            manifest(),
        )
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }
        preview.send(PluginEvent.Click("go"))
        waitUntil { preview.state.value.failure != null }

        assertEquals(PluginRuntime.Failure.Kind.RUNTIME, preview.state.value.failure?.kind)
        assertEquals(2, preview.state.value.failure?.line)
        assertEquals("still here", preview.label())
        assertTrue(preview.console().single { it.kind == PluginPreviewSession.ConsoleEntry.Kind.ERROR }.text.contains("boom"))
    }

    @Test
    fun `a runaway draft stops without touching the installed plugin`() {
        val preview = session(PluginRuntime.Limits(load = PluginLimit(instructions = 200_000, wallMillis = 5_000)))
        store.adopt(manifest(), "def render():\n    return ui.label(\"installed\")")

        preview.run(draftId, "while True:\n    pass", manifest())
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.STOPPED }

        assertTrue(PluginPreviewSession.ConsoleEntry.Kind.STOPPED in preview.kinds())
        assertEquals(PluginRuntime.Failure.Kind.INSTRUCTIONS, preview.state.value.failure?.kind)
        assertEquals(0, store.plugin(ID)!!.abandonedCount)
        assertTrue(store.plugin(ID)!!.enabled)
    }

    @Test
    fun `preview storage lives in the draft, not beside the installed plugin`() {
        val preview = session()
        store.adopt(manifest(permissions = listOf("storage")), "def render():\n    return ui.label(\"x\")")

        preview.run(
            draftId,
            "wm.storage.set(\"k\", \"preview value\")\n\ndef render():\n    return ui.label(\"x\")",
            manifest(permissions = listOf("storage")),
        )
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }

        // The inspector reads the file the interpreter wrote, fresh on every call.
        assertEquals("preview value", preview.storageOf(draftId)!!.get("k"))
        assertTrue(workspace.previewStorageFile(draftId)!!.readText().contains("preview value"))
        assertFalse(store.storageFile(ID)!!.exists())
    }

    @Test
    fun `the inspector's edits reach the next run`() {
        val preview = session()
        val script = "def render():\n    return ui.label(wm.storage.get(\"k\", \"none\"))"
        preview.run(draftId, script, manifest(permissions = listOf("storage")))
        waitUntil { preview.label() == "none" }

        assertNull(preview.storageOf(draftId)!!.set("k", "edited"))
        preview.run(draftId, script, manifest(permissions = listOf("storage")))
        waitUntil { preview.label() == "edited" }
    }

    @Test
    fun `the draft manifest decides whether storage exists`() {
        val preview = session()
        val script = """
            def render():
                try:
                    there = wm.storage is not None
                except AttributeError:
                    there = False
                return ui.label(str(there))
        """.trimIndent()
        preview.run(draftId, script, manifest(permissions = listOf("storage")))
        waitUntil { preview.label() == "True" }
        preview.run(draftId, script, manifest())
        waitUntil { preview.label() == "False" }
    }

    @Test
    fun `preview reminders stay in the draft's own list`() {
        val preview = session()
        preview.run(
            draftId,
            "import time\nwm.reminders.set(\"r\", int(time.time()) + 3600, \"from the preview\")\n\n" +
                "def render():\n    return ui.label(\"x\")",
            manifest(permissions = listOf("reminders")),
        )
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }

        assertEquals("from the preview", preview.remindersOf(draftId).list().single().text)
        assertTrue(PluginReminders(store.remindersFile(ID)).list().isEmpty())
    }

    @Test
    fun `a newer run replaces one in flight without inheriting its cancellation`() {
        // Allowances the first run cannot reach, so only the second run stops it.
        val preview = session(PluginRuntime.Limits(load = PluginLimit(instructions = 1_000_000_000_000L, wallMillis = 5_000)))
        preview.run(draftId, "while True:\n    pass", manifest())
        Thread.sleep(150)
        preview.run(draftId, "def render():\n    return ui.label(\"second\")", manifest())
        waitUntil { preview.label() == "second" && PluginPreviewSession.ConsoleEntry.Kind.CANCELLED in preview.kinds() }

        assertNull(preview.state.value.failure)
        assertEquals(PluginPreviewSession.Status.READY, preview.state.value.status)
        assertEquals(2, preview.state.value.runs)
    }

    @Test
    fun `typing into a preview input reaches the script and stays in the box`() {
        val preview = session()
        preview.run(
            draftId,
            """
            typed = ""

            def on_event(e):
                global typed
                if e.type == "input_changed":
                    typed = e.value

            def render():
                return ui.column(ui.input("box"), ui.label(typed))
            """.trimIndent(),
            manifest(),
        )
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }
        preview.send(PluginEvent.InputChanged("box", "hi"))
        waitUntil {
            val column = preview.state.value.ui.root.singleOrNull() as? PluginWidget.Column
            (column?.children?.getOrNull(1) as? PluginWidget.Label)?.text == "hi"
        }
        assertEquals("hi", preview.state.value.inputs["box"])
        assertEquals(listOf("box"), preview.state.value.targets.inputs.map { it.id })
        assertEquals(listOf<PluginEvent>(PluginEvent.InputChanged("box", "hi")), preview.recordedEvents())
    }

    @Test
    fun `set_input fills the preview box`() {
        val preview = session()
        preview.run(
            draftId,
            "def on_event(e):\n    wm.ui.set_input(\"box\", \"cleared\")\n\ndef render():\n    return ui.input(\"box\")",
            manifest(),
        )
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }
        preview.send(PluginEvent.Click("go"))
        waitUntil { preview.state.value.inputs["box"] == "cleared" }
    }

    @Test
    fun `a repair is listed once while it stays the same`() {
        val preview = session()
        preview.run(
            draftId,
            "def on_event(e):\n    pass\n\ndef render():\n    return [{\"type\": \"sparkle\"}, ui.label(\"x\")]",
            manifest(),
        )
        waitUntil { preview.state.value.status == PluginPreviewSession.Status.READY }
        preview.send(PluginEvent.Click("again"))
        waitUntil { preview.recordedEvents().isNotEmpty() && preview.state.value.busy.not() }
        Thread.sleep(200)

        val repairs = preview.console().filter { it.kind == PluginPreviewSession.ConsoleEntry.Kind.REPAIR }
        assertEquals(1, repairs.size)
        assertEquals(PluginRepair.UNKNOWN_TYPE, repairs.single().repair)
    }

    @Test
    fun `clearing the console empties it and says so`() {
        val preview = session()
        preview.run(draftId, "print(\"x\")\n\ndef render():\n    return ui.label(\"x\")", manifest())
        waitUntil { preview.console().any { it.kind == PluginPreviewSession.ConsoleEntry.Kind.PRINT } }
        val before = preview.state.value.consoleRevision
        preview.clearConsole()
        assertTrue(preview.console().isEmpty())
        assertTrue(preview.state.value.consoleRevision > before)
    }

    private companion object {
        const val ID = "com.example.preview"
    }
}

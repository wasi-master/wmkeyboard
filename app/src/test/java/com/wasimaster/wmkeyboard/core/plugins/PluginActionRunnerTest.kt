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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Selection actions, run for real on their own threads in the real interpreter. */
class PluginActionRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: PluginStore
    private var runner: PluginActionRunner? = null
    private val results = LinkedBlockingQueue<PluginActionResult>()
    private val remindersMoved = AtomicInteger()

    @After
    fun tearDown() {
        runner?.shutdown()
    }

    private fun runner(limit: PluginLimit = PluginLimit.ACTION): PluginActionRunner {
        store = PluginStore(temp.newFolder())
        store.setSubsystemEnabled(true)
        return PluginActionRunner(store, limit = limit, onRemindersChanged = { remindersMoved.incrementAndGet() })
            .also { runner = it }
    }

    private fun install(
        script: String,
        actions: List<PluginActionSpec> = listOf(PluginActionSpec(id = "go", name = "Go")),
        permissions: List<String> = listOf("selection"),
        panel: Boolean = false,
        id: String = "com.example.actions",
    ): String {
        store.adopt(
            PluginManifest(
                format = PluginManifestCodec.FORMAT,
                id = id,
                name = "Actions",
                pluginVersion = "1.0.0",
                permissions = permissions,
                panel = panel,
                actions = actions,
            ),
            script,
        )
        return id
    }

    private fun PluginActionRunner.await(id: String, text: String, action: String = "go"): PluginActionResult {
        run(id, action, text) { results.put(it) }
        return results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("no result")
    }

    @Test
    fun `transform gets the selection and the action, and its string comes back`() {
        val engine = runner()
        val id = install("def transform(text, action):\n    return action + \":\" + text.upper()")
        assertEquals(PluginActionResult.Output("go:HELLO", replaces = true), engine.await(id, "hello"))
    }

    @Test
    fun `one parameter is enough for a single action`() {
        val engine = runner()
        val id = install("def transform(text):\n    return text[::-1]")
        assertEquals(PluginActionResult.Output("olleh", replaces = true), engine.await(id, "hello"))
    }

    @Test
    fun `None leaves the text alone`() {
        val engine = runner()
        val id = install("def transform(text):\n    return None")
        assertEquals(PluginActionResult.Unchanged, engine.await(id, "hello"))
    }

    @Test
    fun `an action that keeps the selection says so`() {
        val engine = runner()
        val id = install(
            "def transform(text):\n    return \"!\"",
            actions = listOf(PluginActionSpec(id = "go", name = "Go", replaces = false)),
        )
        assertEquals(PluginActionResult.Output("!", replaces = false), engine.await(id, "hello"))
    }

    @Test
    fun `an input of none passes an empty string`() {
        val engine = runner()
        val id = install(
            "def transform(text):\n    return \"[\" + text + \"]\"",
            actions = listOf(PluginActionSpec(id = "go", name = "Go", input = PluginActionSpec.INPUT_NONE)),
        )
        assertEquals(PluginActionResult.Output("[]", replaces = true), engine.await(id, "secret"))
    }

    @Test
    fun `an answer longer than any selection is cut to the cap`() {
        val engine = runner()
        val id = install("def transform(text):\n    return \"x\" * 70000")
        val output = engine.await(id, "hello") as PluginActionResult.Output
        assertEquals(PluginActionRunner.MAX_OUTPUT, output.text.length)
    }

    @Test
    fun `a script error is reported in its own words and the plugin stays on`() {
        val engine = runner()
        val id = install("def transform(text):\n    raise ValueError(\"nope\")")
        val result = engine.await(id, "hello") as PluginActionResult.Failed
        val message = result.message as PluginText.Script
        assertTrue(message.text, message.text.contains("nope"))
        assertTrue(message.text, message.text.startsWith("main.py:2: "))
        assertTrue(store.plugin(id)!!.enabled)
        assertEquals(0, store.plugin(id)!!.abandonedCount)
    }

    @Test
    fun `a script that fails to load is reported the same way`() {
        val engine = runner()
        val id = install("def transform(text):\n    return text\n\nthis is not python")
        val result = engine.await(id, "hello") as PluginActionResult.Failed
        assertTrue(result.message is PluginText.Script)
    }

    @Test
    fun `a plugin without transform says so`() {
        val engine = runner()
        val id = install("def render():\n    return ui.column()", panel = true)
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_no_transform)),
            engine.await(id, "hello"),
        )
    }

    @Test
    fun `a runaway action is stopped, counts a strike, and a second disables the plugin`() {
        val engine = runner(PluginLimit(instructions = 100_000, wallMillis = 2_000))
        val id = install("def transform(text):\n    while True:\n        pass")

        val first = engine.await(id, "hello") as PluginActionResult.Stopped
        assertEquals(PluginText.of(PluginAbortReason.INSTRUCTIONS.messageRes), first.message)
        assertFalse(first.disabled)
        assertEquals(1, store.plugin(id)!!.abandonedCount)

        val second = engine.await(id, "hello") as PluginActionResult.Stopped
        assertTrue(second.disabled)
        assertFalse(store.plugin(id)!!.enabled)
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_turned_off)),
            engine.await(id, "hello"),
        )
    }

    @Test
    fun `an action past its deadline is stopped for that`() {
        val engine = runner(PluginLimit(instructions = 1_000_000_000_000L, wallMillis = 200))
        val id = install("def transform(text):\n    while True:\n        pass")
        val result = engine.await(id, "hello") as PluginActionResult.Stopped
        assertEquals(PluginText.of(PluginAbortReason.DEADLINE.messageRes), result.message)
        assertEquals(1, store.plugin(id)!!.abandonedCount)
    }

    @Test
    fun `globals do not survive between taps but storage does`() {
        val engine = runner()
        val id = install(
            """
            def transform(text):
                global kept
                try:
                    before = kept
                except NameError:
                    before = "none"
                kept = text
                seen = wm.storage.get("seen", "none")
                wm.storage.set("seen", text)
                return before + "/" + seen
            """.trimIndent(),
            permissions = listOf("selection", "storage"),
        )
        assertEquals(PluginActionResult.Output("none/none", true), engine.await(id, "first"))
        // The global set while holding the first selection is gone; the stored value is not.
        assertEquals(PluginActionResult.Output("none/first", true), engine.await(id, "second"))
        assertEquals("second", PluginStorage(store.storageFile(id)).get("seen"))
    }

    @Test
    fun `an action that sets a reminder moves the app's wake-up`() {
        val engine = runner()
        val id = install(
            "import time\n\ndef transform(text):\n    wm.reminders.set(\"r\", int(time.time()) + 3600, text)\n    return None",
            permissions = listOf("selection", "reminders"),
        )
        assertEquals(PluginActionResult.Unchanged, engine.await(id, "call back"))
        assertEquals(1, remindersMoved.get())
        assertEquals("call back", PluginReminders(store.remindersFile(id)).list().single().text)
    }

    @Test
    fun `an action that is gone, or a plugin switched off, does not run`() {
        val engine = runner()
        val id = install("def transform(text):\n    return text")
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_action_missing)),
            engine.await(id, "x", action = "missing"),
        )
        store.setEnabled(id, false)
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_turned_off)),
            engine.await(id, "x"),
        )
        store.setEnabled(id, true)
        store.setSubsystemEnabled(false)
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_turned_off)),
            engine.await(id, "x"),
        )
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_not_installed)),
            engine.await("com.example.absent", "x"),
        )
    }

    @Test
    fun `an action needs the selection permission to run`() {
        val engine = runner()
        val id = install("def transform(text):\n    return text", permissions = emptyList())
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_action_missing)),
            engine.await(id, "x"),
        )
    }

    @Test
    fun `a cancelled call never answers and earns no strike`() {
        // Allowances it cannot reach before the cancel, so only the cancel stops it.
        val engine = runner(PluginLimit(instructions = 1_000_000_000_000L, wallMillis = 5_000))
        val id = install("def transform(text):\n    while True:\n        pass")
        engine.run(id, "go", "x") { results.put(it) }
        Thread.sleep(100)
        engine.cancel()
        assertNull(results.poll(1, TimeUnit.SECONDS))
        assertEquals(0, store.plugin(id)!!.abandonedCount)
    }

    @Test
    fun `a new tap cancels the one before it`() {
        val engine = runner(PluginLimit(instructions = 1_000_000_000_000L, wallMillis = 5_000))
        val slow = install("def transform(text):\n    while True:\n        pass", id = "com.example.slow")
        val fast = install("def transform(text):\n    return \"fast\"", id = "com.example.fast")
        engine.run(slow, "go", "x") { results.put(it) }
        Thread.sleep(100)
        assertEquals(PluginActionResult.Output("fast", true), engine.await(fast, "x"))
        assertNull(results.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(0, store.plugin(slow)!!.abandonedCount)
    }
}

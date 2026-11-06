package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Selection actions, run for real on their own threads. */
class PluginActionRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: PluginStore
    private var runner: PluginActionRunner? = null
    private val results = LinkedBlockingQueue<PluginActionResult>()

    @After
    fun tearDown() {
        runner?.shutdown()
    }

    private fun runner(limit: PluginLimit = PluginLimit.ACTION): PluginActionRunner {
        store = PluginStore(temp.newFolder())
        store.setSubsystemEnabled(true)
        return PluginActionRunner(store, limit = limit).also { runner = it }
    }

    private fun install(
        script: String,
        actions: List<PluginActionSpec> = listOf(PluginActionSpec(id = "go", name = "Go")),
        permissions: List<String> = listOf("selection"),
        language: String = PluginManifestCodec.LANGUAGE_LUA,
        id: String = "com.example.actions",
    ): String {
        store.adopt(
            PluginManifest(
                format = PluginManifestCodec.FORMAT,
                id = id,
                name = "Actions",
                pluginVersion = "1.0.0",
                permissions = permissions,
                language = language,
                panel = language == PluginManifestCodec.LANGUAGE_LUA,
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
    fun `transform gets the selection and its string comes back`() {
        val engine = runner()
        val id = install("function transform(text, action) return action .. ':' .. text:upper() end")
        assertEquals(PluginActionResult.Output("go:HELLO", replaces = true), engine.await(id, "hello"))
    }

    @Test
    fun `nil leaves the text alone`() {
        val engine = runner()
        val id = install("function transform(text) return nil end")
        assertEquals(PluginActionResult.Unchanged, engine.await(id, "hello"))
    }

    @Test
    fun `an action that keeps the selection says so`() {
        val engine = runner()
        val id = install(
            "function transform(text) return '!' end",
            actions = listOf(PluginActionSpec(id = "go", name = "Go", replaces = false)),
        )
        assertEquals(PluginActionResult.Output("!", replaces = false), engine.await(id, "hello"))
    }

    @Test
    fun `an input of none passes an empty string`() {
        val engine = runner()
        val id = install(
            "function transform(text) return '[' .. text .. ']' end",
            actions = listOf(PluginActionSpec(id = "go", name = "Go", input = PluginActionSpec.INPUT_NONE)),
        )
        assertEquals(PluginActionResult.Output("[]", replaces = true), engine.await(id, "secret"))
    }

    @Test
    fun `a script error is reported and the plugin stays on`() {
        val engine = runner()
        val id = install("function transform(text) error('nope') end")
        val result = engine.await(id, "hello") as PluginActionResult.Failed
        assertTrue((result.message as PluginText.Script).text.contains("nope"))
        assertTrue(store.plugin(id)!!.enabled)
    }

    @Test
    fun `a plugin without transform says so`() {
        val engine = runner()
        val id = install("function render() return ui.column {} end")
        assertEquals(
            PluginActionResult.Failed(PluginText.of(R.string.core_plugins_error_no_transform)),
            engine.await(id, "hello"),
        )
    }

    @Test
    fun `a runaway action is stopped and counts a strike`() {
        val engine = runner(PluginLimit(instructions = 100_000, wallMillis = 2_000))
        val id = install("function transform(text) while true do end end")
        val result = engine.await(id, "hello") as PluginActionResult.Stopped
        assertEquals(PluginText.of(PluginAbortReason.INSTRUCTIONS.messageRes), result.message)
        assertEquals(1, store.plugin(id)!!.abandonedCount)
    }

    @Test
    fun `globals do not survive between taps but storage does`() {
        val engine = runner()
        val id = install(
            """
            function transform(text)
              local before = tostring(kept) .. '/' .. tostring(wm.storage.get('seen'))
              kept = text
              wm.storage.set('seen', text)
              return before
            end
            """.trimIndent(),
            permissions = listOf("selection", "storage"),
        )
        assertEquals(PluginActionResult.Output("nil/nil", true), engine.await(id, "first"))
        // The global set while holding the first selection is gone; the stored value is not.
        assertEquals(PluginActionResult.Output("nil/first", true), engine.await(id, "second"))
    }

    @Test
    fun `an action that is gone, or a plugin switched off, does not run`() {
        val engine = runner()
        val id = install("function transform(text) return text end")
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
    }

    @Test
    fun `a cancelled call never answers`() {
        val engine = runner()
        val id = install("function transform(text) local n = 0 for i = 1, 5000000 do n = n + i end return 'late' end")
        engine.run(id, "go", "x") { results.put(it) }
        engine.cancel()
        assertNull(results.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun `a python action runs its transform`() {
        val engine = runner()
        val id = install(
            "def transform(text):\n    return text[::-1]",
            actions = listOf(PluginActionSpec(id = "transform", name = "Reverse")),
            language = PluginManifestCodec.LANGUAGE_PYTHON,
            id = "clink.reverse",
        )
        assertEquals(PluginActionResult.Output("olleh", true), engine.await(id, "hello", action = "transform"))
    }

    @Test
    fun `a python action that loops forever is stopped without a strike`() {
        val engine = runner(PluginLimit(instructions = 100_000, wallMillis = 500))
        val id = install(
            "def transform(text):\n    while True:\n        pass",
            actions = listOf(PluginActionSpec(id = "transform", name = "Spin")),
            language = PluginManifestCodec.LANGUAGE_PYTHON,
            id = "clink.spin",
        )
        val result = engine.await(id, "x", action = "transform")
        assertTrue(result.toString(), result is PluginActionResult.Failed)
        assertEquals(0, store.plugin(id)!!.abandonedCount)
        assertTrue(store.plugin(id)!!.enabled)
    }
}

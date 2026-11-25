package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** File previews and thumbnails, run for real in the interpreter on their own threads. */
class PluginPreviewRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: PluginStore
    private var runner: PluginPreviewRunner? = null
    private val results = LinkedBlockingQueue<PluginPreviewResult>()

    @After
    fun tearDown() {
        runner?.shutdown()
    }

    private fun runner(limit: PluginLimit = PluginLimit.PREVIEW): PluginPreviewRunner {
        store = PluginStore(temp.newFolder())
        store.setSubsystemEnabled(true)
        return PluginPreviewRunner(store, cacheDir = temp.newFolder(), previewLimit = limit).also { runner = it }
    }

    private fun install(script: String, preview: PluginPreviewSpec = PluginPreviewSpec(id = "csv", extensions = listOf("csv"), thumbnails = true)): PluginPreviewRef {
        store.adopt(
            PluginManifest(
                format = PluginManifestCodec.FORMAT,
                id = "com.example.csv",
                name = "Tables",
                pluginVersion = "1.0.0",
                permissions = listOf("previews"),
                panel = false,
                previews = listOf(preview),
            ),
            script,
        )
        return store.previews().single()
    }

    /** A file in memory, counting the reads past its head. */
    private class Bytes(override val name: String, private val data: ByteArray) : PreviewSource {
        val reads = AtomicInteger()
        override val mime: String? = null
        override val size: Long = data.size.toLong()
        override val modified: Long? = 1_700_000_000_000
        override val identity: String = "/sdcard/$name"
        override fun read(offset: Long, length: Int): ByteArray {
            reads.incrementAndGet()
            val from = offset.toInt().coerceAtMost(data.size)
            return data.copyOfRange(from, (from + length).coerceAtMost(data.size))
        }
    }

    private fun PluginPreviewRunner.await(ref: PluginPreviewRef, source: PreviewSource): PluginPreviewResult {
        preview(ref, source) { results.put(it) }
        return results.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no result")
    }

    @Test
    fun `a preview draws a table from the file's text`() {
        val engine = runner()
        val ref = install(
            "from wm import plugin, ui, formats\n\n" +
                "@plugin.preview('csv')\ndef show(file):\n    rows = formats.csv(file.text())\n    ui.table(rows[1:], header=rows[0])\n",
        )
        val shown = engine.await(ref, Bytes("a.csv", "name,n\na,1\nb,2\n".toByteArray())) as PluginPreviewResult.Shown
        val table = (shown.ui.root.single() as PluginWidget.Table).table
        assertEquals(listOf("name", "n"), table.header)
        assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), table.rows)
    }

    @Test
    fun `read reaches past the head, a megabyte at a time`() {
        val engine = runner()
        val ref = install(
            "from wm import plugin, ui\n\n" +
                "@plugin.preview('csv')\ndef show(file):\n    ui.label(str(file.truncated) + ':' + file.read(6, 4).decode())\n",
            PluginPreviewSpec(id = "csv", extensions = listOf("csv"), maxBytes = 4),
        )
        val source = Bytes("a.csv", "0123456789".toByteArray())
        val shown = engine.await(ref, source) as PluginPreviewResult.Shown
        assertEquals("True:6789", (shown.ui.root.single() as PluginWidget.Label).text)
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `a failing preview says why and a missing one says it cannot show the file`() {
        val engine = runner()
        val ref = install("from wm import plugin\n\n@plugin.preview('csv')\ndef show(file):\n    raise ValueError('bad row')\n")
        val failed = engine.await(ref, Bytes("a.csv", ByteArray(1))) as PluginPreviewResult.Failed
        assertTrue((failed.message as PluginText.Script).text.contains("bad row"))
        val missing = install("x = 1\n")
        val none = engine.await(missing, Bytes("a.csv", ByteArray(1))) as PluginPreviewResult.Failed
        assertEquals(PluginText.of(R.string.core_plugins_error_no_preview), none.message)
    }

    @Test
    fun `a runaway preview is stopped and earns a strike`() {
        val engine = runner(PluginLimit(instructions = 200_000, wallMillis = 2_000))
        val ref = install("from wm import plugin\n\n@plugin.preview('csv')\ndef show(file):\n    while True:\n        pass\n")
        assertTrue(engine.await(ref, Bytes("a.csv", ByteArray(1))) is PluginPreviewResult.Stopped)
        assertEquals(1, store.plugin(ref.plugin.id)?.abandonedCount)
    }

    @Test
    fun `thumbnails are cached, none is remembered, and three failures pause the plugin`() {
        val engine = runner()
        val ref = install(
            "from wm import plugin\n\n" +
                "@plugin.thumbnail('csv')\ndef thumb(file):\n    if file.name == 'none.csv':\n        return None\n" +
                "    if file.name.startswith('bad'):\n        raise ValueError('no')\n    return b'PNG' + file.data[:1]\n",
        )
        val first = Bytes("a.csv", "x".toByteArray())
        assertArrayEquals("PNGx".toByteArray(), engine.thumbnail(ref, first))
        assertArrayEquals("PNGx".toByteArray(), engine.thumbnail(ref, Bytes("a.csv", "x".toByteArray())))
        assertNull(engine.thumbnail(ref, Bytes("none.csv", "x".toByteArray())))
        repeat(3) { assertNull(engine.thumbnail(ref, Bytes("bad$it.csv", "x".toByteArray()))) }
        // Paused: even a file that would work draws nothing, short of a cached one.
        assertNull(engine.thumbnail(ref, Bytes("b.csv", "y".toByteArray())))
        assertArrayEquals("PNGx".toByteArray(), engine.thumbnail(ref, first))
    }

    @Test
    fun `preview read is refused outside a preview`() {
        val engine = runner()
        store.adopt(
            PluginManifest(
                format = PluginManifestCodec.FORMAT,
                id = "com.example.sneak",
                name = "Sneak",
                pluginVersion = "1",
                permissions = listOf("previews", "selection"),
                panel = false,
                actions = listOf(PluginActionSpec(id = "go", name = "Go")),
                previews = listOf(PluginPreviewSpec(id = "p", extensions = listOf("txt"))),
            ),
            "from wm import plugin, _core\n\n@plugin.action('go')\ndef go(text):\n    return str(_core.call('preview.read', {'offset': 0, 'length': 1}))\n",
        )
        val actions = PluginActionRunner(store)
        val out = LinkedBlockingQueue<PluginActionResult>()
        actions.run("com.example.sneak", "go", "x") { out.put(it) }
        val result = out.poll(20, TimeUnit.SECONDS) as PluginActionResult.Failed
        assertTrue((result.message as PluginText.Script).text.contains("PermissionError"))
        actions.shutdown()
        engine.cancel()
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/** Clink's `.clinkext` actions, read as plugins. The files are anti-ltd/clink-actions' own. */
class ClinkActionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val reverse =
        """{"id":"repo-reverse","name":"Reverse","icon":"arrow.left.arrow.right","summary":"Flip the text backwards",""" +
            """"source":"def transform(text):\n    return text[::-1]","input":"word","replacesInput":true,"enabled":true}"""

    private val wordCount =
        """{"id":"repo-word-count","name":"Word Count","icon":"number","summary":"Count words before the cursor",""" +
            """"source":"def transform(text):\n    return str(len(text.split())) + \" words\"","input":"before",""" +
            """"replacesInput":false,"enabled":true}"""

    private val shrug =
        """{"id":"repo-shrug","name":"Shrug","icon":"face.dashed","summary":"Insert a shrug emoticon",""" +
            """"source":"def transform(text):\n    return \"x\"","input":"none","replacesInput":true,"enabled":true}"""

    @Test
    fun `a clink action becomes an actions-only python plugin`() {
        val (manifest, source) = ClinkAction.toPlugin(reverse)!!
        assertEquals("clink.repo-reverse", manifest.id)
        assertEquals("Reverse", manifest.name)
        assertEquals("Flip the text backwards", manifest.description)
        assertEquals(PluginManifestCodec.LANGUAGE_PYTHON, manifest.language)
        assertFalse(manifest.panel)
        assertEquals(listOf(PluginPermission.SelectedText.wire), manifest.permissions)
        assertTrue(manifest.pluginVersion.startsWith("#"))
        val action = manifest.actions.single()
        assertEquals(ClinkAction.ACTION_ID, action.id)
        assertEquals("arrow.left.arrow.right", action.icon)
        assertTrue(action.replaces)
        // Clink's "word" scope reads the selection here.
        assertEquals(PluginActionSpec.INPUT_SELECTION, action.input)
        assertTrue(source.startsWith("def transform(text):"))
    }

    @Test
    fun `replacesInput false keeps the selection and none passes no text`() {
        assertFalse(ClinkAction.toPlugin(wordCount)!!.first.actions.single().replaces)
        assertEquals(PluginActionSpec.INPUT_NONE, ClinkAction.toPlugin(shrug)!!.first.actions.single().input)
    }

    @Test
    fun `what the converter writes is a manifest the importer accepts`() {
        for (file in listOf(reverse, wordCount, shrug)) {
            val manifest = ClinkAction.toPlugin(file)!!.first
            assertEquals(file, emptyList<PluginManifestCodec.Problem>(), PluginManifestCodec.problems(manifest))
        }
    }

    @Test
    fun `a clink file installs through the ordinary plugin reader`() {
        val store = PluginStore(temp.newFolder("plugins"))
        store.setSubsystemEnabled(true)
        val result = PluginFile.import(reverse.byteInputStream(), store) as PluginImportResult.Imported
        val plugin = result.plugin
        assertTrue(plugin.isPython)
        assertFalse(plugin.panel)
        assertEquals("Reverse", plugin.actions.single().name)
        assertEquals("def transform(text):\n    return text[::-1]", store.script(plugin.id))
        assertEquals(listOf(PluginActionRef.keyOf(plugin.id, "transform")), store.selectionActions().map { it.key })
        // Actions-only: nothing for the keyboard's plugin panel to open.
        assertTrue(store.panelPlugins().isEmpty())
    }

    @Test
    fun `the disclosure reads a clink file without running anything`() {
        val read = PluginFile.readManifest(reverse.byteInputStream()) as PluginManifestResult.Ok
        assertEquals(listOf(PluginPermission.SelectedText), read.permissions)
    }

    @Test
    fun `a real plugin archive still reads as itself`() {
        val manifest = PluginManifest(
            format = PluginManifestCodec.FORMAT,
            id = "com.example.demo",
            name = "Demo",
            pluginVersion = "1.0.0",
        )
        val out = ByteArrayOutputStream()
        PluginFile.write(out, manifest, "def render():\n    return ui.column()")
        val read = PluginFile.read(out.toByteArray().inputStream()) as PluginReadResult.Ok
        assertEquals("com.example.demo", read.manifest.id)
    }

    @Test
    fun `other json is not a clink action`() {
        assertFalse(ClinkAction.looksLikeClinkAction("""{"id": "x", "name": "A theme"}"""))
        assertFalse(ClinkAction.looksLikeClinkAction("""{"name": "Panel", "source": "def view():\n    pass"}"""))
        assertFalse(ClinkAction.looksLikeClinkAction("not json"))
        assertTrue(ClinkAction.looksLikeClinkAction(reverse))
    }
}

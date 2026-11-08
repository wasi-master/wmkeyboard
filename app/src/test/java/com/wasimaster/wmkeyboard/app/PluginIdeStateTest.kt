package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.plugins.PluginThumbnail
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.PyHostShape
import com.wasimaster.wmkeyboard.core.plugins.PySeverity
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling
import com.wasimaster.wmkeyboard.core.plugins.SnapshotReason
import com.wasimaster.wmkeyboard.plugins.R as PluginsR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The plugin editor's decisions, over a real workspace and store on a temporary folder. */
class PluginIdeStateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspace: PluginWorkspace
    private lateinit var store: PluginStore

    private fun setUp(): Pair<String, PluginIdeState> {
        workspace = PluginWorkspace(temp.newFolder("workspace"))
        store = PluginStore(temp.newFolder("plugins"))
        val draft = newBlankDraft(workspace, store, "Counter")!!
        return draft.draftId to PluginIdeState(draft.draftId, WorkspaceIdePorts(workspace, store, draft.draftId))
    }

    @Test
    fun `a new draft starts from the blank plugin with a free identifier`() {
        val (draftId, ide) = setUp()
        assertEquals(BLANK_PLUGIN_SCRIPT, ide.savedText)
        assertEquals("my.counter", ide.manifest.id)
        assertEquals("Counter", ide.manifest.name)
        assertTrue(ide.problems.isEmpty())
        assertEquals(BLANK_PLUGIN_SCRIPT, workspace.script(draftId))
    }

    @Test
    fun `the blank plugin is already formatted and the checks find nothing in it`() {
        assertEquals(BLANK_PLUGIN_SCRIPT, PythonTooling.format(BLANK_PLUGIN_SCRIPT))
        assertNull(PythonCode.problem(BLANK_PLUGIN_SCRIPT))
        assertTrue(PythonCode.parses(BLANK_PLUGIN_SCRIPT))
        val (_, ide) = setUp()
        val found = PythonTooling.analyse(BLANK_PLUGIN_SCRIPT, PyHostShape.of(ide.manifest)).diagnostics
            .filter { it.severity != PySeverity.INFO }
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun `the blank plugin runs and draws its counter`() {
        val ui = PluginThumbnail.render(InstalledPlugin(id = "my.counter", name = "Counter", version = "0.1.0"), BLANK_PLUGIN_SCRIPT)
        val column = ui!!.root.single() as PluginWidget.Column
        assertEquals("Pressed 0 times", (column.children.first() as PluginWidget.Label).text)
        assertTrue(column.children.any { it is PluginWidget.Button && it.id == "add" && it.primary })
    }

    @Test
    fun `identifiers avoid what is taken, and a name with no Latin letters still gets one`() {
        assertEquals("my.counter", uniquePluginId("Counter", emptySet()))
        assertEquals("my.counter-3", uniquePluginId("Counter", setOf("my.counter", "my.counter-2")))
        assertEquals("my.plugin", uniquePluginId("কিছু", emptySet()))
        assertEquals("my.two-words", uniquePluginId("  Two   Words! ", emptySet()))
        assertTrue(Regex("^[a-z0-9][a-z0-9._-]{2,63}$").matches(uniquePluginId("x".repeat(200), emptySet())))
    }

    @Test
    fun `saving writes only a change`() {
        val (draftId, ide) = setUp()
        assertFalse(ide.isDirty(BLANK_PLUGIN_SCRIPT))
        assertTrue(ide.save("# changed"))
        assertEquals("# changed", workspace.script(draftId))
        assertFalse(ide.isDirty("# changed"))
        assertTrue(ide.isDirty("# changed again"))
    }

    @Test
    fun `install refuses a manifest the importer would refuse and installs nothing`() {
        val (_, ide) = setUp()
        ide.updateManifest(ide.manifest.copy(id = "Not Valid"))
        val outcome = ide.publish(BLANK_PLUGIN_SCRIPT)
        assertTrue(outcome is PublishOutcome.Invalid)
        assertTrue(store.plugins().isEmpty())
    }

    @Test
    fun `install refuses a script larger than an import would take`() {
        val (_, ide) = setUp()
        val huge = "# " + "x".repeat(260 * 1024)
        assertTrue(ide.publish(huge) is PublishOutcome.Invalid)
        assertTrue(store.plugins().isEmpty())
    }

    @Test
    fun `install refuses a Lua manifest in the importer's words`() {
        val (_, ide) = setUp()
        ide.updateManifest(ide.manifest.copy(language = PluginManifestCodec.LANGUAGE_LUA))
        assertEquals(
            PublishOutcome.Invalid(PluginText.of(PluginsR.string.core_plugins_reject_lua, "Counter")),
            ide.publish(BLANK_PLUGIN_SCRIPT),
        )
        assertTrue(store.plugins().isEmpty())
    }

    @Test
    fun `install publishes the draft, keeps a version and records it`() {
        val (draftId, ide) = setUp()
        val outcome = ide.publish(BLANK_PLUGIN_SCRIPT) as PublishOutcome.Published
        assertEquals("0.1.0", outcome.version)
        assertFalse(outcome.replaced)
        assertEquals(BLANK_PLUGIN_SCRIPT, store.script("my.counter"))
        assertEquals("0.1.0", workspace.draft(draftId)!!.publishedVersion)
        assertEquals(SnapshotReason.PUBLISH.wire, workspace.snapshots(draftId).first().reason)
    }

    @Test
    fun `installing again keeps a plugin the user turned off turned off`() {
        val (_, ide) = setUp()
        ide.publish(BLANK_PLUGIN_SCRIPT)
        store.setEnabled("my.counter", false)
        ide.updateManifest(ide.manifest.copy(pluginVersion = "0.2.0"))
        val outcome = ide.publish("$BLANK_PLUGIN_SCRIPT# two\n") as PublishOutcome.Published
        assertTrue(outcome.replaced)
        assertFalse(store.plugin("my.counter")!!.enabled)
        assertEquals("0.2.0", store.plugin("my.counter")!!.version)
    }

    @Test
    fun `a saved version is kept once until the code changes`() {
        val (_, ide) = setUp()
        assertNotNull(ide.saveVersion(BLANK_PLUGIN_SCRIPT))
        assertNull(ide.saveVersion(BLANK_PLUGIN_SCRIPT))
        assertNotNull(ide.saveVersion("# different"))
    }

    @Test
    fun `editing an installed plugin reuses its draft or makes one from what is installed`() {
        workspace = PluginWorkspace(temp.newFolder("workspace"))
        store = PluginStore(temp.newFolder("plugins"))
        store.adopt(
            PluginManifest(format = PluginManifestCodec.FORMAT, id = "com.example.tool", name = "Tool", pluginVersion = "1.0.0"),
            INSTALLED,
        )

        val first = draftForInstalled(workspace, store, "com.example.tool")!!
        assertEquals(INSTALLED, workspace.script(first))
        assertEquals("com.example.tool", workspace.manifest(first)!!.id)
        assertEquals(first, draftForInstalled(workspace, store, "com.example.tool"))
        assertNull(draftForInstalled(workspace, store, "com.example.absent"))
    }

    private companion object {
        const val INSTALLED = "def render():\n    return ui.label(\"installed\")\n"
    }
}

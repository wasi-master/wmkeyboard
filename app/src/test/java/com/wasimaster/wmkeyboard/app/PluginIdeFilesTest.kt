package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A plugin of several files in the editor: every file opens, saves, and goes with the plugin on Install. */
class PluginIdeFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var workspace: PluginWorkspace
    private lateinit var store: PluginStore

    private fun installed(): String {
        workspaceDir = temp.newFolder("workspace")
        workspace = PluginWorkspace(workspaceDir)
        store = PluginStore(temp.newFolder("plugins"))
        store.adopt(MANIFEST, MAIN, modules = MODULES, assets = ASSETS, locales = LOCALES)
        return draftForInstalled(workspace, store, ID)!!
    }

    private fun ide(draftId: String) = PluginIdeState(draftId, WorkspaceIdePorts(workspace, store, draftId))

    @Test
    fun `a draft of an installed plugin holds all its files`() {
        val draftId = installed()
        assertEquals(listOf("helpers.py", "pkg/util.py", "assets/logo.png", "locales/fr.json"), workspace.files(draftId))
        assertEquals(MODULES, workspace.modules(draftId))
        assertEquals(LOCALES, workspace.locales(draftId))
        assertArrayEquals(LOGO, workspace.assets(draftId)["logo.png"])
        assertEquals(listOf("helpers", "pkg.util"), ide(draftId).moduleNames)
    }

    @Test
    fun `install keeps every module, asset and translation`() {
        val ide = ide(installed())
        assertTrue(ide.publish(MAIN) is PublishOutcome.Published)
        assertEquals(MODULES, store.modules(ID))
        assertEquals(LOCALES, store.locales(ID))
        assertArrayEquals(LOGO, store.asset(ID, "logo.png"))
    }

    @Test
    fun `an open module saves to itself and the entry script stays as it was`() {
        val draftId = installed()
        val ide = ide(draftId)
        assertTrue(ide.open("helpers.py", MAIN))
        assertFalse(ide.onEntry)
        assertEquals(MODULES["helpers.py"], ide.savedText)
        assertTrue(ide.save("def shout(text):\n    return text.upper() + \"!\"\n"))
        assertEquals(MAIN, workspace.script(draftId))
        assertTrue(workspace.fileText(draftId, "helpers.py")!!.contains("+ \"!\""))
        assertTrue(ide.open(PluginIdeState.ENTRY, ide.savedText))
        assertEquals(MAIN, ide.savedText)
    }

    @Test
    fun `install from an open module installs the entry script with the module as typed`() {
        val ide = ide(installed())
        ide.open("helpers.py", MAIN)
        val typed = "def shout(text):\n    return text\n"
        assertTrue(ide.publish(typed) is PublishOutcome.Published)
        assertEquals(MAIN, store.script(ID))
        assertEquals(typed, store.modules(ID)["helpers.py"])
    }

    @Test
    fun `a run takes the open file as typed`() {
        val ide = ide(installed())
        ide.open("helpers.py", MAIN)
        val inputs = ide.runInputs("def shout(text):\n    return 1\n")
        assertEquals(MAIN, inputs.script)
        assertEquals("def shout(text):\n    return 1\n", inputs.modules["helpers.py"])
        assertEquals(MODULES["pkg/util.py"], inputs.modules["pkg/util.py"])
    }

    @Test
    fun `a new file gets py when bare, refuses bad names and the entry's, and opens`() {
        val draftId = installed()
        val ide = ide(draftId)
        assertEquals(FileChange.Done, ide.addFile("words", MAIN))
        assertEquals("words.py", ide.file)
        assertEquals("", ide.savedText)
        assertTrue("words.py" in workspace.files(draftId))
        assertEquals(FileChange.BadName, ide.addFile("../escape.py", ""))
        assertEquals(FileChange.BadName, ide.addFile("assets/Logo.PNG", ""))
        assertEquals(FileChange.Taken("main.py"), ide.addFile("main.py", ""))
        assertEquals(FileChange.Taken("helpers.py"), ide.addFile("helpers", ""))
        assertEquals(FileChange.Done, ide.addFile("locales/de.json", ""))
        assertTrue(PluginIdeFilesTestLocales.parses(ide.savedText))
    }

    @Test
    fun `rename moves the open file and delete goes back to the entry`() {
        val draftId = installed()
        val ide = ide(draftId)
        ide.open("helpers.py", MAIN)
        assertEquals(FileChange.Done, ide.renameFile("shouting.py", ide.savedText))
        assertEquals("shouting.py", ide.file)
        assertNull(workspace.fileText(draftId, "helpers.py"))
        assertEquals(MODULES["helpers.py"], workspace.fileText(draftId, "shouting.py"))
        assertEquals(FileChange.Done, ide.deleteFile())
        assertTrue(ide.onEntry)
        assertEquals(MAIN, ide.savedText)
        assertFalse("shouting.py" in workspace.files(draftId))
        ide.open("pkg/util.py", MAIN)
        ide.deleteFile()
        assertFalse(File(workspaceDir, "$draftId/${PluginWorkspace.FILES_DIR}/pkg").exists())
    }

    @Test
    fun `a picture is listed but does not open as text`() {
        val draftId = installed()
        val ide = ide(draftId)
        assertFalse(PluginWorkspace.isTextFile("assets/logo.png"))
        assertFalse(ide.open("assets/logo.png", MAIN))
        assertTrue(ide.onEntry)
    }

    @Test
    fun `install refuses files on a plugin below API 5`() {
        val ide = ide(installed())
        ide.updateManifest(ide.manifest.copy(apiVersion = PluginManifestCodec.PACKAGE_API - 1))
        assertEquals(
            PublishOutcome.Invalid(PluginText.of(R.string.plugin_ide_files_needs_api, "main.py", PluginManifestCodec.PACKAGE_API)),
            ide.publish(MAIN),
        )
    }

    @Test
    fun `install refuses a translation that is not one`() {
        val ide = ide(installed())
        ide.open("locales/fr.json", MAIN)
        assertTrue(ide.publish("[1, 2]") is PublishOutcome.Invalid)
        assertEquals(LOCALES, store.locales(ID))
    }

    @Test
    fun `a draft made before drafts held files gets them once`() {
        val draftId = installed()
        // As a draft made by the old editor: no files and the old layout.
        File(workspaceDir, "$draftId/${PluginWorkspace.FILES_DIR}").deleteRecursively()
        val index = File(workspaceDir, "projects.json")
        index.writeText(index.readText().replace("\"layout\":${PluginWorkspace.FILES_LAYOUT}", "\"layout\":1"))
        workspace.reload()
        assertEquals(1, workspace.draft(draftId)!!.layout)

        assertEquals(draftId, draftForInstalled(workspace, store, ID))
        assertEquals(listOf("helpers.py", "pkg/util.py", "assets/logo.png", "locales/fr.json"), workspace.files(draftId))

        // A file deleted afterwards stays deleted.
        workspace.deleteFile(draftId, "helpers.py")
        adoptInstalledFiles(workspace, store, draftId)
        assertFalse("helpers.py" in workspace.files(draftId))
    }

    @Test
    fun `duplicate copies the files`() {
        val draftId = installed()
        val copy = workspace.duplicate(draftId)!!
        assertEquals(workspace.files(draftId), workspace.files(copy.draftId))
        assertEquals(MODULES, workspace.modules(copy.draftId))
    }

    private companion object {
        const val ID = "com.example.many"
        val MANIFEST = PluginManifest(
            format = PluginManifestCodec.FORMAT,
            id = ID,
            name = "Many",
            pluginVersion = "1.0.0",
            apiVersion = PluginManifestCodec.PACKAGE_API,
        )
        const val MAIN = "from wm import ui\nimport helpers\n\n\ndef render():\n    ui.label(helpers.shout(\"hi\"))\n"
        val MODULES = mapOf(
            "helpers.py" to "def shout(text):\n    return text.upper()\n",
            "pkg/util.py" to "VALUE = 1\n",
        )
        val LOGO = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2)
        val ASSETS = mapOf("logo.png" to LOGO)
        val LOCALES = mapOf("fr" to "{\"hi\": \"salut\"}")
    }
}

private object PluginIdeFilesTestLocales {
    fun parses(text: String): Boolean = com.wasimaster.wmkeyboard.core.plugins.PluginLocales.parse(text) != null
}

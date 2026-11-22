package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ToolboxFolder] helpers (#709): merge, take out, dissolve, and the stored form. */
class ToolboxFoldersTest {

    private val d = ToolbarTool.DICTIONARY.key
    private val g = ToolbarTool.GRAMMAR.key
    private val t = ToolbarTool.TRANSLATE.key
    private val c = ToolbarTool.CLIPBOARD.key
    private val grocy = pluginToolKey("com.example.grocy")

    @Test
    fun droppingAToolOnAnotherMakesAFolderOfTheTwo() {
        val folders = emptyList<ToolboxFolder>().withMerge(dropped = g, target = d, newName = "Folder")
        assertEquals(listOf(ToolboxFolder("f1", "Folder", listOf(d, g))), folders)
    }

    @Test
    fun droppingOnAToolInAFolderFilesItThere() {
        val start = listOf(ToolboxFolder("f1", "Language", listOf(d, g)))
        val folders = start.withMerge(dropped = t, target = g, newName = "Folder")
        assertEquals(listOf(ToolboxFolder("f1", "Language", listOf(d, g, t))), folders)
        assertEquals(folders, start.withAdded(t, "f1"))
    }

    @Test
    fun movingTheSecondToolOutDissolvesTheFolder() {
        val start = listOf(ToolboxFolder("f1", "Language", listOf(d, g)))
        // Into a new folder with another tool: the old one is left with one, and goes.
        val folders = start.withMerge(dropped = g, target = c, newName = "Folder")
        assertEquals(listOf(ToolboxFolder("f1", "Folder", listOf(c, g))), folders)
        assertTrue(start.withoutTool(d).isEmpty())
    }

    @Test
    fun droppingOnItselfOrItsOwnFolderChangesNothing() {
        val start = listOf(ToolboxFolder("f1", "Language", listOf(d, g)))
        assertEquals(start, start.withMerge(dropped = d, target = d, newName = "Folder"))
        assertEquals(start, start.withMerge(dropped = d, target = g, newName = "Folder"))
    }

    @Test
    fun newIdsSkipTheOnesTaken() {
        val start = listOf(ToolboxFolder("f1", "A", listOf(d, g)), ToolboxFolder("f3", "B", listOf(t, c)))
        assertEquals("f2", start.nextFolderId())
    }

    @Test
    fun theStoredFormRoundTrips() {
        val folders = listOf(
            ToolboxFolder("f1", "Language", listOf(d, g), legacyPinned = true),
            ToolboxFolder("f2", "Copy, \"paste\"", listOf(t, c)),
        )
        assertEquals(folders, decodeToolboxFolders(encodeToolboxFolders(folders)))
        assertNull(encodeToolboxFolders(emptyList()))
    }

    @Test
    fun decodingDropsWhatCannotBeAFolder() {
        val raw = """[{"id":"f1","name":"A","tools":["DICTIONARY","NOT_A_TOOL"]},""" +
            """{"id":"f2","name":"B","tools":["GRAMMAR","TRANSLATE","GRAMMAR"]},""" +
            """{"id":"f3","name":"C","tools":["TRANSLATE","CLIPBOARD"]},"junk"]"""
        // f1 is down to one tool; f3 loses TRANSLATE to f2, which had it first.
        assertEquals(listOf(ToolboxFolder("f2", "B", listOf(g, t))), decodeToolboxFolders(raw))
        assertTrue(decodeToolboxFolders("not json").isEmpty())
    }

    @Test
    fun aFolderPinnedBeforeFoldersHadAPlaceJoinsTheEndOfTheBar() {
        // A build before #1497 drew a pinned folder after the tools: that is where it lands.
        val raw = """[{"id":"f1","name":"Language","tools":["DICTIONARY","GRAMMAR"],"pinned":true},""" +
            """{"id":"f2","name":"B","tools":["TRANSLATE","CLIPBOARD"]}]"""
        val folders = decodeToolboxFolders(raw)
        val bar = listOf(ToolbarTool.EMOJI.key, ToolbarTool.VOICE.key)
        assertEquals(bar + folderToolKey("f1"), placedToolbarFolders(bar, folders))
        // Placed once: the flag is cleared, and a bar that has it already is left alone.
        val placed = folders.withLegacyPinsPlaced()
        assertTrue(placed.none { it.legacyPinned })
        assertTrue("pinned" !in encodeToolboxFolders(placed)!!)
        val moved = listOf(folderToolKey("f1")) + bar
        assertEquals(moved, placedToolbarFolders(moved, folders))
    }

    @Test
    fun aPinnedFolderThatIsGoneLeavesTheBar() {
        val folders = listOf(ToolboxFolder("f2", "B", listOf(t, c)))
        val bar = listOf(ToolbarTool.EMOJI.key, folderToolKey("f1"), folderToolKey("f2"))
        assertEquals(listOf(ToolbarTool.EMOJI.key, folderToolKey("f2")), placedToolbarFolders(bar, folders))
        assertEquals(listOf(folderToolKey("f2")), decodeToolKeys(encodeToolKeys(listOf(folderToolKey("f2")))))
    }

    @Test
    fun aPluginFilesIntoAFolderAndTheStoredFormKeepsIt() {
        val folders = emptyList<ToolboxFolder>().withMerge(dropped = grocy, target = c, newName = "Kitchen")
        assertEquals(listOf(ToolboxFolder("f1", "Kitchen", listOf(c, grocy))), folders)
        assertTrue("\"plugin:com.example.grocy\"" in encodeToolboxFolders(folders)!!)
        assertEquals(folders, decodeToolboxFolders(encodeToolboxFolders(folders)))
    }

    @Test
    fun uninstallingAPluginTakesItOutOfItsFolder() {
        val start = listOf(ToolboxFolder("f1", "Kitchen", listOf(c, grocy, t)))
        assertEquals(listOf(ToolboxFolder("f1", "Kitchen", listOf(c, t))), start.withoutPlugin("com.example.grocy"))
        // Down to one tool, the folder goes.
        assertTrue(listOf(ToolboxFolder("f1", "K", listOf(c, grocy))).withoutPlugin("com.example.grocy").isEmpty())
    }
}

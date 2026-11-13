package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.ToolKey
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.ToolboxFolder
import com.wasimaster.wmkeyboard.core.settings.key
import com.wasimaster.wmkeyboard.core.settings.pluginToolKey
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.ToolboxUi
import org.junit.Assert.assertEquals
import org.junit.Test

/** The toolbox grid's folders (#709) and search (#687). */
class ToolboxFoldersGridTest {

    private val d = ToolbarTool.DICTIONARY.key
    private val g = ToolbarTool.GRAMMAR.key
    private val t = ToolbarTool.TRANSLATE.key
    private val c = ToolbarTool.CLIPBOARD.key
    private val grocy = pluginToolKey("com.example.grocy")

    @Test
    fun aFolderTakesTheCellOfItsFirstTool() {
        val folder = ToolboxFolder("f1", "Language", listOf(g, d))
        val cells = toolboxCells(listOf(c, d, t, g), listOf(folder))
        assertEquals(
            listOf(ToolboxCell.Tool(c), ToolboxCell.Folder(folder, listOf(g, d)), ToolboxCell.Tool(t)),
            cells,
        )
    }

    @Test
    fun aFolderShowsOnlyItsVisibleTools() {
        val folder = ToolboxFolder("f1", "Language", listOf(g, d))
        // Grammar pinned to the bar: the folder is still there, holding one.
        assertEquals(listOf(ToolboxCell.Folder(folder, listOf(d))), toolboxCells(listOf(d), listOf(folder)))
        assertEquals(listOf(ToolboxCell.Tool(c)), toolboxCells(listOf(c), listOf(folder)))
    }

    @Test
    fun searchPutsNamesThatStartWithTheQueryFirst() {
        val e = ToolbarTool.EMOJI.key
        // Made-up names, so the ranking is the only thing under test.
        val names = mapOf(d to "Dictionary", e to "Spell check", c to "Clipboard")
        val base = KeyboardUiState()
        val state = base.copy(
            settings = base.settings.copy(toolbarTools = emptyList(), toolboxOrder = listOf(d, e, c)),
            toolboxView = ToolboxUi(searchActive = true, query = "C"),
        )
        // Starts with it, then a word that does, then the rest; case ignored.
        assertEquals(listOf(c, e, d), toolboxSearchMatches(state) { names[it].orEmpty() })
        val none = state.copy(toolboxView = ToolboxUi(searchActive = true, query = "zz"))
        assertEquals(emptyList<ToolKey>(), toolboxSearchMatches(none) { names[it].orEmpty() })
    }

    @Test
    fun aPluginIsACellOfTheGridOnlyWhileItIsLive() {
        val base = KeyboardUiState()
        val settings = base.settings.copy(toolbarTools = emptyList(), toolboxOrder = listOf(c, grocy, d))
        val gone = base.copy(settings = settings)
        assertEquals(listOf(c, d), visibleToolboxTools(gone).filter { it == c || it == d || it == grocy })
        val live = gone.copy(pluginTools = mapOf("com.example.grocy" to PluginToolFace("com.example.grocy", "Grocy", "kitchen", "1")))
        assertEquals(listOf(c, grocy, d), visibleToolboxTools(live).filter { it == c || it == d || it == grocy })
        // In a folder with a built-in tool, like any other.
        val folder = ToolboxFolder("f1", "Kitchen", listOf(grocy, c))
        assertEquals(listOf(ToolboxCell.Folder(folder, listOf(grocy, c)), ToolboxCell.Tool(d)), toolboxCells(listOf(c, grocy, d), listOf(folder)))
    }

    @Test
    fun aPinnedPluginIsOnTheBarAndNotInTheGrid() {
        val base = KeyboardUiState()
        val state = base.copy(
            settings = base.settings.copy(toolbarTools = listOf(grocy), toolboxOrder = listOf(c, grocy)),
            pluginTools = mapOf("com.example.grocy" to PluginToolFace("com.example.grocy", "Grocy", "kitchen", "1")),
        )
        assertEquals(listOf(grocy), visibleToolbarTools(state))
        assertEquals(false, grocy in visibleToolboxTools(state))
        // No digit for a plugin: the badges count built-in tools.
        assertEquals(null, barHintSlot(visibleToolbarTools(state), grocy))
    }
}

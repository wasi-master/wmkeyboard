package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom buttons as tools (#1345): the [ToolKey] form, placing the old ones, and clearing deleted ones. */
class CustomButtonToolsTest {

    private val emoji = ToolbarTool.EMOJI.key
    private val clip = ToolbarTool.CLIPBOARD.key
    private val calc = ToolbarTool.CALCULATOR.key

    private val incognito = CustomButton("c1", CustomButtonKind.SHORTCUT, "intent:#Intent;end", packageName = "org.example")
    private val numberRow = CustomButton("c2", CustomButtonKind.SETTING, CustomToggle.NUMBER_ROW.name, onToolbar = false)
    private val oldTile = CustomButton("c3", CustomButtonKind.PLUGIN, "com.example.weather")

    @Test
    fun tokensRoundTrip() {
        val key = customToolKey("c1")
        assertEquals("custom:c1", key.token)
        assertEquals(key, ToolKey.of("custom:c1"))
        assertEquals("c1", key.customButtonId)
        assertNull(key.tool)
        assertNull(key.pluginId)
        assertNull(emoji.customButtonId)
        assertNull(ToolKey.of("custom:"))
        assertNull(ToolKey.of("custom:a b"))
        assertNull(ToolKey.of("custom:../x"))
        assertEquals(listOf(emoji, key), decodeToolKeys("EMOJI,custom:c1,custom:,custom:c1"))
    }

    @Test
    fun shownOnlyWhileTheButtonIsLive() {
        val key = customToolKey("c1")
        assertTrue(key.shownWith(emptySet(), setOf("c1")) { false })
        assertFalse(key.shownWith(emptySet(), setOf("c2")) { true })
        assertFalse(key.shownWith(emptySet()) { true })
    }

    @Test
    fun unplacedButtonsGoWhereTheirOldSwitchSaid() {
        val placed = placedCustomButtons(listOf(incognito, numberRow, oldTile), listOf(emoji), listOf(clip, calc), emptyList())
        // Pinned at the end of the bar, and ranked in the grid for when it is unpinned.
        assertEquals(listOf(emoji, customToolKey("c1")), placed.bar)
        assertEquals(listOf(clip, calc, customToolKey("c1"), customToolKey("c2")), placed.order)
    }

    @Test
    fun aPlacedButtonKeepsItsPlace() {
        val c1 = customToolKey("c1")
        val c2 = customToolKey("c2")
        // Dragged into the grid, its flag still says toolbar: the lists win.
        val placed = placedCustomButtons(listOf(incognito, numberRow), listOf(emoji, c2), listOf(c1, clip, c2), emptyList())
        assertEquals(listOf(emoji, c2), placed.bar)
        assertEquals(listOf(c1, clip, c2), placed.order)
        // Filed in a folder counts as placed too.
        val folders = listOf(ToolboxFolder("f1", "Mine", listOf(clip, c1)))
        val inFolder = placedCustomButtons(listOf(incognito), listOf(emoji), listOf(clip), folders)
        assertEquals(listOf(emoji), inFolder.bar)
        assertEquals(listOf(clip), inFolder.order)
    }

    @Test
    fun deletedButtonsLeaveEveryList() {
        val c1 = customToolKey("c1")
        val c2 = customToolKey("c2")
        val placed = placedCustomButtons(listOf(numberRow), listOf(emoji, c1), listOf(c1, clip, c2), emptyList())
        assertEquals(listOf(emoji), placed.bar)
        assertEquals(listOf(clip, c2), placed.order)

        val ids = customToolIds(listOf(numberRow))
        val folders = listOf(ToolboxFolder("f1", "Mine", listOf(clip, c1)), ToolboxFolder("f2", "Two", listOf(calc, c2, emoji)))
        // A folder left with one tool dissolves.
        assertEquals(listOf(folders[1]), folders.keepingCustom(ids))
        val mode = KeyboardMode(id = "m", name = "m", toolbarTools = listOf(c1, emoji), toolboxOrder = listOf(c2, c1))
        val kept = mode.keepingCustom(ids)
        assertEquals(listOf(emoji), kept.toolbarTools)
        assertEquals(listOf(c2), kept.toolboxOrder)
    }

    @Test
    fun theSwitchMovesAButtonLikeADrag() {
        val c1 = customToolKey("c1")
        val (bar, order) = withCustomButtonPlace(listOf(emoji), listOf(clip, c1), "c1", onToolbar = true)
        assertEquals(listOf(emoji, c1), bar)
        assertEquals(listOf(clip, c1), order)
        val (back, sameOrder) = withCustomButtonPlace(bar, order, "c1", onToolbar = false)
        assertEquals(listOf(emoji), back)
        assertEquals(listOf(clip, c1), sameOrder)
    }
}

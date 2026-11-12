package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where an app carried along the toolbar lands among the tools (#1258). */
class ToolbarAppSlotTest {

    private val tools = listOf(ToolbarTool.EMOJI, ToolbarTool.CLIPBOARD, ToolbarTool.SETTINGS)
    private val across = mapOf(ToolbarTool.EMOJI to 50f, ToolbarTool.CLIPBOARD to 150f, ToolbarTool.SETTINGS to 250f)

    @Test
    fun `along a row the slot counts the tools left of the finger`() {
        assertEquals(0, appSlotAt(Offset(10f, 0f), tools, across, vertical = false, rtl = false))
        assertEquals(2, appSlotAt(Offset(200f, 0f), tools, across, vertical = false, rtl = false))
        assertEquals(3, appSlotAt(Offset(400f, 0f), tools, across, vertical = false, rtl = false))
    }

    @Test
    fun `a right-to-left row counts from the right`() {
        assertEquals(3, appSlotAt(Offset(10f, 0f), tools, across, vertical = false, rtl = true))
        assertEquals(1, appSlotAt(Offset(200f, 0f), tools, across, vertical = false, rtl = true))
    }

    @Test
    fun `down a column the slot counts the tools above the finger`() {
        assertEquals(1, appSlotAt(Offset(0f, 100f), tools, across, vertical = true, rtl = false))
    }
}

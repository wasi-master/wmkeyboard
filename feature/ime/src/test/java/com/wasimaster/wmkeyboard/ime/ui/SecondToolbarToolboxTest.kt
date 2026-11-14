package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.contains
import com.wasimaster.wmkeyboard.core.settings.key
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The second toolbar (#1217): its tools leave the toolbox while the row is on. */
class SecondToolbarToolboxTest {

    private fun state(on: Boolean): KeyboardUiState {
        val base = KeyboardUiState()
        return base.copy(
            settings = base.settings.copy(
                toolbarTools = emptyList(),
                toolbarBehavior = base.settings.toolbarBehavior.copy(
                    secondRow = on,
                    secondRowTools = listOf(ToolbarTool.COPY.key, ToolbarTool.PASTE.key),
                ),
            ),
        )
    }

    @Test
    fun aToolOnTheSecondToolbarIsNotInTheToolbox() {
        val s = state(on = true)
        assertFalse(ToolbarTool.COPY in visibleToolboxTools(s))
        assertFalse(ToolbarTool.PASTE in visibleToolboxTools(s))
        assertTrue(ToolbarTool.CUT in visibleToolboxTools(s))
        assertEquals(listOf(ToolbarTool.COPY.key, ToolbarTool.PASTE.key), secondToolbarTools(s))
    }

    @Test
    fun withTheRowOffItsToolsGoBackToTheToolbox() {
        val s = state(on = false)
        assertTrue(ToolbarTool.COPY in visibleToolboxTools(s))
        assertTrue(ToolbarTool.PASTE in visibleToolboxTools(s))
    }

    @Test
    fun anEmptyRowStillShowsSoThereIsSomewhereToDrop() {
        val base = state(on = true)
        val empty = base.copy(
            settings = base.settings.copy(
                toolbarBehavior = base.settings.toolbarBehavior.copy(secondRowTools = emptyList()),
            ),
        )
        assertTrue(secondToolbarShown(empty))
    }
}

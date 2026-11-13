package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.ToolKey
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.contains
import com.wasimaster.wmkeyboard.core.settings.key
import com.wasimaster.wmkeyboard.core.settings.ToolboxSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ToolboxSettings.hiddenTools]: out of the grid, still on everywhere else. */
class ToolboxHiddenToolsTest {

    private fun state(pinned: List<ToolKey>, hidden: Set<ToolKey>): KeyboardUiState {
        val base = KeyboardUiState()
        return base.copy(
            settings = base.settings.copy(
                toolbarTools = pinned,
                toolbox = base.settings.toolbox.copy(hiddenTools = hidden),
            ),
        )
    }

    @Test
    fun aHiddenToolLeavesTheGridButStaysEnabled() {
        val shown = state(pinned = emptyList(), hidden = emptySet())
        assertTrue(ToolbarTool.COPY in visibleToolboxTools(shown))

        val hidden = state(pinned = emptyList(), hidden = setOf(ToolbarTool.COPY.key))
        assertFalse(ToolbarTool.COPY in visibleToolboxTools(hidden))
        assertTrue(ToolbarTool.PASTE in visibleToolboxTools(hidden))
        assertTrue(ToolbarTool.COPY in hidden.settings.enabledTools)
    }

    @Test
    fun aHiddenToolThatIsPinnedStaysOnTheBar() {
        val s = state(pinned = listOf(ToolbarTool.COPY.key), hidden = setOf(ToolbarTool.COPY.key))
        assertTrue(ToolbarTool.COPY in visibleToolbarTools(s))
        assertFalse(ToolbarTool.COPY in visibleToolboxTools(s))
    }
}

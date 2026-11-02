package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why a tool is unusable is a typed reason, not a boolean the screens
 * re-interpret. The Secondary layout tool was the first one gated on something
 * other than a search key, and every settings screen offered it an API key.
 */
class ToolAvailabilityTest {

    private val pad = LayoutSpec(
        id = "custom_pad",
        name = "Pad",
        secondary = true,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))),
    )

    @Test
    fun `the secondary layout tool is blocked by the lack of a secondary layout, not a key`() {
        val settings = KeyboardSettings()
        assertEquals(ToolBlocker.NEEDS_SECONDARY_LAYOUT, toolBlocker(ToolbarTool.CUSTOM_LAYOUT, settings))
        assertFalse(isUsableTool(ToolbarTool.CUSTOM_LAYOUT, settings))
        val withPad = settings.copy(customLayouts = listOf(pad))
        assertNull(toolBlocker(ToolbarTool.CUSTOM_LAYOUT, withPad))
        assertTrue(isUsableTool(ToolbarTool.CUSTOM_LAYOUT, withPad))
    }

    @Test
    fun `voice typing switched off blocks the voice tool and only that one`() {
        val off = KeyboardSettings().let { it.copy(voiceBar = it.voiceBar.copy(enabled = false)) }
        assertEquals(ToolBlocker.VOICE_OFF, toolBlocker(ToolbarTool.VOICE, off))
        assertFalse(ToolbarTool.VOICE in usableTools(off))
        assertNull(toolBlocker(ToolbarTool.VOICE, KeyboardSettings()))
        for (tool in ToolbarTool.entries) {
            if (tool != ToolbarTool.VOICE) assertTrue("$tool", toolBlocker(tool, off) != ToolBlocker.VOICE_OFF)
        }
    }

    @Test
    fun `only the search tools can be blocked on a key`() {
        // The others that start blocked want a source or a provider set up, not a key.
        val needSetup = setOf(ToolbarTool.CUSTOM_LAYOUT, ToolbarTool.EMOJI_MIX, ToolbarTool.MUSIC_ID)
        val settings = KeyboardSettings()
        for (tool in ToolbarTool.entries) {
            val blocker = toolBlocker(tool, settings)
            if (blocker == ToolBlocker.NEEDS_SEARCH_KEY) {
                assertTrue("$tool", tool == ToolbarTool.WEB_SEARCH || tool == ToolbarTool.IMAGE_SEARCH)
            }
            if (tool != ToolbarTool.WEB_SEARCH && tool != ToolbarTool.IMAGE_SEARCH && tool !in needSetup) {
                assertNull("$tool", blocker)
            }
        }
    }
}

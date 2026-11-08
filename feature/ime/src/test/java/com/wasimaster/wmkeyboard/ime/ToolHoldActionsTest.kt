package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.DefaultToolHoldActions
import com.wasimaster.wmkeyboard.core.settings.ToolHoldAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarBehavior
import com.wasimaster.wmkeyboard.core.settings.holdActionOf
import com.wasimaster.wmkeyboard.core.settings.ToolHoldActions
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isOwnRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `tool=action` map behind the toolbar's press-and-hold actions, and the
 * placement flag the tools' own row keys off.
 *
 * Both are read on every keyboard frame from a stored string, so the decoder has
 * to be total: a name from a newer build, a half-written entry, a tool bound to
 * itself. None of those may cost the other entries.
 */
class ToolHoldActionsTest {

    @Test
    fun `round trips a map`() {
        val map = mapOf(
            ToolbarTool.UNDO to ToolHoldAction.Run(ToolbarTool.REDO),
            ToolbarTool.TEXT_EDIT to ToolHoldAction.Run(ToolbarTool.CLIPBOARD),
            ToolbarTool.SNIPPETS to ToolHoldAction.None,
        )
        assertEquals(map, ToolHoldActions.decode(ToolHoldActions.encode(map)))
    }

    /** "Does nothing" (#136) is its own token, and no tool can ever be named that. */
    @Test
    fun `a hold bound to nothing survives the round trip and is not a tool`() {
        assertEquals("UNDO=NONE", ToolHoldActions.encode(mapOf(ToolbarTool.UNDO to ToolHoldAction.None)))
        assertEquals(mapOf(ToolbarTool.UNDO to ToolHoldAction.None), ToolHoldActions.decode("UNDO=NONE"))
        assertTrue(ToolbarTool.entries.none { it.name == ToolHoldActions.NONE_TOKEN })
    }

    @Test
    fun `an empty or absent map decodes to nothing bound`() {
        assertTrue(ToolHoldActions.decode(null).isEmpty())
        assertTrue(ToolHoldActions.decode("").isEmpty())
        assertEquals("", ToolHoldActions.encode(emptyMap()))
    }

    /**
     * The load-bearing one: one unreadable entry must cost that entry only. A
     * name this build does not have is exactly what a downgrade reads.
     */
    @Test
    fun `unusable entries are dropped without losing the others`() {
        val decoded = ToolHoldActions.decode(
            "UNDO=REDO,NOT_A_TOOL=REDO,UNDO2=,=REDO,TEXT_EDIT=ALSO_NOT_A_TOOL,SNIPPETS=CLIPBOARD",
        )
        assertEquals(
            mapOf(
                ToolbarTool.UNDO to ToolHoldAction.Run(ToolbarTool.REDO),
                ToolbarTool.SNIPPETS to ToolHoldAction.Run(ToolbarTool.CLIPBOARD),
            ),
            decoded,
        )
    }

    /** Holding a tool to run itself is a tap done slowly, so it is not stored. */
    @Test
    fun `a tool bound to itself is dropped`() {
        assertTrue(ToolHoldActions.decode("UNDO=UNDO").isEmpty())
        assertEquals(
            mapOf(ToolbarTool.UNDO to ToolHoldAction.Run(ToolbarTool.REDO)),
            ToolHoldActions.decode("UNDO=REDO,REDO=REDO"),
        )
    }

    @Test
    fun `the settings page round trips as its own token`() {
        assertEquals("COPY=PAGE", ToolHoldActions.encode(mapOf(ToolbarTool.COPY to ToolHoldAction.OpenSettings)))
        assertEquals(mapOf(ToolbarTool.COPY to ToolHoldAction.OpenSettings), ToolHoldActions.decode("COPY=PAGE"))
        assertTrue(ToolbarTool.entries.none { it.name == ToolHoldActions.SETTINGS_TOKEN })
    }

    /** HeliBoard's pairings (issue #639), then the settings page, then the user. */
    @Test
    fun `an unbound tool holds to its pairing, or to its settings`() {
        val plain = ToolbarBehavior()
        assertEquals(ToolHoldAction.Run(ToolbarTool.REDO), plain.holdActionOf(ToolbarTool.UNDO))
        assertEquals(ToolHoldAction.Run(ToolbarTool.CUT), plain.holdActionOf(ToolbarTool.COPY))
        assertEquals(ToolHoldAction.Run(ToolbarTool.PASTE), plain.holdActionOf(ToolbarTool.CLIPBOARD))
        assertEquals(ToolHoldAction.OpenSettings, plain.holdActionOf(ToolbarTool.TRANSLATE))
        val bound = ToolbarBehavior(holdActions = mapOf(ToolbarTool.UNDO to ToolHoldAction.OpenSettings))
        assertEquals(ToolHoldAction.OpenSettings, bound.holdActionOf(ToolbarTool.UNDO))
    }

    @Test
    fun `no pairing holds a tool to itself`() {
        assertTrue(DefaultToolHoldActions.none { (tool, action) -> tool == action })
    }

    @Test
    fun `only the strip placement shares the suggestion row`() {
        assertFalse(ToolbarPlacement.STRIP.isOwnRow)
        assertTrue(ToolbarPlacement.ON_DEMAND_ROW.isOwnRow)
        assertTrue(ToolbarPlacement.ALWAYS_ROW.isOwnRow)
        assertFalse(ToolbarPlacement.ON_SWIPE.isOwnRow)
    }
}

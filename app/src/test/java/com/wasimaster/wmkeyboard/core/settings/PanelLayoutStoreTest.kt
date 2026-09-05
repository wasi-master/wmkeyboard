package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.canBeEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two old settings the panel layouts replaced, and how they fold in. */
class PanelLayoutStoreTest {

    @Test
    fun `the old default text-edit grid migrates key for key to the shipped pad`() {
        assertEquals(BuiltInPanelLayouts.TEXT_EDIT, migrateTextEditLayout(DefaultTextEditLayout))
    }

    @Test
    fun `a hold on a repeating key is dropped and one on a still key becomes an alternate`() {
        val legacy = TextEditLayout(
            rows = listOf(
                listOf(
                    TextEditKey(TextEditAction.LEFT, longPress = TextEditAction.COPY, width = 2f, rowSpan = 2),
                    TextEditKey(TextEditAction.SELECT, longPress = TextEditAction.SELECT_ALL),
                    TextEditKey(TextEditAction.END, longPress = TextEditAction.END),
                ),
            ),
            rowHeights = listOf(1.5f),
        )
        val migrated = migrateTextEditLayout(legacy)
        val keys = migrated.grid.rows.single()
        assertEquals(KeyAction.Edit(TextEditAction.LEFT), keys[0].action)
        assertEquals(2f, keys[0].width)
        assertEquals(2, keys[0].rowSpan)
        assertTrue(keys[0].actionAlternates.isEmpty())
        assertEquals(KeyAction.Edit(TextEditAction.SELECT_ALL), keys[1].actionAlternates.single().action)
        // A hold that repeats the key's own action is no alternate at all.
        assertTrue(keys[2].actionAlternates.isEmpty())
        assertEquals(listOf(1.5f), migrated.grid.rowHeights)
        assertTrue(migrated.canBeEnabled())
    }

    @Test
    fun `the old bottom-row flag seeds a clipboard layout with the row`() {
        val folded = foldLegacyPanelPrefs(emptyList(), legacyTextEdit = null, legacyClipboardBottomRow = true)
        assertEquals(listOf(BuiltInPanelLayouts.clipboard(bottomRow = true)), folded)
        assertTrue(folded.single().grid.rows.last().any { it.action == KeyAction.Letters })
    }

    @Test
    fun `the flag off or absent seeds nothing`() {
        assertEquals(emptyList<Any>(), foldLegacyPanelPrefs(emptyList(), null, false))
        assertEquals(emptyList<Any>(), foldLegacyPanelPrefs(emptyList(), null, null))
    }

    @Test
    fun `a stored clipboard layout wins over the legacy flag`() {
        val mine = BuiltInPanelLayouts.CLIPBOARD
        assertEquals(listOf(mine), foldLegacyPanelPrefs(listOf(mine), null, true))
    }

    @Test
    fun `the old text-edit grid is read only while no text-edit layout is stored`() {
        val folded = foldLegacyPanelPrefs(emptyList(), DefaultTextEditLayout, null)
        assertEquals(listOf(BuiltInPanelLayouts.TEXT_EDIT), folded)
        val mine = BuiltInPanelLayouts.TEXT_EDIT.copy(grid = BuiltInPanelLayouts.TEXT_EDIT.grid.copy(rowHeights = listOf(2f)))
        assertEquals(listOf(mine), foldLegacyPanelPrefs(listOf(mine), DefaultTextEditLayout, null))
    }

    @Test
    fun `folding keeps the stored layouts of other panels`() {
        val emoji = BuiltInPanelLayouts.EMOJI
        val folded = foldLegacyPanelPrefs(listOf(emoji), DefaultTextEditLayout, true)
        assertEquals(setOf(PanelKind.EMOJI, PanelKind.TEXT_EDIT, PanelKind.CLIPBOARD), folded.mapTo(HashSet()) { it.panel })
        assertEquals(emoji, folded.first())
    }
}

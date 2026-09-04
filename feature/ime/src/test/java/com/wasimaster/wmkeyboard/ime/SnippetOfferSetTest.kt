package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip's side of several expansions: which chips are drawn, and how far a
 * walk into linked snippets is allowed to go.
 */
class SnippetOfferSetTest {

    private fun chip(id: Long, text: String, drillable: Boolean = false) =
        SnippetChip(snippetId = id, label = text, text = text, cursorOffset = text.length, drillable = drillable)

    private fun pick(vararg chips: SnippetChip) = SnippetOfferSet(
        kind = SnippetOfferKind.PICK,
        rootId = 1,
        rootLabel = "Continent",
        consumed = "continent",
        composed = true,
        rootChips = chips.toList(),
    )

    private fun swap(current: Int, vararg chips: SnippetChip) = SnippetOfferSet(
        kind = SnippetOfferKind.SWAP,
        rootId = 1,
        rootLabel = "Continent",
        inserted = chips[current].text,
        insertedCaret = chips[current].text.length,
        current = current,
        rootChips = chips.toList(),
    )

    @Test
    fun `one ask-first chip is still one ask-first chip`() {
        val set = pick(chip(1, "Thanks!"))
        assertTrue(set.isSingle())
        assertEquals(1, set.visibleChips().size)
    }

    @Test
    fun `several chips are not the single-chip case`() {
        assertFalse(pick(chip(1, "Asia"), chip(2, "Africa")).isSingle())
    }

    @Test
    fun `a swap set hides the expansion already in the field`() {
        val set = swap(1, chip(1, "Asia"), chip(1, "Africa"), chip(1, "Europe"))
        assertEquals(listOf("Asia", "Europe"), set.visibleChips().map { it.text })
    }

    @Test
    fun `after a walk a swap set hides nothing`() {
        // Down a level the chips belong to a different snippet, so none of them
        // is the text sitting in the field.
        val set = swap(0, chip(1, "Asia"), chip(2, "Africa"))
            .copy(path = listOf(2), chips = listOf(chip(2, "Africa"), chip(2, "African")))
        assertEquals(listOf("Africa", "African"), set.visibleChips().map { it.text })
    }

    @Test
    fun `a walk never re-enters where it has been`() {
        val set = pick(chip(2, "Asia", drillable = true)).copy(path = listOf(2))
        // The snippet that matched, and everything already on the way down.
        assertFalse(set.canDrill(1))
        assertFalse(set.canDrill(2))
        assertTrue(set.canDrill(3))
    }

    @Test
    fun `the way back up is the path, one level at a time`() {
        val set = pick(chip(2, "Asia")).copy(path = listOf(2, 3))
        assertEquals(2L, set.parentId())
        assertNull(pick(chip(2, "Asia")).copy(path = listOf(2)).parentId())
    }
}

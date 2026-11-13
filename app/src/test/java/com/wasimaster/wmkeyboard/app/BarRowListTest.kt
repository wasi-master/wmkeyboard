package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.settings.DefaultBarOrder
import com.wasimaster.wmkeyboard.core.settings.GifSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.RowSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarBehavior
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which rows the Row order list offers, and what a drag on a filtered list
 * saves.
 *
 * The tools row is only a row of its own under the "A button" and "Always"
 * choices of Where the tools go; with the tools on the strip it is part of the
 * strip's own entry, and an entry for it is a slot the user can drag around to
 * no effect. Hiding it must not cost it its place: it comes back where it was
 * the moment the tools get a row again.
 */
class BarRowListTest {

    private fun settings(placement: ToolbarPlacement) = KeyboardSettings(
        toolbarBehavior = ToolbarBehavior(placement = placement),
    )

    @Test
    fun `tools row is listed only when the tools have a row of their own`() {
        for (placement in listOf(ToolbarPlacement.ALWAYS_ROW, ToolbarPlacement.ON_DEMAND_ROW)) {
            assertEquals(
                DefaultBarOrder,
                barRowsListed(DefaultBarOrder, settings(placement)),
            )
        }
        assertEquals(
            DefaultBarOrder - BarRow.TOOLS,
            barRowsListed(DefaultBarOrder, settings(ToolbarPlacement.STRIP)),
        )
    }

    @Test
    fun `a reorder of the filtered list keeps the hidden row where it was`() {
        val stored = DefaultBarOrder
        val listed = barRowsListed(stored, settings(ToolbarPlacement.STRIP))
        // The user drags nothing: the same list back must be the same order.
        assertEquals(stored, barOrderMerged(listed, stored))
        // Now they move the symbol row to the top. The tools row still sits
        // directly after the emoji row, which is where it was stored.
        val moved = listOf(BarRow.SYMBOL) + listed.filter { it != BarRow.SYMBOL }
        val merged = barOrderMerged(moved, stored)
        assertEquals(stored.toSet(), merged.toSet())
        assertEquals(BarRow.SYMBOL, merged.first())
        assertEquals(merged.indexOf(BarRow.EMOJI) + 1, merged.indexOf(BarRow.TOOLS))
    }

    @Test
    fun `sticker row is listed only while stickers are offered as you type`() {
        val on = KeyboardSettings()
        assertEquals(BarRow.STICKERS, barRowsListed(DefaultBarOrder, on).first())
        val off = KeyboardSettings(gif = GifSettings(stickerSuggest = false))
        val listed = barRowsListed(DefaultBarOrder, off)
        assertEquals(false, BarRow.STICKERS in listed)
        // Moved while hidden, it keeps the place it was stored at.
        val stored = DefaultBarOrder - BarRow.STICKERS + BarRow.STICKERS
        assertEquals(stored.last(), barOrderMerged(barRowsListed(stored, off), stored).last())
    }

    @Test
    fun `recent words are listed only while they have a row of their own`() {
        val ownRow = barRowsListed(DefaultBarOrder, KeyboardSettings())
        assertEquals(true, BarRow.RECENT in ownRow)
        val inline = KeyboardSettings(rows = RowSettings(recentWordPlacement = RecentWordPlacement.INLINE))
        assertEquals(ownRow - BarRow.RECENT, barRowsListed(DefaultBarOrder, inline))
    }

    @Test
    fun `a hidden first row comes back at the front`() {
        val stored = listOf(BarRow.TOOLS, BarRow.EMOJI, BarRow.TOPBAR, BarRow.KEYBOARD)
        val listed = listOf(BarRow.TOPBAR, BarRow.EMOJI, BarRow.KEYBOARD)
        assertEquals(
            listOf(BarRow.TOOLS, BarRow.TOPBAR, BarRow.EMOJI, BarRow.KEYBOARD),
            barOrderMerged(listed, stored),
        )
    }
}

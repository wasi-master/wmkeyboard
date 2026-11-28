package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.RowSettings
import com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.RecentBarWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the recent words draw, and when their row takes height (#894). */
class RecentRowPlacementTest {

    private val words = listOf(RecentBarWord(0, "form", listOf("from")))

    private fun state(
        placement: RecentWordPlacement = RecentWordPlacement.OWN_ROW,
        steady: Boolean = false,
        bar: List<RecentBarWord> = words,
        on: Boolean = true,
    ) = KeyboardUiState(
        settings = KeyboardSettings(
            suggestionStrip = SuggestionStripSettings(recentWordBar = on),
            rows = RowSettings(recentWordPlacement = placement, recentWordSteady = steady),
        ),
        recentBar = bar,
    )

    @Test
    fun `a row of their own shows only while it has words`() {
        assertTrue(recentRowShown(state()))
        assertFalse(recentRowShown(state(bar = emptyList())))
        assertFalse(recentRowShown(state(on = false)))
    }

    @Test
    fun `kept steady the row holds its height while empty`() {
        assertTrue(recentRowShown(state(steady = true, bar = emptyList())))
        assertFalse(recentRowShown(state(steady = true, bar = emptyList(), on = false)))
    }

    @Test
    fun `inline they take no row and sit on the strip`() {
        val inline = state(placement = RecentWordPlacement.INLINE, steady = true)
        assertFalse(recentRowShown(inline))
        assertTrue(recentWordsInline(inline))
        assertFalse(recentWordsInline(state(placement = RecentWordPlacement.INLINE, bar = emptyList())))
        assertFalse(recentWordsInline(state()))
    }

    @Test
    fun `stacked they take no row and are not the inline words`() {
        val stacked = state(placement = RecentWordPlacement.STACKED, steady = true)
        assertFalse(recentRowShown(stacked))
        assertFalse(recentWordsInline(stacked))
        assertTrue(recentStackOn(stacked))
        assertTrue(recentStackShown(stacked))
    }

    @Test
    fun `the stacked strip holds itself up only with words to show`() {
        assertFalse(recentStackShown(state(placement = RecentWordPlacement.STACKED, bar = emptyList())))
        assertFalse(recentStackShown(state(placement = RecentWordPlacement.STACKED, on = false)))
        assertFalse(recentStackShown(state(placement = RecentWordPlacement.INLINE)))
        val selectedOnly = state(placement = RecentWordPlacement.STACKED, bar = emptyList())
            .copy(recentSelected = words.first())
        assertTrue(recentStackShown(selectedOnly))
        val afterOnly = state(placement = RecentWordPlacement.STACKED, bar = emptyList())
            .copy(recentBarAfter = words)
        assertTrue(recentStackShown(afterOnly))
    }

    @Test
    fun `only the stacked strip makes the bar taller`() {
        val plain = state().settings
        val stacked = state(placement = RecentWordPlacement.STACKED).settings
        assertEquals(topBarHeight(plain) + RecentStackExtra, topBarHeight(stacked))
        assertEquals(topBarHeight(plain), topBarHeight(state(placement = RecentWordPlacement.INLINE).settings))
        // Off, the placement left on Stacked changes nothing.
        assertEquals(topBarHeight(plain), topBarHeight(state(placement = RecentWordPlacement.STACKED, on = false).settings))
    }
}

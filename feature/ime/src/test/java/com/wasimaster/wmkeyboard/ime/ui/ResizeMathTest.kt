package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.ime.SizingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline resize tool's drag math and, above all, its Done semantics: a
 * field the user did not drag must reach the repository as null, because a
 * written key height permanently disables the tablet default heights
 * (`keyHeightUntouched`). These pin that contract.
 */
class ResizeMathTest {

    private val entry = ResizeValues(
        keyHeightDp = 48,
        numberRowHeightDp = 42,
        bottomPaddingDp = 8,
        sidePadLeft = 0.1f,
        sidePadRight = 0.05f,
    )

    /**
     * A stand-in for the rendered grid: four rows of the key height plus the
     * number row, in dp, ignoring gaps. Enough for the top-edge arithmetic.
     */
    private fun gridDp(v: ResizeValues): Float = v.keyHeightDp * 4f + v.numberRowHeightDp

    // ---- height drag ----

    @Test
    fun `unit frac returns the start values`() {
        assertEquals(entry, resizeScaledHeights(entry, 1f))
    }

    @Test
    fun `frac scales both heights and keeps their ratio`() {
        val half = resizeScaledHeights(entry, 0.75f)
        assertEquals(36, half.keyHeightDp)
        assertEquals(32, half.numberRowHeightDp) // round(42 * .75)
        // The untouched axis rides along unchanged.
        assertEquals(entry.bottomPaddingDp, half.bottomPaddingDp)
    }

    @Test
    fun `heights clamp to the slider range`() {
        val floor = resizeScaledHeights(entry, 0.1f)
        assertEquals(SettingsRepository.KEY_HEIGHT_MIN_DP, floor.keyHeightDp)
        assertEquals(SettingsRepository.KEY_HEIGHT_MIN_DP, floor.numberRowHeightDp)
        val ceiling = resizeScaledHeights(entry, 10f)
        assertEquals(SettingsRepository.KEY_HEIGHT_MAX_DP, ceiling.keyHeightDp)
        assertEquals(SettingsRepository.KEY_HEIGHT_MAX_DP, ceiling.numberRowHeightDp)
    }

    @Test
    fun `height limit flag matches the clamp`() {
        assertFalse(resizeHeightAtLimit(entry))
        assertTrue(resizeHeightAtLimit(resizeScaledHeights(entry, 0.1f)))
        assertTrue(resizeHeightAtLimit(resizeScaledHeights(entry, 10f)))
    }

    // ---- padding drag ----

    @Test
    fun `finger down sinks the keyboard and finger up lifts it`() {
        val max = SettingsRepository.MAX_BOTTOM_PADDING_DP
        assertEquals(0, resizePaddedBy(entry, dyDp = 20, maxPad = max).bottomPaddingDp)
        assertEquals(28, resizePaddedBy(entry, dyDp = -20, maxPad = max).bottomPaddingDp)
    }

    @Test
    fun `padding clamps to its bounds and flags the limit`() {
        val max = SettingsRepository.MAX_BOTTOM_PADDING_DP
        val floor = resizePaddedBy(entry, dyDp = 999, maxPad = max)
        assertEquals(0, floor.bottomPaddingDp)
        assertTrue(resizePadAtLimit(floor, max))
        val ceiling = resizePaddedBy(entry, dyDp = -999, maxPad = max)
        assertEquals(max, ceiling.bottomPaddingDp)
        assertTrue(resizePadAtLimit(ceiling, max))
        assertFalse(resizePadAtLimit(entry, max))
    }

    // ---- bottom handle: bottom edge follows, top edge pinned ----

    @Test
    fun `bottom handle trades padding for height and keeps the top edge`() {
        val max = SettingsRepository.MAX_BOTTOM_PADDING_DP
        val base = gridDp(entry)
        // Finger up 20dp: the keyboard shrinks from the bottom, the padding
        // takes up exactly what the grid gave.
        val up = resizeBottomEdgeBy(entry, dyDp = -20f, baseGridDp = base, maxPad = max, gridDp = ::gridDp)
        assertTrue(up.keyHeightDp < entry.keyHeightDp)
        val topEdgeBefore = base + entry.bottomPaddingDp
        val topEdgeAfter = gridDp(up) + up.bottomPaddingDp
        assertEquals(topEdgeBefore, topEdgeAfter, 0.5f)
        assertEquals(entry.sidePadLeft, up.sidePadLeft)
    }

    @Test
    fun `bottom handle stops at the floor instead of lifting the top edge`() {
        val max = SettingsRepository.MAX_BOTTOM_PADDING_DP
        val base = gridDp(entry)
        // Only 8dp of padding to give: a 100dp pull grows the grid by 8 and stops.
        val down = resizeBottomEdgeBy(entry, dyDp = 100f, baseGridDp = base, maxPad = max, gridDp = ::gridDp)
        assertEquals(0, down.bottomPaddingDp)
        assertEquals(base + entry.bottomPaddingDp, gridDp(down) + down.bottomPaddingDp, 2f)
        assertTrue(resizeBottomEdgeAtLimit(down, max))
    }

    @Test
    fun `bottom handle at the padding ceiling stops shrinking`() {
        val max = SettingsRepository.MAX_BOTTOM_PADDING_DP
        val base = gridDp(entry)
        val up = resizeBottomEdgeBy(entry, dyDp = -999f, baseGridDp = base, maxPad = max, gridDp = ::gridDp)
        // The heights hit their own floor long before the padding's ceiling
        // (32 * 4 + 32 = 160 of grid to give, and 152 of padding headroom).
        assertTrue(up.bottomPaddingDp <= max)
        assertTrue(up.keyHeightDp >= SettingsRepository.KEY_HEIGHT_MIN_DP)
        assertTrue(resizeBottomEdgeAtLimit(up, max))
    }

    @Test
    fun `bottom handle with no grid to measure is a no-op`() {
        val max = SettingsRepository.MAX_BOTTOM_PADDING_DP
        assertEquals(entry, resizeBottomEdgeBy(entry, dyDp = 30f, baseGridDp = 0f, maxPad = max, gridDp = ::gridDp))
    }

    // ---- side handles ----

    @Test
    fun `left handle grows the left pad rightwards and clamps`() {
        assertEquals(0.2f, resizeLeftPaddedBy(entry, 0.1f).sidePadLeft, 1e-6f)
        assertEquals(0f, resizeLeftPaddedBy(entry, -0.5f).sidePadLeft, 1e-6f)
        assertEquals(SidePadScaleRange.endInclusive, resizeLeftPaddedBy(entry, 0.5f).sidePadLeft, 1e-6f)
        // The other pad and the heights ride along unchanged.
        val moved = resizeLeftPaddedBy(entry, 0.1f)
        assertEquals(entry.sidePadRight, moved.sidePadRight)
        assertEquals(entry.keyHeightDp, moved.keyHeightDp)
    }

    @Test
    fun `right handle grows the right pad leftwards`() {
        assertEquals(0.15f, resizeRightPaddedBy(entry, -0.1f).sidePadRight, 1e-6f)
        assertEquals(0f, resizeRightPaddedBy(entry, 0.5f).sidePadRight, 1e-6f)
    }

    @Test
    fun `side pad limit flag matches the slider range`() {
        assertFalse(resizeSidePadAtLimit(0.1f))
        assertTrue(resizeSidePadAtLimit(0f))
        assertTrue(resizeSidePadAtLimit(SidePadScaleRange.endInclusive))
    }

    // ---- Done semantics ----

    @Test
    fun `no drag at all commits as a cancel`() {
        assertEquals(
            SizingAction.ResizeCancel,
            resizeCommitAction(ScreenVariant.PORTRAIT, entry, result = null),
        )
    }

    @Test
    fun `padding-only session leaves both heights null`() {
        val action = resizeCommitAction(
            ScreenVariant.PORTRAIT,
            entry,
            entry.copy(bottomPaddingDp = 40),
        ) as SizingAction.ResizeCommit
        assertNull(action.keyHeightDp)
        assertNull(action.numberRowHeightDp)
        assertEquals(40, action.bottomPaddingDp)
        assertNull(action.sidePadLeftScale)
        assertNull(action.sidePadRightScale)
    }

    @Test
    fun `side-pad-only session leaves everything else null`() {
        val action = resizeCommitAction(
            ScreenVariant.PORTRAIT,
            entry,
            resizeLeftPaddedBy(entry, 0.1f),
        ) as SizingAction.ResizeCommit
        assertNull(action.keyHeightDp)
        assertNull(action.numberRowHeightDp)
        assertNull(action.bottomPaddingDp)
        assertEquals(0.2f, action.sidePadLeftScale!!, 1e-6f)
        assertNull(action.sidePadRightScale)
    }

    @Test
    fun `height-only session leaves the padding null`() {
        val action = resizeCommitAction(
            ScreenVariant.LANDSCAPE,
            entry,
            resizeScaledHeights(entry, 0.75f),
        ) as SizingAction.ResizeCommit
        assertEquals(ScreenVariant.LANDSCAPE, action.variant)
        assertEquals(36, action.keyHeightDp)
        assertEquals(32, action.numberRowHeightDp)
        assertNull(action.bottomPaddingDp)
    }

    @Test
    fun `dragged away and back commits nothing`() {
        val action = resizeCommitAction(ScreenVariant.PORTRAIT, entry, entry)
            as SizingAction.ResizeCommit
        assertNull(action.keyHeightDp)
        assertNull(action.numberRowHeightDp)
        assertNull(action.bottomPaddingDp)
        assertNull(action.sidePadLeftScale)
        assertNull(action.sidePadRightScale)
    }
}

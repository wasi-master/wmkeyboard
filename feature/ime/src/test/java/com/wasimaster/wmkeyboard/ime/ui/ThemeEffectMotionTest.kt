package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1129: Reacts to typing has to be visible from a single press, and the
 * theme's own effects can be let through Reduce motion.
 */
class ThemeEffectMotionTest {

    @Test
    fun `a still board moves only with typing`() {
        assertEquals(0f, boardClockStep(dtS = 1f, speed = 0f, energy = 0f), 0f)
        assertTrue(boardClockStep(dtS = 1f, speed = 0f, energy = 0.5f) > 0f)
    }

    @Test
    fun `one press is a visible surge`() {
        // One press at full Reacts to typing: the board runs well over its own pace.
        val calm = boardClockStep(dtS = 1f, speed = 1f, energy = 0f)
        val pressed = boardClockStep(dtS = 1f, speed = 1f, energy = ThemePulse.KICK)
        assertEquals(1f, calm, 0f)
        assertTrue("one press gave only ${pressed}x", pressed >= 1.5f)
    }

    @Test
    fun `typing brightens into the room left, never past opaque`() {
        assertEquals(0.6f, typingAlpha(0.6f, 0f), 1e-6f)
        assertTrue(typingAlpha(0.6f, 1f) > 0.85f)
        assertEquals(1f, typingAlpha(1f, 1f), 0f)
        // Energy past full counts as full, and stays under opaque.
        assertEquals(typingAlpha(0.9f, 1f), typingAlpha(0.9f, 5f), 0f)
        assertTrue(typingAlpha(0.9f, 5f) <= 1f)
    }

    @Test
    fun `reduce motion stills the theme unless its effects are let through`() {
        val reduced = KeyboardSettings(reduceMotion = true)
        assertFalse(KbMotion.of(reduced).themeEffects)
        val letThrough = reduced.copy(accessibility = reduced.accessibility.copy(themeEffectsWithReduceMotion = true))
        val motion = KbMotion.of(letThrough)
        assertTrue(motion.themeEffects)
        // Only the theme: everything else Reduce motion covers stays still.
        assertFalse(motion.any)
        assertFalse(motion.panels)
    }

    @Test
    fun `without reduce motion the theme moves`() {
        assertTrue(KbMotion.of(KeyboardSettings()).themeEffects)
    }
}

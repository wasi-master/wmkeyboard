package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.PowerSavingSettings
import com.wasimaster.wmkeyboard.core.settings.underPowerSaving
import com.wasimaster.wmkeyboard.core.theme.KeyEffectKind
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power saving's moving-theme drop (#1160), as the theme resolves it. */
class MovingThemeStillTest {

    private val moving = defaultKbTheme(
        scheme = darkColorScheme(),
        dark = true,
        amoled = false,
        settings = KeyboardSettings(),
    ).copy(
        backgroundAnimated = true,
        animation = ThemeAnimation.HUE_CYCLE,
        keyEffect = KeyEffectKind.STARS,
        touchBloom = KbTouchBloom(color = Color.White, light = 0.5f, radiusDp = 80f, dip = 0.3f, wobble = 0.2f),
    )

    @Test
    fun `a saving keyboard holds the theme still and keeps its look`() {
        val saving = KeyboardSettings(powerSaving = PowerSavingSettings(dropMovingThemes = true)).underPowerSaving()
        val still = moving.accessibilityAdjusted(saving)
        assertFalse(still.backgroundAnimated)
        assertEquals(ThemeAnimation.NONE, still.animation)
        assertNull(still.keyEffect)
        assertNull(still.touchBloom)
        assertTrue("the board effect keeps one frame rather than going", still.boardEffectStill)
        assertEquals("the colours are the theme's own", moving.board, still.board)
        assertFalse("the theme's own reduce-motion flag is left as it was", still.reduceMotion)
    }

    @Test
    fun `without the drop the theme moves`() {
        val saving = KeyboardSettings(
            powerSaving = PowerSavingSettings(dropMovingThemes = false, dropAnimations = false),
        ).underPowerSaving()
        val kept = moving.accessibilityAdjusted(saving)
        assertTrue(kept.backgroundAnimated)
        assertNotNull(kept.touchBloom)
        assertFalse(kept.boardEffectStill)
    }

    @Test
    fun `a keyboard that is not saving is untouched`() {
        assertEquals(moving, moving.accessibilityAdjusted(KeyboardSettings()))
    }
}

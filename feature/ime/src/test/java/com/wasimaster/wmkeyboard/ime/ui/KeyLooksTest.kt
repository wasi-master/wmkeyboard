package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.settings.KeyLabelCase
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LayoutBehaviorSettings
import com.wasimaster.wmkeyboard.core.theme.KeyOverride
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Key looks from the competitor gap list: a corner radius per class of key
 * (item 29), the letters' drawn case (item 31) and the preview bubble's width
 * (item 30).
 */
class KeyLooksTest {

    private fun palette(
        modifierRadius: Int? = null,
        actionRadius: Int? = null,
        overrides: Map<String, KeyOverride> = emptyMap(),
    ) = KeyPalette(
        key = Color.White,
        keyText = Color.Black,
        modifierKey = Color.Gray,
        modifierKeyText = Color.DarkGray,
        enterKey = Color.Blue,
        enterKeyText = Color.Yellow,
        pressedKey = Color.LightGray,
        accent = Color.Magenta,
        overrides = overrides,
        modifierRadiusDp = modifierRadius,
        actionRadiusDp = actionRadius,
    )

    private fun state(case: KeyLabelCase = KeyLabelCase.FOLLOW_SHIFT) = KeyboardUiState(
        settings = KeyboardSettings(layoutBehavior = LayoutBehaviorSettings(labelCase = case)),
        layouts = LayoutSet(
            BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS),
            BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS),
            BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS_SHIFTED),
        ),
    )

    private val letter = Key("q")
    private val shift = Key("⇧", action = KeyAction.Shift)
    private val delete = Key("⌫", action = KeyAction.Delete)
    private val enter = Key("⏎", action = KeyAction.Enter)
    private val space = Key("", action = KeyAction.Space)

    // ---- item 29 ----

    @Test
    fun `with no class radii every key follows the board`() {
        val p = palette()
        for (key in listOf(letter, shift, delete, enter, space)) {
            assertNull(key.label, keyVisual(key, state(), p).cornerRadiusDp)
        }
    }

    @Test
    fun `function and action keys take their own class radius, letters never do`() {
        val p = palette(modifierRadius = 3, actionRadius = 20)
        assertNull(keyVisual(letter, state(), p).cornerRadiusDp)
        assertEquals(3, keyVisual(shift, state(), p).cornerRadiusDp)
        assertEquals(3, keyVisual(delete, state(), p).cornerRadiusDp)
        assertEquals(20, keyVisual(enter, state(), p).cornerRadiusDp)
        assertEquals(20, keyVisual(space, state(), p).cornerRadiusDp)
    }

    @Test
    fun `a single-key style's radius wins over its class`() {
        val p = palette(actionRadius = 20, overrides = mapOf("ENTER" to KeyOverride(cornerRadiusDp = 2)))
        assertEquals(2, keyVisual(enter, state(), p).cornerRadiusDp)
        assertEquals(20, keyVisual(space, state(), p).cornerRadiusDp)
    }

    // ---- item 31 ----

    @Test
    fun `follow shift draws the case shift will type`() {
        assertEquals("q", keyVisual(letter, state(), palette()).label)
        assertEquals("Q", keyVisual(letter, state().copy(shiftState = ShiftState.ON), palette()).label)
    }

    @Test
    fun `lowercase and capitals hold whatever shift does`() {
        val lower = state(KeyLabelCase.LOWER)
        assertEquals("q", keyVisual(letter, lower.copy(shiftState = ShiftState.ON), palette()).label)
        assertEquals("q", keyVisual(letter, lower.copy(shiftState = ShiftState.CAPS_LOCK), palette()).label)
        val upper = state(KeyLabelCase.UPPER)
        assertEquals("Q", keyVisual(letter, upper, palette()).label)
        assertEquals("Q", keyVisual(letter, upper.copy(shiftState = ShiftState.ON), palette()).label)
    }

    @Test
    fun `only letters are recased`() {
        val upper = state(KeyLabelCase.UPPER)
        assertEquals("1", keyVisual(Key("1"), upper, palette()).label)
        assertEquals(",", keyVisual(Key(","), upper, palette()).label)
    }

    // ---- item 30 ----

    @Test
    fun `the bubble keeps its old width at one`() {
        assertEquals(48.dp, previewBubbleMinWidth(40.dp, onKeyStyle = true, scale = 1f))
        assertEquals(0.dp, previewBubbleMinWidth(40.dp, onKeyStyle = false, scale = 1f))
    }

    @Test
    fun `the scale widens it in proportion to the key`() {
        assertEquals(72.dp, previewBubbleMinWidth(40.dp, onKeyStyle = true, scale = 1.5f))
        assertEquals(60.dp, previewBubbleMinWidth(40.dp, onKeyStyle = false, scale = 1.5f))
        assertEquals(0.dp, previewBubbleMinWidth(40.dp, onKeyStyle = false, scale = 0.6f))
    }
}

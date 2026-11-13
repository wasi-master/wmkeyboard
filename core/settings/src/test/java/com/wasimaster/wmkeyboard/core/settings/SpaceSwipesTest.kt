package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.SpaceSwipeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spacebar swipes that run any action (competitor gap list item 3): the
 * swipe-up answer read from the two switches it replaced, and the key each
 * discrete answer presses.
 */
class SpaceSwipesTest {

    private val textEdit = KeyAlternate(KeyAction.Tool(ToolbarTool.TEXT_EDIT))

    @Test
    fun `a fresh install swipes up to nothing`() {
        assertEquals(SpaceSwipeUp.NONE, spaceSwipeUpOf(null, null, null))
        assertEquals(SpaceSwipeUp.NONE, TextEditingSettings().spaceSwipeUp)
    }

    @Test
    fun `the old switches carry over`() {
        assertEquals(SpaceSwipeUp.TOUCHPAD, spaceSwipeUpOf(null, legacyTouchpad = true, legacyNumpad = null))
        assertEquals(SpaceSwipeUp.NUMPAD, spaceSwipeUpOf(null, legacyTouchpad = null, legacyNumpad = true))
        assertEquals(SpaceSwipeUp.NONE, spaceSwipeUpOf(null, legacyTouchpad = false, legacyNumpad = false))
    }

    /** The touchpad won when both switches were on, so it still does. */
    @Test
    fun `both old switches on keep the touchpad`() {
        assertEquals(SpaceSwipeUp.TOUCHPAD, spaceSwipeUpOf(null, legacyTouchpad = true, legacyNumpad = true))
    }

    @Test
    fun `a stored answer beats the old switches`() {
        assertEquals(SpaceSwipeUp.ACTION, spaceSwipeUpOf("ACTION", legacyTouchpad = true, legacyNumpad = true))
        assertEquals(SpaceSwipeUp.NONE, spaceSwipeUpOf("NONE", legacyTouchpad = true, legacyNumpad = null))
    }

    /** A name from a newer build is not an answer; the switches still are. */
    @Test
    fun `an unknown stored answer falls back to the switches`() {
        assertEquals(SpaceSwipeUp.NUMPAD, spaceSwipeUpOf("TELEPORT", legacyTouchpad = null, legacyNumpad = true))
        assertEquals(SpaceSwipeUp.NONE, spaceSwipeUpOf("TELEPORT", null, null))
    }

    /** Every answer that existed before keeps pressing the key it pressed. */
    @Test
    fun `the old sideways answers press what they always did`() {
        assertEquals(KeyAction.Numpad, SpaceSwipeAction.NUMPAD.pressedAction(null)?.action)
        assertEquals(KeyAction.InputMethodPicker, SpaceSwipeAction.KEYBOARDS.pressedAction(null)?.action)
        assertEquals(
            KeyAction.Tool(ToolbarTool.CUSTOM_LAYOUT),
            SpaceSwipeAction.SECONDARY_LAYOUT.pressedAction(null)?.action,
        )
        // A binding left over from an action slot never leaks into them.
        assertEquals(KeyAction.Numpad, SpaceSwipeAction.NUMPAD.pressedAction(textEdit)?.action)
    }

    /** The drags press nothing: they run on the finger, not on the lift. */
    @Test
    fun `cursor language and nothing press no key`() {
        for (drag in listOf(SpaceSwipeAction.NONE, SpaceSwipeAction.LANGUAGE, SpaceSwipeAction.CURSOR)) {
            assertNull(drag.pressedAction(textEdit))
        }
        assertNull(SpaceSwipeUp.TOUCHPAD.pressedAction(textEdit))
        assertNull(SpaceSwipeUp.NONE.pressedAction(textEdit))
    }

    @Test
    fun `another action presses its binding`() {
        assertEquals(textEdit, SpaceSwipeAction.ACTION.pressedAction(textEdit))
        assertEquals(textEdit, SpaceSwipeUp.ACTION.pressedAction(textEdit))
        assertEquals(KeyAction.Numpad, SpaceSwipeUp.NUMPAD.pressedAction(textEdit)?.action)
    }

    @Test
    fun `another action with nothing bound does nothing`() {
        assertNull(SpaceSwipeAction.ACTION.pressedAction(null))
        assertNull(SpaceSwipeAction.ACTION.pressedAction(KeyAlternate(KeyAction.None)))
        assertNull(SpaceSwipeUp.ACTION.pressedAction(null))
    }

    @Test
    fun `each slot reads its own binding`() {
        val settings = TextEditingSettings(
            spaceSwipeActions = mapOf(
                SpaceSwipeSlot.SHORT to textEdit,
                SpaceSwipeSlot.UP to KeyAlternate(KeyAction.Edit(TextEditAction.PASTE)),
            ),
        )
        assertEquals(textEdit, settings.spaceSwipeBinding(SpaceSwipeSlot.SHORT))
        assertNull(settings.spaceSwipeBinding(SpaceSwipeSlot.LONG))
        assertEquals(KeyAction.Edit(TextEditAction.PASTE), settings.spaceSwipeBinding(SpaceSwipeSlot.UP)?.action)
    }

    /** Values stored by earlier builds still name the same answers. */
    @Test
    fun `stored names of the sideways answers are unchanged`() {
        val names = listOf("NONE", "LANGUAGE", "CURSOR", "NUMPAD", "KEYBOARDS", "SECONDARY_LAYOUT")
        assertEquals(names, SpaceSwipeAction.entries.map { it.name }.take(names.size))
        for (name in names) assertEquals(name, SpaceSwipeAction.valueOf(name).name)
    }
}
